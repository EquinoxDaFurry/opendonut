# OpenDonut 🍩

**Several independent Minecraft servers that behave like one continuous world.**

OpenDonut is an open-source multi-server world system for Minecraft Java Edition. Players connect through a
single Velocity proxy address, walk across rectangular server boundaries in a shared global coordinate
system, and are handed to whichever backend owns their destination coordinates — arriving at the same global
position they left.

```
                        ┌─────────────────────────────┐
   player ────────────► │  Velocity  127.0.0.1:25565  │
                        │  + OpenDonut plugin         │
                        │    (authoritative routing)  │
                        └──────────┬──────────┬───────┘
                                   │          │
                  X = [-100000,0)  │          │  X = [0,100000)
                                   ▼          ▼
                        ┌──────────────┐  ┌──────────────┐
                        │ Server1      │  │ Server2      │
                        │ Fabric 26.3  │  │ Fabric 26.3  │
                        │ + OpenDonut  │  │ + OpenDonut  │
                        │   region west│  │   region east│
                        └──────────────┘  └──────────────┘
```

---

## What problem does it solve?

A single Minecraft server is limited by one machine's CPU, memory and view distance. The usual answer is to
split the world across several servers, but players then run into a wall: the world is no longer continuous.
Coordinates are meaningless, walking to a neighbour means typing `/server other`, and the boundary is obvious.

OpenDonut makes the split invisible — or, more precisely, it makes the split *administrable*:

* **Arbitrary rectangular regions.** A server owns a rectangle of the global coordinate space
  (`min-x`, `max-x`, `min-z`, `max-z`, minimum-inclusive and maximum-exclusive). There is no fixed tile grid
  and no chunk-size constraint, so a network can be two servers split on one axis, four in a 2×2 grid, or
  twenty in whatever shape the hardware justifies.
* **One source of truth.** A single YAML file describes the world and every backend. The proxy and each
  backend load the same document, so adding a server means editing one file — never Java code, and never a
  per-machine rule.
* **The proxy decides.** A backend may *ask* for a player to be moved; only the proxy decides whether it
  happens, and it re-derives the destination from its own copy of the configuration. Nothing is routed on the
  word of a client.
* **Verification instead of assumption.** Every backend proves at runtime that it is the server it claims,
  that it owns the region it claims, and that it is generating the *same* world — same level name, same seed.
  A backend that fails the check does not receive players, and says why in the log.

## Current status — read this first

OpenDonut is at **0.1.0-dev**, an early prototype. Being precise about that is more useful than a roadmap:

| Area | State |
|---|---|
| Configuration engine (models, YAML loader, validator, region lookup, seed persistence, bridge protocol) | **Complete, with 207 unit tests passing** |
| Region lookup engine | **Complete.** Coordinate-compressed index with binary search, cross-checked against a reference implementation over randomised layouts |
| Configuration validation | **Complete.** Detects duplicate ids, inverted and misaligned bounds, overlaps, gaps, out-of-border regions, invalid addresses, unknown keys, bad YAML — with file paths and line numbers |
| Velocity plugin | **Implemented** against velocity-api 4.3.1: channel registration, HMAC-authenticated messages, authoritative routing, backend verification, connection requests, admin command |
| Fabric mod | **Implemented** against Minecraft 26.3 / Fabric Loader 0.20.1: tick-based boundary detection, transfer requests, arrival placement, failure recovery, admin command |
| End-to-end transfer with a real client | **Not yet verified.** The integration was built and compiles against the real APIs, but this repository has no automated way to attach a Minecraft client and walk across a boundary. Treat the first run as a test to be observed, not a feature to be trusted. |
| Player data (inventory, health, XP) across a transfer | **Not implemented.** Velocity and Fabric do not synchronise it, and OpenDonut does not pretend otherwise. See [docs/architecture.md](docs/architecture.md#what-is-not-synchronised) |
| Block modification / entity ownership enforcement | **Not implemented.** The *player* is kept inside their region; chunk-level write protection is future work |
| Seamless (non-teleporting) arrival | **Not implemented.** Arrival is a teleport to the global coordinate, not a transparent hand-off |

[`docs/architecture.md`](docs/architecture.md) explains the design, why each decision was made, and what each
limitation would take to remove.

## Requirements

| Component | Version | Why |
|---|---|---|
| Java | **25** | Minecraft 26.3 requires Java 25, and Velocity 4.3.1 requires Java 25 as a minimum |
| Minecraft | **26.3** (Fabric) | Backends run Fabric Loader 0.20.1; no mappings are needed because 26.x is deobfuscated |
| Velocity | **4.3.1** | Plugin API 4.3.1 |
| Gradle | 9.8.2 | Bundled via the wrapper |
| Fabric API | 0.166.2+26.3 | On every backend |
| FabricProxy-Lite | 2.12.0 | On every backend, for secure Velocity forwarding |

Exact version evidence and what to check before bumping are in
[docs/architecture.md](docs/architecture.md#version-matrix).

## Quick start

```bash
# 1. Build everything (tests included)
./gradlew build                       # Windows: gradlew.bat build

# 2. Validate the configuration before anything else
java -jar common/build/libs/opendonut-tools-0.1.0-dev.jar check config/opendonut.example.yml

# 3. Resolve the world seed and put it in every backend's server.properties
java -jar common/build/libs/opendonut-tools-0.1.0-dev.jar seed config/opendonut.example.yml

# 4. Create the bridge secret and copy it to the proxy and every backend
java -jar common/build/libs/opendonut-tools-0.1.0-dev.jar secret secrets/bridge-secret.txt

# 5. Install: see docs/installation.md
```

The command line tool answers the same questions the running components answer, using the same code — which
is what makes it useful for diagnosis:

```console
$ java -jar opendonut-tools.jar route config/opendonut.example.yml -1 0
coordinate (-1, 0) chunk (-1, 0)
  inside world border : true  center=(0,0) size=200000 X=[-100000,100000) Z=[-100000,100000)
  owning server       : west (127.0.0.1:25566) X=[-100000,0) Z=[-100000,100000)
  reference check     : agrees (linear scan)
  offset in region    : 99999 blocks from min-x, 100000 blocks from min-z
```

## Example configuration

One file describes the whole network. The short version, with the reasoning removed (the annotated original is
[`config/opendonut.example.yml`](config/opendonut.example.yml)):

```yaml
config-version: 1

world:
  name: "world"
  seed: random            # or a number; 'random' generates once and persists it
  border:
    center-x: 0
    center-z: 0
    size: 200000          # multiple of 16, centred on a chunk boundary

validation:
  require-full-coverage: false   # gaps are a warning, not an error, unless you insist
  unknown-fields: warn

bridge:
  secret-file: "secrets/bridge-secret.txt"
  timestamp-tolerance-seconds: 30

transfer:
  boundary-margin-blocks: 2
  on-transfer-failure: revert    # push the player back; safer than leaving them in a region they do not own

servers:
  - id: west
    address: "127.0.0.1:25566"
    bounds: { min-x: -100000, max-x: 0, min-z: -100000, max-z: 100000 }

  - id: east
    address: "127.0.0.1:25567"
    bounds: { min-x: 0, max-x: 100000, min-z: -100000, max-z: 100000 }
```

**Bounds are minimum-inclusive, maximum-exclusive.** `x = -1` belongs to `west`; `x = 0` belongs to `east`.
Two regions that share an edge therefore claim no block in common, which is what keeps two servers from
generating the same chunk. Both halves together tile the 200000 × 200000 border exactly, so no coordinate is
unowned.

More examples: [`examples/two-servers`](examples/two-servers) and [`examples/four-servers`](examples/four-servers)
(the latter shows that adding servers is a YAML edit: it splits the same border into a 2×2 grid with no code
change).

## How a transfer works

1. A backend notices, on a server tick, that one of its players has left the rectangle it owns by at least
   `boundary-margin-blocks`.
2. It sends an **authenticated** `TRANSFER_REQUEST` to the proxy through that player's connection. Plugin
   messages are the only channel a Fabric mod and a Velocity plugin share.
3. The proxy authenticates the frame (HMAC-SHA256 over a shared secret, plus a timestamp and a nonce), checks
   that only a backend — never a client — sent it, and **re-derives the destination from its own region
   table**. The backend's suggestion is a hint, not an instruction.
4. The proxy checks that the destination has verified itself, then issues the connection request.
5. When the player has connected, the proxy sends the destination a `PLACE_PLAYER` with the global coordinates
   (Velocity can only send plugin messages through an established connection, which is why the coordinates
   arrive *after* the connect).
6. The destination places the player at those global coordinates and starts a grace period, so the arrival is
   not mistaken for a departure.
7. The requesting backend is told the outcome. On failure it applies `on-transfer-failure`: by default the
   player is moved back inside their own region, because a player standing in someone else's region while
   connected here is the state that corrupts worlds.

Details, including the security reasoning and the failure cases, are in
[docs/architecture.md](docs/architecture.md#the-transfer-protocol).

## Documentation

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Design, the version matrix and its evidence, the bridge protocol, threading, and what is *not* synchronised |
| [docs/configuration.md](docs/configuration.md) | Every configuration key, the coordinate rules, negative coordinates and chunk alignment |
| [docs/installation.md](docs/installation.md) | Installing the proxy and backends, secure forwarding, seeding worlds, running the tests |
| [docs/adding-servers.md](docs/adding-servers.md) | Adding the third, fourth and twentieth server without touching Java code |
| [docs/troubleshooting.md](docs/troubleshooting.md) | Every symptom with its diagnosis, including seed changes and failed transfers |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Building, testing, and the rules a change has to follow |

## Project layout

```
OpenDonut/
├── common/            pure Java: configuration, regions, validation, seed, bridge protocol, CLI
├── fabric-mod/        Minecraft 26.3 backend integration
├── velocity-plugin/   Velocity proxy integration
├── config/            the annotated example configuration
├── docs/              architecture, configuration, installation, adding servers, troubleshooting
├── examples/          two-server and four-server configurations
├── tests/             integration test notes, runtime scratch space (gitignored) and results
└── tools/             helper scripts for local environments
```

`common` is deliberately Minecraft-free: it is loaded by both the mod and the plugin, and it is also the
implementation behind the command line tool. That is what makes the routing engine testable without a server,
and what lets an administrator ask the same questions the running system asks.

## Testing

```bash
./gradlew test                       # 207 unit tests
./gradlew :common:toolsJar           # the self-contained admin CLI
```

The suite covers the cases that actually break multi-server worlds: every world corner, every region boundary
from both sides, negative coordinates around the origin, overlapping regions, unassigned areas, randomised
layout cross-checks between the fast index and a reference scan, YAML diagnostics with line numbers, seed
persistence semantics, and bit-level tampering with bridge frames.

## Contributing

Issues and pull requests are welcome. [CONTRIBUTING.md](CONTRIBUTING.md) covers the build, the test
requirements and the review standards. If you are reporting a routing problem, `report`, `route` and `check`
output from the CLI is worth more than a description.

## License

MIT — see [LICENSE](LICENSE).

OpenDonut is not affiliated with Mojang, Microsoft, Fabric or the Velocity project.
