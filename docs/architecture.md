# OpenDonut architecture

This document explains how OpenDonut works, why each significant decision was made, and — just as
importantly — what it does not do. If you are reading the code, start here: the javadoc in each class assumes
you know the shape of the system described below.

---

## 1. The problem, stated precisely

Several Minecraft servers must behave like one world:

* **One address.** Players connect to a single proxy. They never choose a server.
* **One coordinate system.** The number `x = 12346` means the same place on every backend.
* **No shared ownership.** No chunk may be generated, loaded or written by two backends, ever. This is the
  constraint everything else is built around: two servers writing the same chunk is not a performance problem,
  it is silent world corruption.
* **No trusted client.** A modified client can send anything on any channel, including the plugin message
  channel the backends use. Routing must not depend on the client's word.

The last point is why OpenDonut has an authenticated protocol at all, rather than simply passing server names
around in plugin messages as many scripts do.

## 2. Component responsibilities

```
                       ┌───────────────────────────────────────────────┐
                       │ common/   (no Minecraft, no Velocity classes) │
                       │                                               │
                       │  config models · YAML loader · validator      │
                       │  region index · seed store · bridge codec     │
                       │  CLI: check · report · route · seed · secret  │
                       └───────▲───────────────────────────────▲───────┘
                               │                               │
              ┌────────────────┴──────────────┐   ┌────────────┴───────────────────┐
              │ fabric-mod/                   │   │ velocity-plugin/               │
              │ Minecraft 26.3 backend        │   │ Velocity 4.3.1 proxy           │
              │                               │   │                                │
              │ observes player positions     │   │ owns the routing table         │
              │ asks for transfers            │   │ verifies backends              │
              │ places arriving players       │   │ issues connection requests     │
              │ applies failure policy        │   │ sends placements               │
              └───────────────▲───────────────┘   └────────────┬───────────────────┘
                              │                                │
                              └──── authenticated plugin messages ┘
                                          (HMAC + nonce + timestamp)
```

The three modules exist because the same logic must run in three places with wildly different class paths.
`common` is the only module that knows the *rules*; the other two know only how to talk to their host.

| Module | Must not contain | Why it matters |
|---|---|---|
| `common` | Minecraft, Fabric or Velocity classes | It runs in both hosts *and* as a standalone CLI. A Minecraft import would make the CLI unloadable and the region engine untestable. |
| `fabric-mod` | Routing decisions | The backend's view is local. Only the proxy can see the whole network. |
| `velocity-plugin` | Minecraft world access | Velocity has no world; it must never behave as if it does. |

## 3. Region ownership

### 3.1 Rectangles, not tiles

A region is a rectangle in global block coordinates with **minimum-inclusive, maximum-exclusive** bounds:

```
min-x <= x < max-x        min-z <= z < max-z
```

Half-open intervals are used because they make the shared-edge case unambiguous. With closed intervals,
`west` ending at 0 and `east` starting at 0 both claim block 0 — a one-block-wide strip of permanently
contested world. With half-open intervals:

```
west  X = [-100000, 0)      blocks -100000 .. -1
east  X = [      0, 100000) blocks 0 .. 99999
```

Block `-1` is west's, block `0` is east's, and no block is claimed twice. This is also how Minecraft itself
treats chunks, so region edges and chunk edges behave identically.

### 3.2 Chunk alignment, and negative coordinates

A region whose edge falls inside a chunk would force two servers to generate and write the same chunk, so
bounds must be multiples of 16. Block coordinates map to chunk coordinates by **floor** division, which matters
as soon as coordinates go negative:

```
block  x :  ... -33 -32 | -17 -16 | -1  0 | 15 16 | 31 32 ...
chunk    :      -3  -2  |  -2  -1  | -1  0 |  0  1 |  1  2
```

Java's `/` truncates towards zero, so `-1 / 16 == 0`, which would put block `-1` in chunk `0`. `ChunkMath`
uses `>> 4` (an arithmetic shift, i.e. floor division by 16) and documents the rule. Chunk `-1` covers blocks
`-16 .. -1`; chunk `0` covers `0 .. 15`.

The world border must be aligned too — a size that is a multiple of 16 with a centre that is a multiple of 16
— otherwise no region inside it can be aligned. The validator says exactly that if you get it wrong.

### 3.3 The lookup engine

`RegionIndex.findRegion(x, z)` answers "who owns this coordinate?" and is called once per player per tick. The
production implementation, `SlabRegionIndex`, uses coordinate compression on the X axis:

1. Collect every region's `min-x` and `max-x`. With `n` regions that is at most `2n` distinct values.
2. Those values cut the X axis into *slabs* — ranges of X in which the set of candidate regions never changes.
   Two regions sharing an edge produce two slabs, not four.
3. Inside a slab, the candidates are sorted by their minimum Z. For a valid configuration their Z intervals are
   disjoint, so a binary search finds the owner — or proves the coordinate is unassigned — in `O(log n)`.

Misaligned or overlapping regions (only possible in a configuration that failed validation) are handled too: a
slab whose Z intervals overlap is flagged at build time and lookups there fall back to an exact scan, so
`findRegions` always reports *every* owner and the validator can describe the conflict accurately.

Why not a grid or an R-tree? A grid needs a cell size, and the right cell size depends on the layout — choose
badly and lookups degrade to a linear scan. An R-tree is more code and more allocation per lookup than a
structure needs when the rectangles are static, axis-aligned and non-overlapping. Compression exploits exactly
those properties.

`LinearRegionIndex` is a plain scan, kept as an independent reference implementation. The test suite cross-checks
the two over randomised layouts — including abutting edges, shared corners, negative coordinates and deliberate
overlaps — by sampling points one block inside and outside every edge. If the two ever disagree, the test says
so with the seed that reproduced it.

### 3.4 Coverage analysis

`RegionCoverage` computes the union area of the regions inside a border, the exact unassigned area, a sample of
the unassigned rectangles, and every pair of regions that share a block. It sweeps the *area*, cutting it at
every region edge clipped to the area, so a stretch of X with no region at all is still accounted for.

That last detail is not academic: an analysis built only from the regions' own edges sees no slab for an empty
column of the world, reports a fully covered world, and hides a hole a player can walk into. The record's
constructor enforces `covered + unassigned == analysed area` as an invariant, so that class of bug cannot come
back unnoticed.

## 4. Configuration

### 4.1 One file, many machines

A single YAML document describes the world and every backend. The proxy and each backend load the same file.
Adding a server is a YAML edit; the JVM never needs to change, because nothing is compiled per server.

Each backend still has to know *which* entry is its own. It works that out by matching its listening port
against the configured addresses, so a new machine needs no per-machine file. `backend.server-id` exists as an
override for the cases auto-detection cannot solve — two backends behind one port in a container runtime, or a
configuration that names a public hostname while the server binds loopback. The validator warns when two servers
share a port, because that makes auto-detection ambiguous.

### 4.2 Parsing for humans

The loader works on SnakeYAML's node API rather than binding YAML to Java objects. Binding is convenient and
useless for a validator: the first mistake aborts the load, and the message names a Java field rather than the
line the administrator typed. Working on nodes keeps the source line of every value, so:

* every finding carries a configuration path and a line number;
* *all* problems are reported in one pass, instead of one per build;
* unknown keys produce a "did you mean" suggestion, because `max_x` for `max-x` is the single most common typo
  and it otherwise silently produces a region nobody intended.

Parsing is bounded (document size, alias count, nesting depth) and uses `SafeConstructor`, so a configuration
file cannot be turned into a gadget: OpenDonut only ever reads scalars, lists and mappings.

### 4.3 Validation: errors, warnings, information

The distinction drives real behaviour rather than cosmetics.

| Severity | Effect | Examples |
|---|---|---|
| `ERROR` | The proxy refuses to route; a backend refuses to transfer | Overlapping regions, inverted bounds, duplicate ids, a region outside the border, a seed mismatch, `accepts-transfers=false` |
| `WARNING` | Runs, and says so | Unassigned areas (unless full coverage is required), a key that is not recognised, a secret readable by other users |
| `INFO` | Reporting only | Coverage statistics, the derived border, a cooldown value that has no effect |

Two of these are configurable, because they are design choices rather than mistakes:
`validation.require-full-coverage` decides whether a gap is fatal, and `validation.strict-chunk-alignment`
decides whether a misaligned region is fatal or merely dangerous. OpenDonut never refuses to start merely
because a network is still being built out.

`ConfigLoadResult` keeps the two failure modes distinct: a document that cannot be parsed into a model at all,
versus a model that exists but must not be used for routing. The CLI can therefore *print* a broken
configuration — including its routing table and coverage — which is what makes it useful for diagnosis.

## 5. Seeds

A random seed must be random exactly once. If every backend generated its own, the servers would carve out
visibly different terrain and a player crossing a boundary would walk into a different landscape.

`SeedStore` implements three rules:

1. `seed: random` with no stored value → generate once and write `seed.properties` next to the configuration.
2. `seed: random` with a stored value → use the stored value. **Never regenerate.**
3. `seed: <number>` → the configuration wins, and a disagreement with the stored value is reported, because it
   usually means one backend was started before the seed was changed and is now generating different terrain.

The proxy is the authority: it resolves the seed at startup, logs the exact `level-seed=` line to put in
`server.properties`, and includes the value in every HELLO acknowledgement. A backend that compares it against
its own world's seed and finds a mismatch logs an error and **disables transfers**, because routing players into
terrain that does not match produces a boundary nobody can cross without noticing.

Changing the seed of a world that already exists does **not** regenerate its chunks — Minecraft keeps what it
has generated. To get new terrain the world folders must be recreated, which OpenDonut never does for you. See
[troubleshooting.md](troubleshooting.md#changing-the-seed-deliberately).

`seed.properties` is deliberately a commented properties file rather than JSON, so an administrator can read
it, understand it, and copy it between machines with any tool at hand.

## 6. The bridge protocol

### 6.1 Threat model

Plugin messages are the only channel a Fabric mod and a Velocity plugin share, and they have awkward
properties:

1. They are addressed to a **player**, so Velocity can only send one through an established connection.
2. From the backend's point of view, a plugin message looks like it came from that player's **client**, because
   that is how the proxy delivers it.
3. Therefore a **modified client can send arbitrary bytes on the channel**.

Point 3 is the reason for everything else. Any design that routes players based on the content of a plugin
message is a design where a client can ask to be sent anywhere.

### 6.2 Frame format

```
offset  size  field
------  ----  ---------------------------------------------------------
     0     4  magic 'O' 'D' 'N' 'T'
     4     1  protocol version (1)
     5     1  message type id
     6     4  payload length, big endian
    10   N*1  payload: nonce(16) | timestamp(8) | message fields
10+N   32*1   HMAC-SHA256 over bytes 0 .. 10+N-1
```

#### 6.2.1 The payload boundary — read this before touching the codec

**A Minecraft custom-payload field carries the frame verbatim.** Byte 0 of the payload is byte 0 of the frame:
no length prefix, no padding, nothing in front of it.

This is the contract, not a preference, and it is worth understanding *why* before changing anything near it:

* Velocity is a pure pass-through. `PluginMessagePacket.decode` is `readRetainedSlice(readableBytes())` and
  `encode` is `writeBytes(...)`, so the bytes a backend sends arrive in a proxy plugin's
  `PluginMessageEvent.getData()` unchanged, and the bytes a plugin passes to
  `ServerConnection.sendPluginMessage` end up in the backend's payload field unchanged.
* The payload field is **already length-delimited** by the packet that contains it. Adding another length is
  not merely redundant: it shifts every byte, so the receiving side reads the length as the first magic byte.

That second point is not hypothetical. An early revision of `BridgePayload` wrapped the frame in
`FriendlyByteBuf.writeByteArray` — a VarInt length prefix — while the proxy handed the field over as-is. Every
frame arrived as `[len][ODNT…]`, the proxy's magic check read the length instead of `'O'`, and **every transfer
was refused with `BAD_MAGIC`**, a message that points at the magic bytes and says nothing about framing. The
symptom was identical for both directions, because the same mismatch existed in both.

Two things now guard against a repeat:

1. `BridgePayload.CODEC` writes and reads the frame with no wrapper, and `BridgePayloadCodecTest` asserts
   against the real Minecraft buffer classes that byte 0 of the payload is `'O'`;
2. `BridgeCodec.describeFrame` renders a non-magic payload as **hex** and explicitly names a VarInt-prefixed
   frame as the cause, so the next occurrence is one log line instead of a client session.

`tools/verify-transport.ps1` proves the boundary end to end against Velocity's own packet class, without
needing a client. Run it after any change to the payload codec or the Velocity dependency.

Design notes:

* **The signature is verified before a single payload byte is interpreted**, so unauthenticated input never
  reaches a parser.
* **Timestamp plus nonce.** The timestamp bounds how long a captured frame is useful; the nonce makes every
  frame unique so a duplicate inside that window is caught by a bounded replay cache. Either alone is
  insufficient: a timestamp admits replays inside its tolerance, and a nonce alone would force the receiver to
  remember every nonce forever.
* **Fixed field order, no reflection, no JSON.** The same bytes are produced by every implementation, the format
  is trivially testable — the tests flip every single bit of a frame to prove tampering is caught — and there is
  no parser surface for a hostile payload to attack.
* **An explicit version.** A backend running an older build is rejected with
  `UNSUPPORTED_VERSION` rather than misread.

### 6.3 Message types

| Type | Direction | Purpose |
|---|---|---|
| `HELLO` | backend → proxy | "This is who I am: id, level name, seed, region, versions" |
| `HELLO_ACK` | proxy → backend | Verdict, with the reason, and the authoritative seed |
| `TRANSFER_REQUEST` | backend → proxy | "Player X left my region at these coordinates" — includes a destination *hint* |
| `TRANSFER_RESULT` | proxy → backend | Whether the move happened, and why not if it did not |
| `PLACE_PLAYER` | proxy → backend | "Put this arriving player at these global coordinates" |
| `TIME_REPORT` | backend → proxy | "My reading of world clock C right now" — only the configured authority's reading is trusted |
| `TIME_SET` | proxy → backend | "Set world clock C to this reading" — sent on hello, on arrival, and periodically |
| `TRANSFER_DIMENSION` | backend → proxy | "Player X is currently in dimension D" — sent right before the `TRANSFER_REQUEST` for the same move |
| `PLACE_DIMENSION` | proxy → backend | "Place arriving player X in dimension D" — sent right after the `PLACE_PLAYER` for the same transfer |
| `TRANSFER_PORTAL` | backend → proxy | "Player X went through a portal; here are the blocks around the far side" — sent right before the `TRANSFER_REQUEST` for the same move |
| `PLACE_PORTAL` | proxy → backend | "Recreate these blocks at this origin in this level" — sent right after the `PLACE_DIMENSION` for the same transfer |
| `ADVANCEMENTS_LOAD_REQUEST` | backend → proxy | "Send me the stored advancement progress of player X" — sent right after the `PLAYER_DATA_LOAD_REQUEST` for the same join |
| `ADVANCEMENTS_LOAD_RESPONSE` | proxy → backend | The raw bytes of the stored `advancements/<uuid>.json` (empty when none is stored) |
| `ADVANCEMENTS_SAVE_REQUEST` | backend → proxy | "Store these advancement bytes for player X" — compare-and-set on the revision, sent after every save |
| `ADVANCEMENTS_SAVE_RESULT` | proxy → backend | Whether the advancement payload was stored, and the new revision |
| `CHAT_RELAY` | backend → proxy | "Player X said this in chat" — plain text only, signatures are never forwarded |
| `CHAT_BROADCAST` | proxy → backend | "Show this chat line from player X locally" — sent to every backend except the source |

The two worlds are separate folders with the same seed, so terrain matches across a boundary but player-built
blocks do not. A nether portal whose linked far side falls in another region (possible because vanilla searches
a 128-block radius when linking portals, and nether coordinates are x/8) would therefore strand the player on
the destination with no portal there. `TRANSFER_PORTAL`/`PLACE_PORTAL` (ids 15–16) fix that: the source sends a
small box of non-air, non-liquid blocks around the arrival point, the destination recreates them in the arrival
level, and the arriving player gets the vanilla portal-travel cooldown so they are not immediately teleported
back through the recreated portal. Old builds refuse the unknown ids cleanly and the arrival is portal-less, as
before.

Advancements live in a per-player JSON file in each backend's world folder, not in the player data, so ids
17–20 carry them as their own payload: the proxy stores the raw bytes in a `player_advancements` table with its
own revision counter, under the same lease and with the same compare-and-set rule as snapshots. On join the
backend asks for the stored bytes (right after the player-data request on the same connection), writes them to
its own file, reloads from it, and sends the client a full advancements packet so the screen is correct
immediately; on every save (periodic, disconnect, flush before a transfer) the freshly written local file is
sent back. Chat (ids 21–22) is simpler: the sending backend relays the plain text of the message, and the proxy
broadcasts it to every other backend that currently has players, which displays it as an unsigned
`<name> message` line. Signatures never cross the bridge, and a broadcast is never relayed again, so each line
appears exactly once per server and can always be told apart from the sender's signed chat. Old builds refuse
all six ids cleanly (`UNKNOWN_MESSAGE_TYPE`) and advancements/chat simply stay local, as before.

`TRANSFER_REQUEST`/`PLACE_PLAYER` cannot carry a dimension field — their fields are frozen — so the dimension
travels in its own pair of messages (ids 13–14). The pair is sent on the same connection, in order, which is
what keeps a dimension from being attached to the wrong transfer. The coordinates are global and shared, so
the arrival is a cross-level teleport to the *same* coordinates: no nether/overworld scaling. An old build on
either side refuses the unknown id cleanly (`UNKNOWN_MESSAGE_TYPE`) and the transfer then behaves exactly as it
did before dimensions were synchronised.

### 6.4 The transfer protocol, step by step

```
  backend W                     proxy                         backend E
     │                            │                               │
     │ player walks past max-x    │                               │
     ├─────────►│   TRANSFER_DIMENSION               │
     │   (player, e.g. nether)    │                               │
     ├─────────►│   TRANSFER_REQUEST                 │
     │   (HMAC, hint "east")      │ authenticate                  │
     │                            │ check source is a backend     │
     │                            │ re-derive owner from ITS table│
     │                            │ attach noted dimension        │
     │                            │ check E verified itself       │
     │                            │ record pending transfer       │
     │                            ├────────► connection request │
     │◄ TRANSFER_RESULT ok ──────┤                               │
     │                            │                               │ player connects
     │                            ├──────────────► PLACE_PLAYER│
     │                            │   (global x, y, z, yaw, pitch)│ teleport next tick
     │                            ├────────────► PLACE_DIMENSION│
     │                            │   (player, dimension)         │ cross-level teleport next tick
     │                            │                               │ grace period starts
```

Why the placement arrives *after* the connect: Velocity's `ServerConnection.sendPluginMessage` needs an
established connection. There is no server-to-server channel. `ServerPostConnectEvent` is therefore the earliest
moment the coordinates can be delivered — and the destination backend holds them until it applies them, so an
instruction that arrives a tick early is not lost.

### 6.4.1 The bootstrap problem, and why an unknown backend is admitted provisionally

A backend announces itself with a `HELLO`, and the only channel a Fabric mod has is a player's connection.
Velocity offers no server-to-server plugin message path. Put those two facts together and you get a rule that
is easy to miss when designing the verification:

> A backend that has never had a player **cannot** have announced itself.

So "only transfer to a backend that has sent a valid HELLO" cannot be the whole rule: the first transfer to
every backend would be refused, because the transfer is the very thing that would give that backend its first
player. The system would deadlock on itself, and the symptom is confusing — the refused backend is running
perfectly and simply has nothing to announce itself through.

The proxy therefore has three admission states rather than a yes/no answer:

| State | When | What happens |
|---|---|---|
| `ADMITTED` | The backend verified, or `proxy.require-backend-hello` is `false` | The player is moved and their arrival coordinates are applied immediately |
| `PROVISIONAL` | Nothing has been heard from the backend | The player is moved — which makes the backend announce itself — but the arrival coordinates are **withheld** until it verifies. If it verifies, the placement is applied; if it does not within `proxy.arrival-verification-timeout-seconds`, the player is **returned to the server they came from** |
| `REFUSED` | The backend announced itself and did **not** match: wrong world, seed or region | No player is ever moved onto it. This is the case the verification exists to catch |

The trade-off in the `PROVISIONAL` row is deliberate and worth stating: for a few hundred milliseconds the
player stands on a backend that has not yet been verified, and that backend may briefly load chunks around
them. The alternative — refusing — is a permanent deadlock, and a backend generates its own spawn chunks
regardless. The window is bounded, the check still happens, and a mismatch still results in the player being
taken off that backend and that backend being refused for every subsequent transfer.

Recovery for a `REFUSED` backend: fix it, then move a player to it explicitly (`/server <id> <player>` on the
proxy console) or restart the proxy, so it can announce itself again.

### 6.5 Anti-loop and failure handling

The classic failure mode of any multi-server boundary is oscillation: two servers each decide the other should
own the player, and the player ping-pongs. OpenDonut defends with layered rules, all configurable:

| Rule | Setting | What it prevents |
|---|---|---|
| A player must be `boundary-margin-blocks` outside before a transfer is requested | `boundary-margin-blocks` | A player standing exactly on an edge triggering every tick |
| One request at a time per player | internal | Duplicate requests while a transfer is in flight |
| A cooldown after any arrival or failure | `cooldown-seconds` | A failed transfer turning into a request storm |
| An arrival grace period after joining | `join-grace-ticks` | A placement being interpreted as a departure |
| An unanswered request is treated as failed | `max-pending-seconds` | A player being stuck in a pending state forever |
| A failed transfer pushes the player back | `on-transfer-failure: revert` | A player standing in another server's region, which is the state that corrupts worlds |

Cases explicitly handled: the destination being offline or unregistered, the player disconnecting mid-transfer,
the proxy rejecting a connect, the request timing out, a placement arriving for a player who never arrived, a
player logging in at coordinates owned by another backend (the join check catches it), and a coordinate that no
server owns at all (`transfer.unassigned-area-policy`).

### 6.6 What the protocol does not protect against

The HMAC authenticates the proxy to the backends and the backends to the proxy. It does not encrypt, and it does
not help if the secret leaks or a host is compromised. The secret is a credential: keep it out of version
control, out of chat, and out of shared backups. The CLI prints a short fingerprint so two machines can be
compared without anyone reading the secret aloud.

## 7. Threading and lifecycle

| Work | Thread | Consequence |
|---|---|---|
| Boundary checks | server thread (end of tick) | Per-player state is plain collections; no locking |
| Bridge receive | server thread (packet handling) | The same state can be touched safely from both |
| Proxy events | Velocity's event threads | State is concurrent maps of immutable records |
| Config load | whichever thread starts the component | The configuration is immutable, and the region index is published safely on first use |

Lifecycle: the payload types are registered at mod initialisation, before any player can connect; the
configuration is loaded when the server *starts*, not when the class loads, because the identity check needs the
listening port and the loaded world. If the configuration cannot be used, the mod stays loaded and inert and logs
the whole validation report. Deliberately inert: a broken routing file must not take a Minecraft server down,
and an inert OpenDonut is visible in the log and in `/opendonut`.

## 8. What is *not* synchronised

Velocity and Fabric do not synchronise player data, and OpenDonut does not pretend they do. When a player is
transferred today, the destination server loads **its own copy** of that player's data.

| Data | State | Consequence |
|---|---|---|
| Inventory, armour, ender chest | Not synchronised | A player arrives with whatever the destination's `world/playerdata/<uuid>.dat` holds |
| Health, hunger, experience, effects | Not synchronised | Same |
| Advancements, statistics | Not synchronised | Per-server |
| Persistent data (scoreboards, attachments) | Not synchronised | Per-server |
| Player position | **Synchronised** | Global coordinates are carried through the transfer and applied on arrival |
| Block modifications, containers, block entities | **Per-server world folders** | Matching seeds do not merge them; see below |
| Mobs and entities | Per-server | Not carried across a boundary |

**Matching seeds only make unexplored terrain identical.** They do not merge two servers' worlds: a block placed
on `west` does not exist on `east`, and with `reload`-style chunk copying out of scope there is currently no
mechanism that copies it. What OpenDonut does provide today is the *precondition* for solving this safely:
authoritative ownership of each region, so that a chunk is only ever written by the one server responsible for
it. Anything else — shared storage, chunk replication, a proxy-side player-data service — has to be built on top
of that guarantee, and is future work.

This is a bigger deal for players than for administrators, so say it out loud before promising a seamless world:
**0.1.0-dev routes players and preserves their coordinates. It does not yet preserve their belongings.**

A concrete design for closing that gap — a lease-based player-data service behind the proxy, reachable from the
backends over the bridge that already exists, with a database for durability — is written up in
[plan-player-data-sync.md](plan-player-data-sync.md). It is a proposal, not implemented; the reasoning there
(why shared world folders duplicate items, why the lease is the load-bearing part) applies to any attempt at
this problem, not just that design.

## 9. Version matrix

Every version below was verified against upstream metadata on 2026-11-17, not remembered.

| Component | Version | Evidence |
|---|---|---|
| Minecraft | 26.3 | `piston-meta` version manifest: `latest.release == 26.3`; the servers in this workspace run `26.3` |
| Fabric Loader | 0.20.1 | `meta.fabricmc.net/v2/versions/loader/26.3` returns loader 0.20.1 |
| Fabric Loom | 1.19.4 | GitHub release tag `1.19` (2026-10-23) and plugin markers `1.19.1` … `1.19.4` on `maven.fabricmc.net` |
| Gradle | 9.8.2 | The Gradle distribution the official `fabric-example-mod` `26.3` branch wraps; SHA-256 verified before use |
| Java | 25 | Minecraft 26.3's version JSON declares `javaVersion.majorVersion = 25`; Velocity 4.3.1 requires 25 |
| Fabric API | 0.166.2+26.3 | `maven.fabricmc.net` metadata contains `0.166.2+26.3`; the `fabric` repository's `26.3` branch pins `minecraft_version=26.3` |
| Velocity | 4.3.1 (build 45) | `fill.papermc.io/v3/projects/velocity/versions/4.3.1`; SHA-256 recorded in [installation.md](installation.md) |
| velocity-api | 4.3.1 | `repo.papermc.io` |
| FabricProxy-Lite | 2.12.0 | GitHub release notes state support for Minecraft 26.1, 26.1.1, 26.1.2, 26.2 and **26.3** |
| SnakeYAML | 2.7 | Maven Central |
| JUnit | 6.0.3 | Maven Central (JUnit BOM) |

### 9.1 The 26.x deobfuscation change

**Minecraft 26.x ships deobfuscated, and this changes how Fabric mods are built.**

Evidence, all checkable:

* The server jar contains real names — `net/minecraft/world/entity/animal/sheep/Sheep.class`,
  `net/minecraft/network/protocol/game/ServerboundMovePlayerPacket$PosRot.class`.
* Fabric's intermediary for 26.3 reports version `0.0.0` — there is nothing to map.
* Yarn stops at `1.21.11`; there is no Yarn for 26.x.
* Fabric's official `fabric-example-mod` `26.3` branch has **no `mappings` dependency at all**, and declares
  `minecraft_version=26.3`, `loader_version=0.20.1`, `loom_version=1.19-SNAPSHOT`,
  `fabric_api_version=0.166.2+26.3`.

Consequences for this project: `fabric-mod/build.gradle` has no `mappings` line, and the mod is written against
the names that are *in the jar*. Two of those names differ from what older documentation would suggest, and both
were confirmed with `javap` against the real 26.3 jar rather than assumed:

* the resource identifier class is **`net.minecraft.resources.Identifier`**, not `ResourceLocation`;
* the stream codec type is **`net.minecraft.network.codec.StreamCodec`**, and the custom payload interface is
  `net.minecraft.network.protocol.common.custom.CustomPacketPayload` with
  `CustomPacketPayload.createType(String)`.

Anyone porting OpenDonut to a future 26.x release should re-run the same `javap` checks against the new jar
before changing code. The evidence above is the method: read the jar, do not guess.

### 9.2 Before bumping a version

1. Check whether the Minecraft version is still deobfuscated (`intermediary` on the Fabric meta server).
2. If mappings reappear, add a `mappings` dependency to `fabric-mod/build.gradle` and re-verify every Minecraft
   type name used in `net.opendonut.fabric`.
3. Check that FabricLoader, Fabric API and Loom all list the new Minecraft version.
4. Check `FabricProxy-Lite`'s release notes for the new Minecraft version — forwarding is the part that is
   easy to forget and hard to debug.
5. Check Velocity's supported versions and its minimum Java version.
6. Update `docs/architecture.md` (this file) and `gradle.properties`, and add the evidence.

## 10. Deliberate non-goals for 0.1.0-dev

Listing these is part of the design, not an apology:

* **No fixed tile grid.** A grid would be trivial to implement and would force every network into the same shape.
  Rectangles plus a compression-based index cost about 300 lines and remove that constraint.
* **No shared world-folder access.** Two servers pointed at one directory via a network share is the classic
  way to corrupt a world. OpenDonut assigns ownership instead, and never opens a world folder.
* **No chunk replication.** It requires deciding who is authoritative for a chunk *and* an atomic copy protocol.
  Ownership is the prerequisite, and it is in place.
* **No client mod.** The client is unmodified on purpose: a network whose players must install something has a
  smaller audience, and the client cannot be trusted with routing anyway.
* **No auto-discovery or gossip between backends.** Backends do not talk to each other. Everything goes through
  the proxy, which is the only component with a complete view.
