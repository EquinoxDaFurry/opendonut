# Plan: synchronising world time across backends

**Status:** **Phase 1 implemented** (ids 11–12, authority push + proxy cache/estimate, `TIME_SET` on hello-accept,
on post-connect arrival, and periodic push; `time:` config section, default `enabled: false`). The wire protocol
is in `common/.../bridge`, the proxy decision logic in `common/.../timesync/TimeSyncCoordinator`, the backend
report/apply logic in `fabric-mod/.../WorldTimeSync`, covered by unit tests (`TimeSyncCoordinatorTest`,
`TimeSettingsTest`, `BridgeCodecTest`, `ConfigLoaderTest`). End-to-end verification with a real client crossing a
boundary is tracked in `tests/README.md`. The parts marked "Phase 2+" below remain unimplemented: the
query/response pull (ids 13–14), `rate`/`paused` propagation (carried on the wire but not applied), additional
clocks as a list, weather sync, and a `/opendonut time` proxy command.

This is a plan, written to be argued with. It states the problem precisely, lays out the designs that could solve
it — including the ones that should be rejected and why — and recommends one.

---

## 1. What a player experiences today

Every backend runs its own world and its own clock. In Minecraft 26.3 time is no longer a single `dayTime` long on
the level: each world **clock** (a registry entry, `Registries.WORLD_CLOCK`; vanilla defines exactly two,
`minecraft:overworld` and `minecraft:the_end` — the nether has no clock of its own) is an instance managed by the
server-wide `ServerClockManager`, a `SavedData` that each backend persists in **its own world folder**:

```java
ServerClockManager clocks = server.clockManager();              // also on ServerLevel
ClockInstance clock = clocks.getInstance(WorldClocks.OVERWORLD); // holder from the registry
clock.totalTicks();  // long — the value /time query reports
clock.rate();        // float, ticks per real tick
clock.isPaused();

clocks.setTotalTicks(holder, ticks);   // write; wrap like TimeCommand.wrapTime
clocks.setRate(holder, rate);
clocks.setPaused(holder, paused);
clocks.createFullSyncPacket();         // ClientboundSetTimePacket — broadcast to players after a change
```

Because each backend owns its own `ServerClockManager`, the clocks agree only by accident:

* They start equal (same seed does not even set time — each world's saved clock is independent) and advance at
  the same rate **while both servers are running**, so any difference is created by an event, not by drift.
* `/time set`, `/time add`, `/time rate`, `/time pause` run on one backend change only that backend. A player who
  crosses the boundary arrives at a world whose sun is in a different place — visibly wrong at dawn/dusk and
  confusing at any hour.
* A backend restart reloads **its own saved** clock, so a backend that was down while the others played is behind
  by exactly the downtime.
* Tick pausing ("Server empty for 60 seconds, pausing") and clock pausing make the divergence worse whenever the
  backends are not ticking in lockstep.

So the problem is not "clocks drift" — it is "**each backend is its own source of truth, and nothing ever reconciles
them**". A player crossing `x = 0` should experience one continuous world with one continuous time.

## 2. Constraints

* **The proxy is the only component that sees the whole network**, and it already has an authenticated bridge to
  every backend. But the bridge has one hard property that shapes this plan: **plugin messages are addressed to a
  player connection**. The proxy can only send to a backend through a live `ServerConnection` — a player currently
  on that backend, or the connection being established during a transfer. When a backend has no players, the proxy
  has no channel to it. (The backend's `HELLO` arrives on the first player's connection for exactly this reason.)
* **The bridge protocol is append-only.** Message ids 1–10 exist (`HELLO` … `PLAYER_DATA_FLUSH_REQUEST`). New
  messages get ids 11+; existing ids are never reused, reordered, or given new meanings, so an older build can
  refuse an unknown frame instead of misreading it.
* **Every frame is HMAC-authenticated** with the shared secret, a timestamp and a nonce, and role checks apply on
  top (the proxy refuses a `PLAYER_DATA_FLUSH_REQUEST` from a backend; the same pattern will apply to the new
  messages).
* **The transfer window is short** (~a second), and the time must be right the moment the player arrives.
* **Admin intent must survive.** If someone runs `/time set day` on the authority, the whole network should follow;
  if someone runs it on a non-authority, the next sync should correct it. The design must make "which server's
  `/time` matters" an explicit, configured choice — not an accident of who the player happens to be near.

## 3. The invariant

> **Exactly one backend is the time authority, and every other backend converges to the authority's clock —
> at the latest when a player arrives on it, and at best continuously.**

Time is world state, not player state, so unlike player data there is no lease to hand over per player: there is
one authority per clock, chosen by configuration, and a one-way flow of corrections.

## 4. Options

### Option A — Velocity owns the clock

The proxy keeps `totalTicks` itself, advances it at 20 ticks per real second, and pushes it to backends.

**Rejected as the primary mechanism.** The proxy has no game tick and no world; it cannot know that someone ran
`/time set` on a backend, cannot persist the clock across a proxy restart without its own store, and cannot
represent a paused or re-rated clock without mirroring the whole `ServerClockManager`. It also inverts the trust
model: today backends are the source of truth for world state and the proxy only routes. If the proxy ever owns
time, a proxy restart rewinds the world.

Kept only as a **fallback**: the proxy's *estimate* between reports advances by wall clock, which is Option B's
cache — see below.

### Option B — Authority backend reports, proxy caches and pushes (recommended)

One backend is designated the **time authority** (`time.authority: west`, or `auto` = the first backend whose
`HELLO` the proxy accepts). The flow is push-based, because of the per-player channel constraint:

1. **Report.** The authority sends `TIME_REPORT` (new id 11, backend → proxy: clock key, `totalTicks`, `rate`,
   `paused`) right after its `HELLO_ACK`, and then every `time.report-interval-seconds` (default 5 s) through any
   live player connection. If the authority has no players connected, it simply cannot report — which is fine,
   because the proxy's estimate is still good (below).
2. **Cache + estimate.** The proxy stores `{clockKey, totalTicks, rate, paused, wallClockMillis}` per report and
   estimates the current time as `totalTicks + elapsedMillis/50 * rate` (no advance while `paused`). A cached
   value plus wall-clock extrapolation is exact as long as nobody changes the clock without the proxy seeing it —
   and the only way to change it without the proxy seeing it is running `/time` on the authority while it has no
   players, in which case the next report corrects the estimate. The authority going down entirely is harmless:
   the estimate keeps advancing at the last known rate.
3. **Push on arrival (the transfer-time sync).** In `ServerPostConnectEvent`, right where the proxy already sends
   `PLACE_PLAYER` through the new connection, it also sends `TIME_SET` (new id 12, proxy → backend: clock key,
   `totalTicks`, plus a reason string for logs). The destination applies it on the server thread —
   `setTotalTicks(holder, ticks)` — and broadcasts `createFullSyncPacket()` to its players. The arriving player
   therefore lands in a world whose clock matches the authority, and the join packets carry the same time.
4. **Periodic convergence.** Every `time.sync-interval-seconds` (default 30 s) the proxy sends `TIME_SET` with the
   current estimate to every **non-authority** backend it has a live connection for, so backends converge even when
   nobody crosses a boundary. Backends with no players are skipped; they are corrected on the next arrival (step 3)
   or, if a player joins them later, the first report-free path still applies because step 3 fires per transfer.
5. **Immediate correction on registration.** When the proxy accepts a backend's `HELLO`, it immediately pushes the
   current estimate to it over the same connection the `HELLO` arrived on — a restarted backend is fixed the moment
   its first player connects.

### Option C — carry the time in the player snapshot

Add the clock reading to the player-data snapshot, captured at flush and applied on arrival.

**Rejected as the primary mechanism.** Time is not player state: two players arriving from two different backends
would carry two different times and the later arrival would win — last-writer-wins on a world clock is exactly the
bug this plan exists to remove, and it can move time *backwards*. It also does nothing for backends with no players.
It could be useful later for per-player time effects, but that is a different feature.

### Option D — pull from the authority at transfer time

The proxy asks the authority for its current clock during the transfer (`TIME_QUERY_REQUEST`/`RESPONSE`, ids 13/14)
and uses the fresh value.

**Rejected for Phase 1, kept as Phase 2.** The proxy usually has no connection to the authority precisely when it
needs it (the authority may be empty), and a per-player channel cannot be opened on demand. The 5-second push in
Option B already bounds staleness to one report interval plus network latency — under a second in practice. Adding
the pull later is compatible (new ids, no meaning changes) if fresher-than-5s is ever needed.

## 5. Recommended design, in detail

### 5.1 Wire protocol (append-only; ids 11–12)

| Id | Message | Direction | Fields |
|---|---|---|---|
| 11 | `TIME_REPORT` | backend → proxy | clock key (string, e.g. `minecraft:overworld`), `totalTicks` (long), `rate` (float), `paused` (bool) |
| 12 | `TIME_SET` | proxy → backend | clock key, `totalTicks`, `rate`, `paused`, `reason` (string: `transfer` / `periodic` / `hello`) |

* Frames stay tiny (< 100 bytes), far under the 64 KiB frame limit; the codec's bounded readers apply as usual.
* Role checks mirror the existing pattern: the proxy logs and ignores a `TIME_SET` arriving from a backend
  ("only the proxy may set time"), and the backend ignores a `TIME_REPORT` arriving from the proxy. HMAC + nonce +
  timestamp authentication is unchanged.
* `TIME_REPORT` from a backend that is **not** the configured authority is ignored (warn once per backend), so a
  misconfigured backend cannot move the network clock.
* Phase 1 syncs `totalTicks` only. `rate`/`paused` are carried in the messages (so the codec is forward-compatible
  within this protocol version) but the destination logs them and applies them only in Phase 2 — changing meaning
  later requires new ids, so Phase 1 deliberately does not act on them yet.

### 5.2 Proxy side

* New `TimeSyncCoordinator` in `common` (plain Java, unit-testable without a proxy), holding: the resolved
  authority id, the last accepted report + wall-clock, and the estimate function. API:
  `resolveAuthority(config, verifiedBackends)`, `onReport(serverId, report, nowMillis)`, `estimateAt(nowMillis)`,
  `isAuthority(serverId)`.
* `OpenDonutVelocityPlugin` wires it: on `HELLO` accepted → push `TIME_SET(reason=hello)` over the same connection
  and, if the accepted backend is the authority, expect reports; on `TIME_REPORT` → coordinator; in
  `ServerPostConnectEvent` next to the existing `PLACE_PLAYER` send → `TIME_SET(reason=transfer)` with the current
  estimate; a scheduler task every second pushes `TIME_SET(reason=periodic)` to every live non-authority connection
  at most every `sync-interval-seconds`.
* Authority resolution: `time.authority: auto` → the first backend whose `HELLO` is accepted wins, for the lifetime
  of the proxy; an explicit id must name a configured server (validation error otherwise, like other config
  mistakes). If the authority is unreachable/empty, the estimate keeps advancing — no failover election is needed,
  because the estimate is derived from the last report and wall clock. If the authority later reports a value that
  differs from the estimate (someone ran `/time set` while it was offline), the estimate snaps to the report —
  the authority is always right.

### 5.3 Backend side

* New `WorldTimeSync` class in `fabric-mod`: handles `TIME_SET` (resolve the clock holder from
  `server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK)`, `setTotalTicks` with the same wrapping as
  `TimeCommand.wrapTime`, then broadcast `createFullSyncPacket()` to all players, and log the applied delta against
  the previous reading), and sends `TIME_REPORT` after `HELLO_ACK` and every report interval through any online
  player (skip silently when nobody is online). All of it runs on the Minecraft server thread, like `PlayerDataSync`.
* The arriving-player path needs no backend-side hook beyond `TIME_SET`: the proxy sends it on the same connection
  as `PLACE_PLAYER`, before the player finishes joining, and the destination's join packets already carry the
  corrected time.

### 5.4 Configuration

New `time:` section (parsed into a `TimeSettings` record in `common/.../model`, validated in the loader, default
**disabled**, unimplemented values fail loudly — same pattern as `player-data`):

```yaml
time:
  enabled: false            # opt-in, like player-data
  authority: auto           # auto | <server id, e.g. west>
  clock: minecraft:overworld
  report-interval-seconds: 5   # how often the authority reports to the proxy
  sync-interval-seconds: 30     # how often the proxy pushes to non-authority backends (0 = only on transfer/hello)
  log-divergence-ticks: 20      # warn when an applied TIME_SET jumps more than this
```

Validation errors: explicit `authority` not among `servers`, `clock` not a valid resource location, non-positive
intervals, `sync-interval-seconds: 0` allowed only with a comment... (no — keep it simple: 0 disables periodic
push, that is legal and documented).

### 5.5 Failure modes and edge cases

| Case | Behaviour |
|---|---|
| Authority has no players connected | It cannot report; the proxy's wall-clock estimate stands in (exact while rate is constant). Next report re-syncs. |
| Authority restarts | It reloads its own saved clock (possibly behind), reports on first player join, and the proxy snaps the estimate to the report — the authority is always right, by definition. |
| `/time set` run on a non-authority | Corrected by the next periodic `TIME_SET` (≤ `sync-interval-seconds`) or the next arrival. Documented: run `/time` on the authority. |
| Backend restarts / was down | Corrected immediately on `HELLO` accept (push) and on the next arrival. |
| Time wrap (24000) | `setTotalTicks` is applied with the same wrapping as `TimeCommand.wrapTime`; the estimate uses `Math.floorMod` semantics, never negative deltas. |
| Backwards jump (authority `/time set` earlier) | Intentional — the authority's reading wins, including backwards. Logged at `log-divergence-ticks`. |
| Two backends think they are the authority | Impossible from config alone (one `authority` value); reports from non-authorities are ignored with a warning. |
| Clock paused on authority | `paused` is carried in reports; the estimate does not advance while paused. Phase 2 will also propagate `rate`/`paused` to other backends. |
| Weather (rain/thunder) | Separate per-level state, **out of scope for Phase 1**. Phase 2 adds `WEATHER_REPORT`/`WEATHER_SET` as new ids. |
| Other clocks (`minecraft:the_end`) | Config `clock` names one clock; Phase 2 allows a list. |
| A player stands exactly on the boundary | Unaffected — time sync is independent of routing. |

## 6. Testing

* **Unit** (no Minecraft needed): `TimeSyncCoordinatorTest` — estimate math (elapsed × rate, paused, wrap via
  floorMod), authority resolution (`auto` = first verified, explicit id, explicit id not configured → validation
  error), non-authority reports ignored, snap-to-report on divergence. `BridgeCodecTest` additions: ids 11–12
  round-trip, oversize-frame refusal, signed-but-lying-length refusal (same guards as the player-data messages).
  `ConfigLoaderTest`: `time:` section cases — defaults disabled, unknown authority, bad clock id, zero/negative
  intervals, `sync-interval-seconds: 0`.
* **Fabric-side**: apply-path test with the real 26.3 classes (as `PlayerDataCaptureTest` does with real NBT):
  wrap behaviour, unknown clock key refused with a log line, holder resolution from a real registry access.
* **Integration** (checklist to add to `tests/README.md`): with `time.enabled: true` and `authority: west`,
  run `/time set 18000` on west only → within one `sync-interval-seconds` east's `/time query` matches; transfer a
  player east → west and back and check the time follows on arrival; `/time set` on east is reverted by the next
  sync; restart east and check it is corrected when its first player joins; watch logs for `reported time …` /
  `applied time … (was …)` and for no `UNKNOWN_MESSAGE_TYPE`.

## 7. Phases

| Phase | Contents |
|---|---|
| **Phase 1 (this plan)** | Ids 11–12, authority push + proxy cache/estimate, `TIME_SET` on hello-accept, on post-connect arrival, and periodic push; `time:` config section; overworld clock, `totalTicks` only; unit + integration tests as above |
| Phase 2 | `TIME_QUERY_REQUEST`/`RESPONSE` (ids 13–14) for a fresh pull during a transfer; propagate `rate`/`paused`; `clocks:` list (e.g. also `minecraft:the_end`); `/opendonut time status|sync` proxy command |
| Phase 3 | Weather sync (`WEATHER_REPORT`/`WEATHER_SET`, ids 15–16); per-dimension time if 26.3 ever grows per-dimension clocks |
