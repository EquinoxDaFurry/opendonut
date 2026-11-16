# Configuration reference

One file describes the whole network. The proxy and every backend load the same document, so there is exactly
one place where a server is added, moved or renamed.

* Annotated example: [`config/opendonut.example.yml`](../config/opendonut.example.yml)
* Two-server example: [`examples/two-servers/opendonut.yml`](../examples/two-servers/opendonut.yml)
* Four-server example: [`examples/four-servers/opendonut.yml`](../examples/four-servers/opendonut.yml)

Validate before starting anything:

```bash
java -jar opendonut-tools-0.1.0-dev.jar check  opendonut.yml     # findings; exit 1 if unusable
java -jar opendonut-tools-0.1.0-dev.jar report opendonut.yml     # findings + routing table + coverage
java -jar opendonut-tools-0.1.0-dev.jar route  opendonut.yml -1 0
```

Where the file lives on each component:

| Component | Path | Override |
|---|---|---|
| Fabric backend | `config/opendonut.yml` inside the game directory | `-Dopendonut.config=/path/to/opendonut.yml` |
| Velocity proxy | `plugins/opendonut/opendonut.yml` inside the proxy's working directory | — |
| Command line tool | any path you pass | — |

The **proxy file is the copy that matters**: it is the routing authority, it resolves the world seed, and it
verifies every backend against it. Copy it to each backend rather than maintaining several documents.

---

## Top level

```yaml
config-version: 1
world: { ... }
validation: { ... }      # optional
bridge: { ... }          # optional
proxy: { ... }           # optional, proxy only
transfer: { ... }        # optional, backends only
backend: { ... }         # optional, backends only
servers: [ ... ]
```

| Key | Type | Required | Default | Meaning |
|---|---|---|---|---|
| `config-version` | integer | no | `1` | Schema revision. A document newer than the build understands is refused, rather than misread |
| `world` | mapping | **yes** | — | The shared world |
| `servers` | list | **yes** | — | The routing table; at least one entry |
| `validation` | mapping | no | see below | Validator behaviour |
| `bridge` | mapping | no | see below | The backend ↔ proxy channel |
| `proxy` | mapping | no | see below | Proxy-only behaviour |
| `transfer` | mapping | no | see below | Backend-only transfer behaviour |
| `backend` | mapping | no | see below | Which entry describes *this* machine |

Unrecognised keys are reported according to `validation.unknown-fields`. A misspelled key usually also produces a
"required value is missing" error for the value it replaced, and the two findings together make the typo obvious:

```
[WARN]  UNKNOWN_FIELD (servers[0].bounds.max_x:42) unknown key 'max_x' in servers[0].bounds; did you mean 'max-x'?
[ERROR] MISSING_FIELD (servers[0].bounds:39) required value 'max-x' is missing
```

---

## `world`

```yaml
world:
  name: "world"
  seed: random
  border:
    center-x: 0
    center-z: 0
    size: 200000
```

| Key | Type | Required | Default | Meaning |
|---|---|---|---|---|
| `name` | string | no | `world` | The Minecraft level folder name (`level-name` in `server.properties`). Every backend must use this name, and each backend checks it at startup |
| `seed` | number or `random` | no | `random` | The world seed; see [seeds](#seeds) |
| `border.center-x` | integer | no | `0` | Border centre on X |
| `border.center-z` | integer | no | `0` | Border centre on Z |
| `border.size` | integer | **yes** | — | Total edge length of the square border, in blocks |

### Border constraints

The border defines the outermost reachable area, and it is the area the coverage check measures against:

* `size` must be a **positive multiple of 16**;
* the centre must be a **multiple of 16**, so both edges fall on chunk boundaries.

If either rule is broken the validator reports `BORDER_NOT_CHUNK_ALIGNED` and explains that no server region
inside the border can then be aligned either — which is exactly what makes two servers share a chunk.

`size: 200000` with `center: 0` gives `X = [-100000, 100000)` and `Z = [-100000, 100000)` — 12 500 × 12 500
chunks, 40 000 000 000 blocks.

---

## `servers`

```yaml
servers:
  - id: west
    address: "127.0.0.1:25566"
    bounds:
      min-x: -100000
      max-x: 0
      min-z: -100000
      max-z: 100000
```

| Key | Type | Required | Meaning |
|---|---|---|---|
| `id` | string | **yes** | Unique identifier, 1–64 characters from `A–Z a–z 0–9 _ . -`, starting with a letter or digit. It is the Velocity server name and the routing key |
| `address` | string | **yes** | `host:port`; IPv6 literals need brackets: `[::1]:25566`. A missing port is an error rather than a silent default, because a wrong port looks like "server offline" |
| `bounds.min-x` | integer | **yes** | Inclusive minimum X |
| `bounds.max-x` | integer | **yes** | **Exclusive** maximum X |
| `bounds.min-z` | integer | **yes** | Inclusive minimum Z |
| `bounds.max-z` | integer | **yes** | **Exclusive** maximum Z |

### The coordinate rule

```
min-x <= x < max-x        min-z <= z < max-z
```

Minimum-inclusive, maximum-exclusive. This is not a detail — it is what makes a shared edge safe:

```
west  X = [-100000, 0)      owns block -1, not block 0
east  X = [      0, 100000) owns block 0, not block -1
```

With `west` ending at `0` and `east` starting at `0`, block `0` is east's and block `-1` is west's. No block is
claimed twice, and no block between them is left over. A region written as `max-x: 1` when it should be `0`
would produce an 16-block-wide strip claimed by both, which the validator reports as `REGION_OVERLAP` with the
shared area and an example rectangle.

### Chunk alignment

Every edge must be a multiple of 16. A boundary inside a chunk would force the two neighbouring servers to
generate and write the same chunk; the validator reports `BOUNDS_NOT_CHUNK_ALIGNED`, suggests the nearest aligned
rectangle, and — unless `validation.strict-chunk-alignment` is `false` — refuses to start.

### Negative coordinates

Coordinates are ordinary signed integers and are used as-is. What matters is how a *block* maps to a *chunk*,
which uses floor division:

```
block : ... -33 -32 | -17 -16 | -1  0 | 15 16 | 31 32 ...
chunk :     -3  -2  |  -2  -1  | -1  0 |  0  1 |  1  2
```

Chunk `-1` contains blocks `-16 … -1`; chunk `0` contains blocks `0 … 15`. The world origin therefore sits at the
*end* of chunk `-1`, not in the middle of anything. `>> 4` is used rather than `/ 16`, because `/ 16` truncates
towards zero and would put block `-1` in chunk `0`.

A worked example: the region `X = [-16, 16)` spans exactly chunks `-1` and `0` — 32 blocks, two chunks. Block
`-16` is inside it, because the minimum is inclusive. Block `15` is inside it. Block `16` is **not**, because
the maximum is exclusive: `16` is the first block of chunk `1`, and a neighbouring region starting at `16` owns
it. Checking each of these boundaries from both sides is one of the cases the test suite enumerates.

---

## `validation`

```yaml
validation:
  require-full-coverage: false
  strict-chunk-alignment: true
  unknown-fields: warn
```

| Key | Type | Default | Meaning |
|---|---|---|---|
| `require-full-coverage` | boolean | `false` | When `true`, any block inside the border owned by no server is a fatal error. When `false`, it is a warning that names the unassigned rectangles |
| `strict-chunk-alignment` | boolean | `true` | When `true`, a misaligned region or border is fatal. When `false`, it is a warning — useful only while diagnosing a legacy network |
| `unknown-fields` | `ignore` \| `warn` \| `error` | `warn` | What to do with unrecognised keys. `error` is recommended for production |

The defaults are strict about conditions that are always wrong (overlaps, inverted ranges) and permissive about
choices that are deliberate (unassigned wilderness while a network is being built out). OpenDonut never refuses
to start merely because a network is incomplete; it says precisely what is missing.

### What the validator checks

| Code | Severity | Condition |
|---|---|---|
| `FILE_NOT_FOUND`, `FILE_UNREADABLE`, `FILE_EMPTY` | error | The file is missing, unreadable, or contains no document |
| `YAML_INVALID`, `YAML_DUPLICATE_KEY` | error | Syntax error, or a key repeated in one mapping |
| `UNSUPPORTED_CONFIG_VERSION` | error | `config-version` is newer than the build understands |
| `MISSING_FIELD`, `INVALID_TYPE`, `INVALID_VALUE` | error | Required value absent, wrong YAML type, or an illegal value |
| `UNKNOWN_FIELD` | warning | A key that is not part of the schema, with a suggestion |
| `WORLD_NAME_INVALID`, `SEED_INVALID` | error | Blank world name, or a seed that is neither a number nor `random` |
| `BORDER_INVALID`, `BORDER_NOT_CHUNK_ALIGNED` | error | Non-positive or misaligned border |
| `NO_SERVERS` | error | The `servers` list is empty |
| `DUPLICATE_SERVER_ID` | error | Two servers share an id |
| `DUPLICATE_SERVER_ADDRESS` | error | Two servers share a socket (loopback spellings are treated as equal) |
| `SERVER_ID_INVALID`, `ADDRESS_INVALID` | error | Unusable id or address, with the parser's own explanation |
| `BOUNDS_INVALID` | error | Inverted bounds (`max < min`) or a region covering no block (`max == min`) |
| `BOUNDS_NOT_CHUNK_ALIGNED` | error¹ | An edge is not a multiple of 16 |
| `REGION_OUTSIDE_BORDER` | error | A region reaches outside the border, with the number of blocks outside on each axis |
| `REGION_OVERLAP` | error | Two regions claim a block; reports the shared area and an example rectangle |
| `WORLD_UNCOVERED` | warning² | Part of the border is unowned; reports the area, the percentage and a sample of the gaps |
| `BACKEND_IDENTITY_UNRESOLVED`, `BACKEND_IDENTITY_AMBIGUOUS` | error | `backend.server-id` names no server, or a port identifies several |
| `BRIDGE_SECRET_MISSING`, `BRIDGE_SECRET_INVALID` | error | The secret is absent, too short or unusable |
| `BRIDGE_SECRET_WORLD_READABLE` | warning | Other users on the machine can read the secret |
| `BRIDGE_DISABLED` | warning | `bridge.enabled: false`; transfers can never happen |
| `SEED_GENERATED` | info | A new random seed was generated and persisted |
| `SEED_STATE_MISMATCH` | warning | An explicit seed differs from the persisted one |
| `DERIVED_VALUE` | info | Coverage statistics, the derived border, an ineffective cooldown |

¹ error when `strict-chunk-alignment` is `true`, otherwise warning.
² error when `require-full-coverage` is `true`, otherwise warning.

Findings are ordered errors-first, then by source line, so "the first error" is stable for scripts.

---

## `bridge`

```yaml
bridge:
  enabled: true
  channel: "opendonut:bridge"
  secret-file: "secrets/bridge-secret.txt"
  timestamp-tolerance-seconds: 30
```

| Key | Type | Default | Meaning |
|---|---|---|---|
| `enabled` | boolean | `true` | Whether this instance participates in transfers at all |
| `channel` | string | `opendonut:bridge` | The plugin message channel, `namespace:path`. Change it only if it collides with another plugin — it must match on both sides |
| `secret-file` | string | `secrets/bridge-secret.txt` | The shared secret. Relative paths resolve against the directory holding this file |
| `timestamp-tolerance-seconds` | integer | `30` | Accepted clock skew, and the window the replay cache covers |

The secret authenticates every bridge message. It is generated on first run, must be **identical** on the proxy
and on every backend, and must never be committed or pasted into chat:

```bash
java -jar opendonut-tools-0.1.0-dev.jar secret secrets/bridge-secret.txt          # create or inspect
java -jar opendonut-tools-0.1.0-dev.jar secret secrets/bridge-secret.txt --show   # print the value to copy
```

Both components log a fingerprint on startup. If two machines print different fingerprints, they hold different
secrets — that is the single most common cause of "the backends never get transfers".

If the clocks on your machines disagree by more than the tolerance, transfers fail with `STALE_TIMESTAMP` and the
log says so. Fix the clocks (`chrony`, `w32tm`) rather than raising the tolerance; a large tolerance widens the
replay window.

---

## `proxy`

Ignored by the backends.

```yaml
proxy:
  auto-register-backends: true
  require-backend-hello: true
  arrival-verification-timeout-seconds: 5
  disconnect-unrouted-players: false
```

| Key | Type | Default | Meaning |
|---|---|---|---|
| `auto-register-backends` | boolean | `true` | Register every `servers` entry with the proxy at startup, so `velocity.toml` does not have to list them. A name `velocity.toml` already defines is left alone; the file wins |
| `require-backend-hello` | boolean | `true` | Verify every backend against this file before placing players on it. See the three admission states below |
| `arrival-verification-timeout-seconds` | 1–30 | `5` | How long the proxy may hold an arriving player on a backend that has not verified itself yet, before returning them to where they came from. Keep it shorter than `transfer.max-pending-seconds` (the validator says so if you do not) |
| `disconnect-unrouted-players` | boolean | `false` | Whether a player routed into an unowned coordinate is disconnected. `false` leaves them where they are, which is the gentler default |

### How a backend becomes trusted

A backend announces itself with a `HELLO`, and the only channel it has is a player's connection — Velocity has
no server-to-server plugin message path. So **a backend that has never had a player cannot have announced
itself**, and requiring an announcement before allowing a transfer would deadlock the first transfer to every
backend. `require-backend-hello: true` therefore has three outcomes rather than two:

| The backend… | Result |
|---|---|
| has announced itself and matches this file | **Admitted.** Players are moved and placed immediately |
| has never announced itself | **Admitted provisionally.** The player is moved, which makes the backend announce itself; the arrival coordinates are withheld until it verifies, and the player is returned to where they came from if it does not verify within `arrival-verification-timeout-seconds` |
| has announced itself and does **not** match | **Refused.** No player is moved onto it. Its own explanation is logged and returned to the requesting backend |

So "unannounced" is a normal state, not a problem to fix: `/opendonut regions` labels it
`[not yet announced — a transfer will verify it on arrival]`. A backend that was *refused* can be brought back
into service by fixing it and then moving a player to it explicitly (`/server <id> <player>` on the proxy
console) or restarting the proxy, so that it announces itself again.

Leaving `require-backend-hello` at `true` is strongly recommended: it is the check that notices a backend
generating a different world before a player walks into the seam. Setting it to `false` routes to any
registered backend without verification, which is only sensible when you are certain every backend is
generating the same world.

---

## `transfer`

Ignored by the proxy.

```yaml
transfer:
  boundary-margin-blocks: 2
  join-grace-ticks: 40
  cooldown-seconds: 5
  max-pending-seconds: 15
  on-transfer-failure: revert
  unassigned-area-policy: allow
```

| Key | Type | Default | Meaning |
|---|---|---|---|
| `boundary-margin-blocks` | 0–512 | `2` | How far outside its own region a player must be before a transfer is requested. Absorbs client/server position jitter on an edge |
| `join-grace-ticks` | 0–1200 | `40` | Ticks after joining during which boundary checks are skipped (20 ticks = 1 s) |
| `cooldown-seconds` | 0–3600 | `5` | Minimum time between transfer attempts for the same player |
| `max-pending-seconds` | 1–600 | `15` | How long an unanswered request may stay pending before it is treated as failed |
| `on-transfer-failure` | `revert` \| `log-only` | `revert` | `revert` moves the player back inside their own region, which is the safe default: a player standing in another server's region while connected here is what corrupts worlds |
| `unassigned-area-policy` | `allow` \| `return` | `allow` | What to do with a player in an unowned coordinate. `allow` leaves them and logs it once; `return` pushes them back inside the owning region of the server they are on |

The validator notes when `max-pending-seconds` exceeds `cooldown-seconds`, because then the pending timeout is
what actually controls retries and the cooldown value has no effect.

---

## `backend`

Backends only; the proxy ignores it.

```yaml
backend:
  server-id: west
  enabled: true
```

| Key | Type | Default | Meaning |
|---|---|---|---|
| `server-id` | string | unset | Which `servers` entry describes this machine |
| `enabled` | boolean | `true` | Whether this backend may transfer players at all |

**Leave `server-id` unset unless you must set it.** A backend finds itself by comparing its listening port with
the configured addresses, so adding or renaming a server never requires editing a per-machine file. Set it when
auto-detection cannot work:

* two backends share one port because of how a container runtime publishes them;
* the configuration names a public hostname while the server binds loopback and the ports collide;
* a spare backend is running that should stay out of the routing table — although `enabled: false` is the
  clearer way to express that.

The validator warns when two servers share a port, because that makes auto-detection ambiguous.

---

## Seeds

```yaml
world:
  seed: random      # or: seed: 1234567890
```

| Value | Behaviour |
|---|---|
| `random` (default) | The first component to start generates a seed and writes `seed.properties` next to the configuration. Every later start — and every other backend once the file is copied there — reuses it. **A new seed is never generated while that file exists** |
| a number | The configuration is authoritative. A disagreement with the stored value is reported as `SEED_STATE_MISMATCH`, because it usually means a backend was started before the seed changed and is now generating different terrain |

Accepted number forms: decimal, negative decimal, and hexadecimal with a `0x` prefix.

Resolve and print the value at any time:

```bash
java -jar opendonut-tools-0.1.0-dev.jar seed opendonut.yml
```

which prints the exact `level-seed=` line for every `server.properties`. Put it in place **before** the world is
created — see [installation.md](installation.md#seeding-the-worlds).

The proxy is the authority: it resolves the seed at startup, logs the line, and sends the value in every HELLO
acknowledgement. A backend whose world seed differs logs an error and disables transfers rather than letting
players walk into terrain that does not line up.

Changing the seed of an existing world does **not** regenerate its chunks. See
[troubleshooting.md](troubleshooting.md#changing-the-seed-deliberately).

---

## A minimal working configuration

```yaml
config-version: 1

world:
  name: "world"
  seed: 1234567890
  border:
    center-x: 0
    center-z: 0
    size: 32768

validation:
  require-full-coverage: true

servers:
  - id: west
    address: "127.0.0.1:25566"
    bounds: { min-x: -16384, max-x: 0, min-z: -16384, max-z: 16384 }

  - id: east
    address: "127.0.0.1:25567"
    bounds: { min-x: 0, max-x: 16384, min-z: -16384, max-z: 16384 }
```

A 32768 × 32768 border (1024 × 1024 chunks) split in half at `x = 0`, fully covered, no overlaps, both halves
aligned. `check` reports zero errors and zero warnings.
