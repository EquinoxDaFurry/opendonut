# Plan: synchronising player data across backends

**Status:** design proposal, not implemented. Nothing in this document exists in the code yet.

This is a plan, written to be argued with. It states the problem precisely, lays out the designs that could
solve it — including the ones that should be rejected and why — and recommends one for a network whose backends
may be on different machines, different networks, with nothing in common except that they can reach the
Velocity proxy.

---

## 1. What a player experiences today

OpenDonut routes players and preserves their position. When a player is moved, the destination server loads
**its own copy** of that player's data — its own `world/players/<uuid>.dat`, or whatever its own world folder
contains. Concretely, on a transfer today:

| What the player keeps | What the player loses |
|---|---|
| Their coordinates | Their inventory, armour and off-hand |
| | Their ender chest |
| | Their XP, level and effects |
| | Their health and hunger |
| | Their advancements, statistics and recipes |
| | Anything a mod attached to their player object |

The player is moved to a server that has never seen their belongings and is handed whatever that server's
stored copy holds — usually nothing, because they have never played there before. That is the single largest
gap between what OpenDonut promises to become and what it does now, and it is the reason a "seamless world"
is not yet seamless in practice.

## 2. What "player data" actually is

The player's data is the NBT compound that a backend writes to `world/players/<uuid>.dat` when they leave. The
parts that matter for a transfer:

| Group | NBT keys (as of the current format) | Notes |
|---|---|---|
| Inventory | `Inventory` (36 slots + armour + off-hand) | Item stacks are NBT compounds; components make them large |
| Ender chest | `EnderItems` | Same shape as inventory |
| Progress | `XpLevel`, `XpP`, `XpTotal` | Trivially small |
| Vitals | `Health`, `foodLevel`, `foodSaturationLevel`, `foodExhaustionLevel`, `Air`, `Fire`, `active_effects`, `attributes` | Small, but `attributes` must be filtered: some are derived from the destination's state |
| Loadout | `SelectedItemSlot`, `equipment` | Small |
| Long-term | `advancements`, `stats`, `recipeBook`, `seenCredits` | Additive, mergeable |
| Identity/config | `abilities`, `gameType` | `gameType` should **not** be blindly applied across servers that run different modes |
| Position/dimension | `Pos`, `Rotation`, `Dimension`, `PortalCooldown` | OpenDonut already handles position; dimension must **not** be applied |
| Mod data | Fabric data-attachment payloads, mod-specific keys | Unknown shape; must travel as opaque bytes and may not be understood by the destination |

Two consequences follow immediately, and they shape the whole design:

1. **The data is not uniformly small.** A vanilla inventory of shulker boxes can be tens of kilobytes; a
   heavily modded player with several full shulkers and written books can exceed a megabyte.
2. **Not all of it should move.** A design that "syncs the whole player file" will also move position,
   dimension, and per-server state — which is exactly the class of bug that makes players log in inside a wall
   or lose their inventory twice.

## 3. Constraints

From the deployment this must work in — which is stricter than "same machine":

* **The backends may be on different machines, in different networks.** The only guaranteed path between them
  is that they can all reach the Velocity proxy. Nothing assumes a shared filesystem, a shared subnet, or even
  a shared administrative domain.
* **The proxy is the only component that sees the whole network.** It is already the routing authority and it
  already authenticates a bridge channel to every backend.
* **A database is acceptable**, but its reachability is a decision, not an assumption: putting it on the
  proxy's network means backends do not need to reach it; putting it somewhere shared means every backend needs
  network access and credentials.
* **Nothing may be lost or duplicated.** Item duplication is worse than item loss: it breaks the economy, and it
  is the failure mode every naive implementation produces.
* **The transfer window is short.** A player crosses a boundary and expects to arrive within a second or two;
  the data therefore has to be moved inside that window, or the transfer has to be held until it is safe.

## 4. The invariant

Everything below is in service of one rule:

> **At most one backend is authoritative for a player's data at any moment, and a transfer only completes after
> that authority has been handed over with the data intact.**

This is a lease, and it is the piece that separates a design that works from one that duplicates items. Two
backends that both believe they own a player's inventory will both save it, and the later save wins — which
means a player can withdraw an item on two servers and keep both copies.

The invariant has three corollaries:

1. A backend may only **write** a player's data while it holds the lease for that player.
2. A backend may only receive a player after the lease has been moved to it, and it must **read** the data
   before the player can interact with anything.
3. If the hand-over cannot be completed, the transfer does not happen. Refusing to move a player is a
   disappointing outcome; moving them and losing their inventory is a broken server.

---

## 5. The options

### Option A — share the world folder (`world/players` on NFS/SMB)

The path of least resistance: point every backend at one directory over a network share, so there is only one
copy of the file.

**Rejected.** It fails for reasons that are all fatal, in this order:

* Minecraft writes the player file with a plain `FileOutputStream` and no locking, and it writes it *on
  disconnect* — which is exactly when a transfer is happening. The destination can read the file before the
  source has finished writing it, and the classic duplicated-inventory bug follows.
* Network filesystem locking is famously unreliable; `flock` over NFS is a suggestion, not a guarantee.
* It requires every backend to mount the same volume, which contradicts "linked only via Velocity" — a VPN
  mount is a heavier dependency than a database and much harder to operate correctly.
* It syncs the *whole* player file, position and dimension included, so it also reintroduces the bugs §2 warns
  about.

It is worth naming because it is the first thing most networks try, and the failure is silent until players
notice they have two sets of gear.

### Option B — a shared database, written to directly by each backend (JDBC)

Every backend opens a JDBC connection to one database and reads/writes player snapshots itself. No proxy
involvement.

**Viable, and the simplest correct design** — if every backend can reach the database. It requires:

* the database to be reachable from every backend, which for machines in different networks means a VPN or a
  public endpoint with TLS;
* database credentials on every backend, each of which can therefore read and write *any* player's data;
* the lease logic to be implemented identically in every backend (it is a `SELECT … FOR UPDATE` or a
  conditional `UPDATE`, so this is manageable, but it is duplicated logic in the component that is hardest to
  update).

Choose this when the backends already share a private network and the operator would rather run one moving part
than two. Its weakness is that it makes the database internet-facing or VPN-bound, and it spreads authority
logic across every backend.

### Option C — a database behind the proxy, relayed over the existing bridge **(recommended)**

The proxy owns the only database connection and the only copy of the authority logic. Backends ask it for a
player's data and hand data back, using the authenticated channel that already exists.

* Backends need nothing but Velocity. No database credentials, no database reachability, no new firewall rule.
* The lease logic lives in exactly one place, next to the routing logic that already decides which server a
  player is on.
* The proxy already sequences transfers, so the hand-over fits into the flow it controls.
* The bridge is already HMAC-authenticated and replay-protected, so this adds no new trust surface.
* Cost: the proxy relays the data (up to a few megabytes per transfer), and it becomes a dependency for
  correctness rather than just for routing — which it already is.

### Option D — Redis/Valkey as the store

A key–value store with atomic operations and TTLs is a natural fit: the lease is `SET key value NX PX ttl`, and
the snapshot is a value or a hash. A Lua script gives atomic compare-and-set.

**Viable, and a good accelerator.** Two caveats: it has the same reachability question as Option B (so put it
behind the proxy and it becomes Option C with Redis), and durability depends on configuration — with `AOF
everysec` you can lose the last second of writes on a crash, which for player data is a real (if small) risk.
The strongest version is Redis in front of PostgreSQL: Redis absorbs the read/write traffic, PostgreSQL is the
durable record.

### Option E — an existing synchronisation plugin

**HuskSync** solves exactly this problem, with per-player locks, MySQL/Redis backends, snapshot versioning and
years of edge cases behind it. It is the reference implementation and worth reading.

**Rejected for direct use:** it is a Paper/Bukkit plugin and OpenDonut's backends are Fabric. There is no
equivalent for Fabric — which is both the opportunity (this is a real gap) and the warning (the edge cases are
numerous and well-trodden).

### Option F — do nothing, but be honest

Keep the status quo and make it visible: tell the player at the boundary that they are leaving *this* server's
storage behind. Zero risk, zero cost, and a hard ceiling on how good the network can feel. Worth keeping as the
documented default until one of the above is finished, which is what OpenDonut does today.

---

## 6. The recommended design (Option C) in detail

```
       ┌────────────────────────────────────────────────────────────┐
       │  Backend  A  (west)                                        │
       │   capture / apply player NBT  ──┐                          │
       └─────────────────────────────────┼──────────────────────────┘
                                         │  bridge (HMAC, existing channel)
                                         ▼
       ┌────────────────────────────────────────────────────────────┐
       │  Velocity  +  OpenDonut plugin                             │
       │   routing authority   +   player-data authority            │
       │        lease: who owns this player right now               │
       │        snapshot store, compare-and-set on revision         │
       └───────────────────────────────┬────────────────────────────┘
                                       │  JDBC (TLS), proxy's network only
                                       ▼
       ┌────────────────────────────────────────────────────────────┐
       │  PostgreSQL (or MariaDB / SQLite for one machine)           │
       └────────────────────────────────────────────────────────────┘
```

### 6.1 The two facts the schema must express

* **A lease**: one row per player saying which server may write their data, and until when. Expiry is what
  stops a crashed backend from locking a player out forever.
* **A snapshot with a revision**: the data itself, versioned so a write can be rejected when it is based on a
  version that has since changed (compare-and-set). Without this, two writers silently overwrite each other.

```sql
-- PostgreSQL flavour; the MySQL/MariaDB equivalent differs only in the bytes and timestamp types.
CREATE TABLE player_data (
    player_uuid      UUID        NOT NULL,
    profile          TEXT        NOT NULL DEFAULT 'default',  -- room for per-mode profiles later
    revision         BIGINT      NOT NULL DEFAULT 0,
    data_version     INTEGER     NOT NULL,          -- Minecraft's DataVersion of the writing server
    mod_fingerprint  TEXT        NOT NULL,          -- hash of the writer's mod set, for a mismatch warning
    payload          BYTEA       NOT NULL,          -- compressed NBT
    payload_bytes    INTEGER     NOT NULL,          -- uncompressed size, for accounting
    payload_sha256   BYTEA       NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       TEXT        NOT NULL,          -- server id that wrote it
    PRIMARY KEY (player_uuid, profile)
);

CREATE TABLE player_lease (
    player_uuid   UUID        PRIMARY KEY,
    owner_server  TEXT        NOT NULL,
    revision      BIGINT      NOT NULL,   -- the revision the owner is expected to write from
    acquired_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL
);

-- A history table is cheap and turns "my items vanished" into a question with an answer.
CREATE TABLE player_data_history (
    id            BIGSERIAL   PRIMARY KEY,
    player_uuid   UUID        NOT NULL,
    revision      BIGINT      NOT NULL,
    payload       BYTEA       NOT NULL,
    written_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    written_by    TEXT        NOT NULL
);
```

Acquiring a lease is one statement, so it cannot race with itself:

```sql
INSERT INTO player_lease (player_uuid, owner_server, revision, expires_at)
VALUES (:uuid, :server, :revision, now() + interval '60 seconds')
ON CONFLICT (player_uuid) DO UPDATE
   SET owner_server = EXCLUDED.owner_server,
       revision     = EXCLUDED.revision,
       acquired_at  = now(),
       expires_at   = EXCLUDED.expires_at
 WHERE player_lease.expires_at < now()          -- the previous owner is gone
    OR player_lease.owner_server = EXCLUDED.owner_server   -- or it is us, refreshing
RETURNING owner_server, revision;
```

No row returned means somebody else holds the lease and it has not expired — in which case the transfer is
refused, with the holder named in the log.

Writing is a compare-and-set on the revision:

```sql
UPDATE player_data
   SET revision = revision + 1, payload = :payload, payload_sha256 = :sha, ...
 WHERE player_uuid = :uuid AND revision = :baseRevision
RETURNING revision;
```

Zero rows updated means the snapshot moved under us: the writer's data is stale, and it must **not** be
applied. That is the moment a naive implementation duplicates items.

### 6.2 Wire protocol

New bridge messages, using the frame format and authentication that already exist. Chunking is required: a
plugin message carries at most 1 MB by default (`velocity.max-plugin-message-payload-size.clientbound`), and
OpenDonut's own frame cap is 64 KB, so a payload is compressed and then cut into chunks of at most 48 KB.

| Message | Direction | Purpose |
|---|---|---|
| `PLAYER_DATA_LOAD_REQUEST` | backend → proxy | "Player X is joining me; I hold the lease; send me their data" |
| `PLAYER_DATA_LOAD_CHUNK` | proxy → backend | One chunk: `{transferId, index, count, sha256, totalLength, compression, bytes}` |
| `PLAYER_DATA_LOAD_COMPLETE` | proxy → backend | `{transferId, revision, dataVersion, payloadSha256}` — or `{error}` |
| `PLAYER_DATA_SAVE` | backend → proxy | One chunk of an outgoing snapshot: `{transferId, index, count, baseRevision, bytes}` |
| `PLAYER_DATA_SAVE_ACK` | proxy → backend | `{transferId, accepted, newRevision, reason}` |
| `PLAYER_DATA_FLUSH_REQUEST` | proxy → backend | "Save player X now, the lease is about to move" |
| `PLAYER_LEASE_STATE` | proxy → backend | "You hold / do not hold the lease for X" — for diagnostics and for refusing writes |

Every chunk carries enough context to reject a partially applied transfer: a `transferId`, and a SHA-256 over
the whole payload so the receiving end can verify before applying. A chunk set that does not complete within a
timeout is discarded; nothing is applied from an incomplete transfer.

Compression is per-payload, not per-chunk, so the receiver decompresses once. `zstd` is the best choice (good
ratio, cheap), with `gzip` as the fallback when a backend's JVM does not have zstd — the codec lives in
`common`, so it is testable without a Minecraft server.

### 6.3 The transfer sequence, in the order that matters

This is the part that decides whether items get duplicated, so the ordering is the design:

```
 player crosses the boundary on A
   │
   1. A → proxy: TRANSFER_REQUEST (existing message)
   │
   2. proxy → A: PLAYER_DATA_FLUSH_REQUEST          <- the proxy sequences this, not the backend
   │
   3. A captures the player's NBT, compresses it, sends PLAYER_DATA_SAVE chunks with baseRevision = r
   │     the player is still on A, still playing, still holding their items — nothing has changed for them
   │
   4. proxy commits the snapshot with compare-and-set on r
   │     accepted  → revision becomes r+1
   │     conflict  → abort the transfer, log loudly, leave the player on A, and re-sync A from the store
   │     timeout / malformed → same as conflict
   │
   5. proxy moves the lease to B (same statement as §6.1), then issues the connection request
   │
   6. player arrives on B; B sends PLAYER_DATA_LOAD_REQUEST
   │
   7. proxy sends the snapshot, B verifies the SHA-256, applies the NBT, and forces a full inventory resync
   │     A no longer holds the lease: if it tries to save again, the write is refused
   │
   8. proxy → A: TRANSFER_RESULT (existing message)
```

Three properties fall out of this ordering, and each is deliberate:

* **The source saves before the player leaves.** The player is never in a state where nobody holds their data:
  from step 1 to step 5 they are still on A and A is still authoritative; from step 5 the snapshot is in the
  store and the lease is B's.
* **The data is committed before the lease moves.** A lease without a committed snapshot would let B read
  stale data and then write it back, silently reverting the player's last minute of play.
* **A failed flush refuses the transfer.** The player stays where they are, with everything they own. That is a
  worse experience for one crossing and a better one for the server's economy.

### 6.4 Applying the data on arrival

Capture and apply are Minecraft-side (`fabric-mod`) and are the only genuinely version-sensitive parts:

* **Capture** reads the fields listed in §2 from the `ServerPlayer` — `player.getInventory()`, `player.getEnderChestInventory()`, `player.experienceLevel`, `player.getHealth()`, `player.getFoodData()`, `player.getActiveEffects()`, and the player's `saveWithoutId`-style NBT for anything not exposed by an accessor. Writing them into a fresh `CompoundTag` and storing the writing server's `DataVersion` lets the reader refuse data it does not understand.
* **Apply** writes those fields back and then calls `player.connection.send(new ClientboundContainerSetContentPacket(...))` (or `player.inventoryMenu.broadcastFullState()`), because the client was sent an inventory during login and will otherwise keep showing the old one until the next change. Forgetting this step is the classic "the items are there but the player cannot see them" bug.
* **Filtering** is explicit: position, rotation, dimension, `gameType` and `abilities` modes are *not* applied. OpenDonut sets the position; the rest belong to the destination server's configuration.
* **Mismatches** are reported, not silently applied: a destination whose `mod_fingerprint` or world-generation settings differ from the writer's gets a warning naming the difference, because its client may not understand components the source produced.

### 6.5 Failure behaviour, one row per way it can go wrong

| Failure | What happens | Why |
|---|---|---|
| Database unreachable | The proxy refuses transfers that need a flush and logs it; players already on a server keep playing, and that server keeps its lease until it expires | Routing keeps working; nothing is silently lost |
| Save does not arrive in time | The transfer is refused, the player stays on the source | §6.3 |
| Compare-and-set conflict | As above, plus a re-sync of the source from the store | A stale writer must never win |
| Destination reads a snapshot it cannot parse (newer `DataVersion`, unknown mod data) | The destination refuses the load, the proxy refuses the transfer, the player stays on the source; the reason names the version | Better a refused transfer than an item-shredding migration |
| Backend crashes while holding the lease | The lease expires (60 s); the next backend to see the player steals it and logs the steal | A crash must not lock a player out |
| Two backends both think they hold the lease | Impossible by construction: acquisition is one statement, and every write is conditional on holding it | §4 |
| Proxy restarts | The store is the source of truth; leases expire and are re-acquired on the next join. The in-memory cache is discarded | The proxy is stateless between restarts, which is what makes restarting it safe |

### 6.6 Configuration surface

```yaml
player-data:
  enabled: false                  # off until an operator opts in
  storage: postgres               # postgres | mariadb | sqlite  (sqlite only for a single machine)
  jdbc-url: "jdbc:postgresql://127.0.0.1:5432/opendonut"
  username: opendonut
  secret-file: "secrets/player-data.txt"    # password or connection secret, never inline
  sync-interval-seconds: 60       # periodic autosave while a player is online; 0 disables
  lease-seconds: 60               # how long a lease survives a silent backend
  on-flush-failure: refuse-transfer         # refuse-transfer | proceed-with-last-known
  scope: session                  # session | progress | everything   (see §7)
  keep-history: 20                # snapshots kept per player for recovery
```

`on-flush-failure: proceed-with-last-known` exists because an operator may prefer movement over fidelity; it is
not the default, and it is documented as the setting that can lose a minute of play.

---

## 7. Scope: how much of the player to sync

Shipping all of it at once is how these projects die. The levels, in the order they are worth building:

| Level | Contents | Why this order |
|---|---|---|
| **`session`** (first) | Inventory, armour, off-hand, ender chest, XP, health, hunger, effects, selected slot | This is what a player notices within seconds of a transfer. Everything else can wait |
| **`progress`** | Advancements, statistics, recipe book | Additive and mergeable: take the union rather than the newer snapshot, so crossing a boundary never removes progress |
| **`everything`** | The remaining player NBT, including mod data, minus the filtered fields of §6.4 | Highest fidelity, highest risk: it moves data the destination may not understand |
| Entity carrying the player | Boat, horse, minecart, leashed pets | Needs entity transfer, which is a separate feature. For now: dismount on transfer, and say so in the message the player sees |

## 8. Testing the design

The parts that can be tested without a client, which is most of it:

* **Codec and chunking** (`common`): round trip a payload of 1 byte, 64 KB, 5 MB; verify chunk boundaries, the
  SHA-256 check, and that a truncated chunk set is rejected rather than partially applied. Pure Java, ordinary
  unit tests.
* **Lease and compare-and-set logic**: against an in-memory implementation of the store interface, with
  concurrency tests (two threads racing to acquire; a write from a non-holder; an expired lease being stolen).
  Then the same tests against a real PostgreSQL in a container, because SQL that looks right and deadlocks is a
  tradition.
* **Capture and apply** (`fabric-mod`): now that the module has a test source set, serialise a player's fields
  into NBT and back, asserting that the filtered fields are absent and that a `DataVersion` from the future is
  refused.
* **End-to-end without a client**: extend `tools/verify-transport.ps1` with a scripted scenario — two fake
  backends against the real proxy plugin and a real database, moving a snapshot back and forth, including the
  conflict and timeout paths. This is the same trick that made the `BAD_MAGIC` framing bug reproducible
  without a Minecraft client, and it works here for the same reason: the interesting logic is not in the client.
* **Failure injection**: stop the database mid-transfer; kill a backend holding a lease; corrupt a chunk;
  deliver chunks out of order. Each has a row in §6.5 that says what must happen, so each is a test.

## 9. Risks

| Risk | Why it matters | Mitigation |
|---|---|---|
| **Item duplication** | Breaks the economy and is unrecoverable | The lease plus compare-and-set plus refuse-on-failed-flush (§4, §6.3). This is the risk the design exists to eliminate, and it is the one to attack with tests first |
| **Item loss on a crash** | A player loses a session's work | Periodic autosave (`sync-interval-seconds`) bounds the loss; the history table allows recovery |
| Minecraft changes the player NBT format | Data written by one version applied by another | Store the `DataVersion`; refuse to apply data from a newer version; the history table makes a bad migration reversible |
| Modded data the destination cannot parse | Items with unknown components are dropped or crash the client | `mod_fingerprint` mismatch warning; `scope: session` as the default, which excludes mod-attached data |
| The database becomes a single point of failure | Transfers stop when it is down | Fail closed and keep routing: existing players play on, transfers are refused with a clear reason. This is already the shape of the system's failure behaviour |
| Privacy and retention | A database of player data needs a policy | Store only what is synced, document retention, support deletion on request (`/opendonut playerdata forget <player>`), and keep the DB on the operator's own hardware |
| Scope creep into a general "player cloud" | Turns a world system into a data platform | The scope table in §7 is the contract: each level ships only when the previous one is boring |

## 10. Phasing

| Phase | Work | Done when |
|---|---|---|
| **0. Capture and apply** (no storage) | NBT capture/apply in `fabric-mod`, plus `/opendonut playerdata dump` and `load` for an operator to move data by hand | A player's inventory survives a manual dump/load between two servers, verified with a client |
| **1. Store and lease, single machine** | `player-data` config, SQLite store, lease + revision, save on transfer, load on join, no chunking (small payloads fit one frame) | Two backends on one machine transfer a player with inventory intact, including the failure paths |
| **2. Multi-machine** | PostgreSQL/MariaDB, chunked transfer over the bridge, `mod_fingerprint` and `DataVersion` checks, history table, periodic autosave, admin commands | The two backends can be on different networks with only Velocity in common |
| **3. Progress and polish** | `scope: progress` merging, Redis cache, metrics, `/opendonut playerdata inspect` | Transfers are boring: nobody talks about them |
| **4. Research** | Entity transport (a player in a boat), so a crossing does not dismount them | Only after 1-3 are unremarkable |

Phase 1 is the smallest version that is genuinely useful and testable end to end, and it is where the design
risks are concentrated — the store can be swapped, the protocol can grow, but the lease and the ordering in
§6.3 are the parts that are hard to fix later.

---

## 11. Summary

* The problem is not "copy a file", it is "hand over authority without ever having two owners" — which is why
  the plan is built on a lease and a compare-and-set write rather than on file sharing.
* Of the options, the recommended one is a database behind the proxy, relayed over the existing authenticated
  bridge: it is the only one that works when the backends share nothing but Velocity, and it keeps the
  authority logic in the single component that already decides where players go.
* Everything described here is additive: a new optional `player-data` configuration section, new bridge
  messages, and capture/apply in the Fabric module. Nothing about routing, regions or the world seed changes,
  and with `player-data.enabled: false` the system behaves exactly as it does today.
