# Integration testing

The unit test suite (`./gradlew test`, 312 tests) proves the configuration engine, the region lookup, the
bridge protocol, and the player-data sync machinery: the snapshot envelope codec, the store contract (lease,
compare-and-set, history) run against both the in-memory and the SQLite stores including 16-thread races, the
proxy-side coordinator's flush/lease/CAS sequencing, the five new bridge messages' round trips, the
`player-data` config section, and the Fabric-side NBT strip/encode/decode against the real Minecraft classes.
The suite cannot prove that a player walking across a boundary arrives on the other server with their items,
because that needs a real client, a real proxy and two real Minecraft servers.

This directory holds the procedure for that, the tooling to set it up, and the results that have actually been
observed. **It is a manual procedure.** Nothing here is automated, and nothing here should be described as
passing unless somebody ran it and watched what happened.

```
tests/
├── README.md          this file: the procedure, the checklist, and observed results
├── results/           captured logs from runs, one file per session
└── runtime/           scratch space for downloads and a throwaway proxy (gitignored)
```

## What the test environment is

Three Java processes, all on the local machine. **The live stack runs from
`%LOCALAPPDATA%\OpenDonut`** (`Server1`, `Server2`, `velocity`, plus a `start.bat` that launches all three
with `C:\Java\jdk26`); the `Server1`/`Server2` directories in the workspace root are an earlier copy of the
same servers and are no longer used for live runs — install jars and config changes into the
`%LOCALAPPDATA%` tree, not the workspace one:

| Process | Address | Software |
|---|---|---|
| Proxy | `127.0.0.1:25565` | Velocity 4.3.1 build 45 + the OpenDonut plugin |
| Backend `west` | `127.0.0.1:25566` | Minecraft 26.3, Fabric Loader 0.20.1, Fabric API, FabricProxy-Lite 2.12.0, OpenDonut mod |
| Backend `east` | `127.0.0.1:25567` | Same |

The regions and the exact configuration used are in
[`examples/two-servers/opendonut.yml`](../examples/two-servers/opendonut.yml):

```
west  X = [-100000, 0)        east  X = [0, 100000)
both  Z = [-100000, 100000)   border 200000 x 200000 centred on the origin
```

Both backends must be generating the same world: same `level-name`, same `level-seed`, same world-generation
settings. The mod checks the first two at startup and **disables transfers** if they disagree, which is the
behaviour you want to see when something is wrong.

### Requirements

* Java 25 or newer for every process.
* A Minecraft 26.3 client.
* The proxy must run from a directory path that does not contain both a space and an `!` — see
  [troubleshooting.md](../docs/troubleshooting.md#velocity-fails-to-start-with-providernotfoundexception-provider-jar-not-found).
  This is a real limitation of Velocity 4.3.1, not of OpenDonut, and it is why the proxy runtime for this
  workspace cannot live inside the workspace.

### Setting it up

```powershell
# 1. Build
.\gradlew.bat build

# 2. A configuration with the seed your worlds were actually generated with
copy examples\two-servers\opendonut.yml config\opendonut.yml
java -jar common\build\libs\opendonut-tools-0.1.0-dev.jar check config\opendonut.yml
java -jar common\build\libs\opendonut-tools-0.1.0-dev.jar seed  config\opendonut.yml

# 3. The bridge secret (one file, copied to every process)
java -jar common\build\libs\opendonut-tools-0.1.0-dev.jar secret config\secrets\bridge-secret.txt

# 4. The proxy, in a plain directory
.\tools\install-to-proxy.ps1   -ProxyDirectory "$env:LOCALAPPDATA\OpenDonut\velocity"

# 5. The backends (backs up server.properties, never touches a world folder)
.\tools\install-to-backend.ps1 -ServerDirectory 'E:\servers\Server1'
.\tools\install-to-backend.ps1 -ServerDirectory 'E:\servers\Server2'
```

Then check by hand:

* `level-name` matches `world.name`, and `level-seed` matches the resolved seed, on every backend;
* `accepts-transfers=true` on every backend;
* FabricProxy-Lite's own config in `config/FabricProxyLite.toml` holds the proxy's `forwarding.secret` value;
* `velocity.toml` uses `player-info-forwarding-mode = "modern"`.

## The procedure

0. **Check the transport first**, with no client and no servers running:

   ```powershell
   .\tools\verify-transport.ps1
   ```

   This proves the payload boundary carries frames verbatim in both directions, using Velocity's own packet
   class. It is the check that was missing when every transfer failed with `BAD_MAGIC`; run it after any change
   to the payload codec or the Velocity version.
1. **Start the backends.** Then the proxy.
2. **Connect** with a 26.3 client to `127.0.0.1:25565`.
3. **Read the logs on both sides** before doing anything else: this is where a misconfiguration shows up, and
   the transfer path is much easier to debug when you know the bridge is already working.
4. **Walk the boundary** and watch the three logs.

### Checklist

Each item is something to observe, not something to assume.

| # | Do this | Expect |
|---|---|---|
| 1 | Backend startup | `this server is 'west' because it listens on port 25566`, `bridge ready ... secret fingerprint XXXX`, `boundary checks are active` |
| 2 | Proxy startup | `OpenDonut ... is active: 2 region(s) ... seed ... secret fingerprint XXXX` — **the same fingerprint** as the backends |
| 2a | `/opendonut border` on a backend, in game | The count of particles rises as you walk towards a boundary and falls to zero in the middle of the region; the wall itself should be visible as a grid of end rod particles one block thick |
| 3 | `/opendonut regions` on the proxy | Two regions, each `[verified]` or `[not yet announced — a transfer will verify it on arrival]`. The second is normal for a backend nobody has been sent to yet: a backend announces itself through a player's connection, so it cannot have announced itself before its first player |
| 4 | Compare `/opendonut route <x> <z>` on the proxy and on each backend, for a point on each side of the boundary | All three agree, per side |
| 5 | Walk east from the middle of `west` past `x = 0` | `west` logs `left region 'west' (past max-x, N block(s) outside) ... asked the proxy to move them`; the proxy logs `moving <player> from 'west' to 'east' at global (...) because past max-x`; `east` logs `placed <player> at global (...)` |
| 6 | Look at where the player actually is | Block coordinates within one block of where they crossed, on the other side |
| 7 | Walk back west past `x = -1` | The mirror image of item 5 |
| 8 | Walk along the boundary without crossing | **Nothing** in any log: no request, no teleport |
| 9 | Stop `east`, walk into its region from `west` | The proxy refuses (`could not move ... SERVER_DISCONNECTED`), and `west` moves the player back inside its own region |
| 10 | Restart the proxy while a player is online | The backend re-sends its `HELLO` on the next join; `/opendonut regions` shows `[verified]` again without restarting the backends |
| 11 | Check the destination's chunks | Terrain around the arrival point matches what the source server had, because both use the same seed |

Items 4, 5, 8 and 9 are the ones that matter most: they are the routing decision, the transfer itself, the
anti-oscillation margin, and the failure policy.

### Checklist: player-data sync (needs `player-data.enabled: true` on all three processes)

| # | Do this | Expect |
|---|---|---|
| 12 | Proxy startup with `player-data.enabled: true` | `player data sync ready — player-data coordinator: sqlite (...), ...` and `/opendonut playerdata` on the proxy prints the same line |
| 13 | Backend startup with `player-data.enabled: true` | `player data sync ready on this backend — ...` on both backends, and `/opendonut playerdata` on a backend prints the counters |
| 14 | Give the player items/armour/off-hand, eat, take damage, gain XP, apply an effect, then walk east past `x = 0` | `west` logs a save for the flush (`sent the data of ... (N bytes, base revision R, flush requested by the proxy ...)`), the proxy logs `stored the data of ... as revision R+1` then `lease for ... moved from 'west' to 'east'`, `east` logs `applied the stored data of ...` |
| 15 | On `east`, check the inventory, armour, off-hand, ender chest, health, hunger, XP level/progress, held slot and effects | All identical to what they were on `west`; no stack duplicated, none lost |
| 16 | Walk back west past `x = -1` | The mirror image: a save on `east`, the lease moving back to `west`, `west` applying the stored data |
| 17 | Re-check everything on `west` | Still identical |
| 18 | Disconnect entirely, reconnect through the proxy | The backend that receives the player applies the stored snapshot on join (the store, not the backend's local `world/players/<uuid>.dat`, is authoritative) |
| 19 | Stop `east` while the player is on it, then cross back | The lease expires after `lease-seconds` and `west` takes over (`taken over from 'east', whose lease expired` in the proxy log) — no permanent lockout |
| 20 | With `east` stopped, walk into its region from `west` | The proxy refuses the transfer (`could not move ... SERVER_DISCONNECTED`); with `player-data` on, the flush either commits first and the refusal is clean, or the flush times out and the transfer is refused — in both cases `west` keeps the player's exact state, nothing is duplicated |

### Checklist: world-time sync (needs `time.enabled: true` on all three processes)

| # | Do this | Expect |
|---|---|---|
| 21 | Proxy startup with `time.enabled: true` | `world time sync ready — time sync: authority (not resolved yet), ...` and, once a backend verifies, `authority 'west'` (or the configured id) |
| 22 | Backend startup with `time.enabled: true` | `world time sync ready on this backend — ...` on both backends |
| 23 | On `west` (the authority), run `/time set 18000`; wait ~35 s with no players crossing | The proxy logs an accepted `time report from 'west'`, and `east` logs `applied the proxy's time correction: set clock minecraft:overworld to ... because of 'periodic'`; `/time query daytime` on `east` matches `west` |
| 24 | Run `/time set 6000` on `east` only | Within one `sync-interval-seconds` it is corrected back to the authority's time (watch for the warning-level `applied the proxy's time correction` with a large delta) |
| 25 | Walk east past `x = 0` with the time deliberately different on the two sides | The proxy sends `TIME_SET ... transfer` right after `PLACE_PLAYER`; `east` logs the correction with reason `'transfer'`; the arriving player sees the authority's time (`/time query daytime` on `east`) |
| 26 | Restart `east` while nobody is on it, then connect a player to it | On the first join `east` verifies (`HELLO`), the proxy pushes `TIME_SET ... hello`, and `east`'s log shows the correction to the authority's current time |
| 27 | With `time.enabled: true` but the authority stopped, transfer a player | No crash and no refusal: the proxy's wall-clock estimate stands in (the logs show no new `time report from ...`), and the destination is set from the estimate |

### What a boundary crossing should look like

Backend `west`:

```
[INFO]: OpenDonut: alice left region 'west' (past max-x, 2 block(s) outside), which is owned by 'east'
        (127.0.0.1:25567); asked the proxy to move them
```

Proxy:

```
[INFO]: [opendonut]: OpenDonut: moving alice from 'west' to 'east' at global (2, 64, -1180) because past max-x
[INFO]: [opendonut]: OpenDonut: alice is now on 'east' (SUCCESS)
```

Backend `east`:

```
[INFO]: OpenDonut: placed alice at global (2, 64, -1180) after arriving from 'west'
```

If you see the first line and not the second, the proxy refused the transfer and said why. If you see the second
and not the third, the placement did not reach the destination — check the secret fingerprints and the proxy
log for a `refused a bridge frame`.

## Results observed so far

Recorded on 2026-11-17, Windows 11, Temurin 25.0.5, on the workspace's two existing backends. Captured logs are
in [`results/`](results).

| What was tested | Result |
|---|---|
| **Payload framing contract** (`tools/verify-transport.ps1`, Velocity's own packet class) | **Pass.** `backend -> proxy` and `proxy -> backend` are both byte-identical to the frame and are accepted; the length-prefixed framing that caused the `BAD_MAGIC` incident is rejected and named |
| **Payload codec at the Minecraft layer** (`BridgePayloadCodecTest`) | **Pass.** Byte 0 of the encoded payload is `'O'`; a frame survives the boundary unchanged; an oversized payload is refused rather than allocated |
| Mod loads on Minecraft 26.3 / Fabric Loader 0.20.1 | **Pass.** `opendonut 0.1.0-dev` with nested jars `net_opendonut_common` and `org_yaml_snakeyaml` |
| Mod identifies its own server without a per-machine file | **Pass.** `this server is 'west' because it listens on port 25566`, and `'east'` on port 25567 |
| Mod validates the configuration at startup | **Pass.** Coverage `100.000%`, 2 lookup buckets, both regions reported with their chunk counts |
| Mod checks the world seed against the configuration | **Pass**, including the failure path: with a mismatched seed it logged the exact remedy and **disabled transfers** instead of routing players into terrain that does not match |
| Mod's `accepts-transfers` check | **Pass.** Detected `accepts-transfers=false` before it was fixed |
| `/opendonut`, `/opendonut region`, `/opendonut route <x> <z>` in game | **Pass.** The two backends answered oppositely for the shared edge — `(0,0)` is "this server" on `east` and "server 'east' ... is moved there" on `west` — and both reported `(100000, 0)` as unowned |
| Proxy loads the plugin | **Pass.** `Loaded plugin opendonut 0.1.0-dev by OpenDonut contributors` |
| Proxy validates the same configuration and resolves the seed | **Pass.** `OpenDonut 26.3 is active: 2 region(s) ..., seed 8275902214569690041, channel opendonut:bridge, secret fingerprint 6488b4ac` |
| Proxy auto-registers the backends from the YAML | **Pass.** `regions: 2 configured, 2 registered with the proxy` |
| Secret fingerprints match across all three processes | **Pass.** `6488b4ac` on both backends and on the proxy |
| `/opendonut status`, `regions`, `seed`, `route <x> <z>` on the proxy | **Pass.** Routing answers match the backends'; the seed command printed the `level-seed=` line |
| `check`/`route` CLI output matches the running components' answers | **Pass** |
| **A player walking across the boundary, first attempt** | **Failed, diagnosed and fixed.** The client connected, Velocity placed the player on `west`, `west` detected the crossing and requested the transfer — and the proxy refused every frame with `BAD_MAGIC`. Cause: the Fabric payload codec wrapped the frame in a VarInt length prefix while Velocity passes the payload field through verbatim, so the magic bytes sat at offset 2. Fix and regression tests: see [architecture.md § 6.2.1](../docs/architecture.md#621-the-payload-boundary--read-this-before-touching-the-codec); evidence in `results/2026-11-17-BAD_MAGIC-incident-proxy.log`. |
| **A player walking across the boundary, after the framing fix** | **Partially verified, one blocker found.** `west` verified itself and the routing decision ran — the frames were parsed, so the framing fix worked. But the transfer was then refused with *"backend 'east' has not verified itself ... so it may not receive players"*, while `east` was running perfectly. See the next row. |
| **The first transfer to a backend that has never had a player** | **Failed, diagnosed and fixed.** A backend announces itself only through a player's connection, so requiring a verified `HELLO` before allowing a transfer deadlocked the first transfer to *every* backend. The proxy now has three admission states: verified → place immediately; unknown → admit provisionally and verify during the arrival (returning the player if it fails); announced-and-mismatched → refuse. Five tests cover the three states and the bootstrap rule. |
| Velocity booting from the workspace path | **Fail, diagnosed.** `ProviderNotFoundException: Provider "jar" not found`; the cause is a path containing both a space and `!`. Works from a plain path. See [troubleshooting.md](../docs/troubleshooting.md#velocity-fails-to-start-with-providernotfoundexception-provider-jar-not-found) |
| **Player-data sync, unit level** (2026-11-17) | **Pass, at the unit level only.** 312 tests green, including: the snapshot envelope codec (round trips, zip-bomb and lying-length refusal), the store contract against both the in-memory and SQLite stores (CAS saves, lease grant/refuse/renew/release/steal-after-expiry, bounded history, 16-thread races with exactly one winner), the coordinator's flush/lease/CAS sequencing, round trips for the five new bridge messages plus refusal of a >64 KiB frame and of a validly signed frame with a lying inner length, the `player-data` config section (defaults off, every key, unimplemented options rejected loudly), and the Fabric NBT strip/encode/decode against the real 26.3 classes. |
| **Player-data sync, live startup on the three-process stack** (2026-11-17, logs `results/2026-11-17-playerdata-phase1c-*.log`) | **Pass for startup.** All three processes boot with `player-data.enabled: true`: the proxy logs `player data sync ready — player-data coordinator: sqlite (playerdata.db) ...` and creates `plugins/opendonut/playerdata.db` with the `player_data` / `player_lease` / `player_data_history` tables (schema verified by opening the live file); both backends log `player data sync ready on this backend ... data version 5023, mod fingerprint edaa0b8a1366c4bb`. Two live bugs were found and fixed this way: the driver was invisible to the plugin class loader (`No suitable driver found` — fixed by loading the driver through the store's own class loader), and relocating `org.sqlite` broke the native library (`NoClassDefFoundError: org/sqlite/core/NativeDB` — fixed by shading the driver unrelocated; see docs/installation.md). |
| **Player-data sync, in-game transfer** (checklist 12–20) | **Not yet run.** No client has crossed the boundary with sync on. The bridge path is only exercised through a player connection, so this needs a human with a client: connect to `127.0.0.1:25565`, give yourself items/armour/XP, walk past `x = 0`, and watch the three logs for the flush → save → lease-move → apply sequence, then check the inventory/health/XP arrived intact, and walk back. |
| **First live attempt at a sync transfer, 2026-11-17 17:30** | **Failed, diagnosed.** The proxy (new plugin) sent `PLAYER_DATA_FLUSH_REQUEST` (type 10) but the running backend answered `UNKNOWN_MESSAGE_TYPE`, then the flush timed out and the transfer was refused (`the source did not save the player's data within 10 second(s)`). Cause: the *live* backends under `%LOCALAPPDATA%\OpenDonut` were still running the pre-Phase-1 jar in memory (jars are not reloaded) and their configs had no `player-data` section. Fixed by installing the new mod jar into both live backends, enabling `player-data` in both live configs (backups kept), and restarting all three from `%LOCALAPPDATA%\OpenDonut` — all three now log `player data sync ready` (logs `results/2026-11-17-playerdata-live-*.log`). |
| **World-time sync, unit level** (2026-11-17) | **Pass.** `TimeSyncCoordinatorTest` (auto/explicit authority resolution, only-the-authority reports trusted with warn-once, wrong-clock refusal, wall-clock estimate at 20 tps, rate and pause, snap-to-later-report even backwards, negative-elapsed clamp), `TimeSettingsTest` (defaults, normalisation, every range check), `TIME_REPORT`/`TIME_SET` codec round-trips in `BridgeCodecTest`, and the `time:` section in `ConfigLoaderTest` (defaults off, every key, unknown authority → `TIME_AUTHORITY_UNKNOWN` error, bad clock id, interval ranges, `sync-interval-seconds: 0` legal, unknown key policy). |
| **World-time sync, live startup** (2026-11-17 19:43, checklist 21–22, logs `results/2026-11-17-timesync-live-*.log`) | **Pass for startup.** New mod/plugin jars installed into `%LOCALAPPDATA%\OpenDonut`, `time:` section (enabled, `authority: west`) appended to all three live configs, stack restarted: the proxy logs `world time sync ready — time sync: authority 'west', ... no report received yet` and the config report `world time sync: authority west, clock minecraft:overworld, ...`; both backends log `world time sync ready on this backend — ...`. Player-data sync still ready on all three (fingerprint `6488b4ac` everywhere), so no regression. No player was connected yet, so no `TIME_REPORT`/`TIME_SET` frames have flowed — the in-game part (checklist 23–27) is still open. |
| **Appending a config section under PowerShell 5.1, first attempt** | **Failed, diagnosed and fixed.** `Get-Content -Raw` decodes with the system ANSI codepage (cp1252 here), so the live configs' UTF-8 em-dash line was re-encoded as mojibake containing C1 control bytes, and SnakeYAML rejected all three configs with `YAML_INVALID: special characters are not allowed` (OpenDonut went inactive on all three processes). Fixed by restoring the backups and re-appending with an explicit `[System.Text.Encoding]::UTF8` read + no-BOM UTF-8 write; all three configs then pass `check` and the em-dash bytes are preserved. Rule for this workspace: **never** read or write the live YAML with implicit encodings — always `[System.IO.File]::ReadAllText($p, [Text.Encoding]::UTF8)` / `WriteAllText(..., UTF8Encoding($false))`. |

### Honest summary

Everything up to the transfer itself has been verified on real servers: the mod loads and self-checks, the
plugin loads and routes, the two sides agree, the secret is symmetric, and both sides answer coordinate queries
correctly — including the failure paths, which were observed rather than reasoned about.

The transfer path itself (checklist items 5-11) has **not** been exercised with a client. The code is written
against the real APIs and reviewed, and the failure modes are handled and logged, but until somebody walks
across a boundary on a real client, treat it as untested. That is the first thing to do next. The same is true
for the player-data checklist (items 12-20): startup on all three processes is verified (see the table above),
but no player has crossed a boundary with sync enabled yet.

## Leftovers in this workspace

Two directories exist in this workspace that OpenDonut does not own and does not need. They are listed here
because they are easy to mistake for something the project requires, and because one of them holds a stale
credential.

| Path | What it is | Recommended action |
|---|---|---|
| `../Velocity/` (in the workspace root, outside this repository) | A Velocity installation assembled during an earlier attempt at this environment, before the path limitation below was understood. Its log shows it failing with the same `ProviderNotFoundException`, and its `velocity.toml` had player-info forwarding disabled at that point. It also carries its own `forwarding.secret` and bridge secret, which differ from the ones now in use everywhere else. | Delete it (`rmdir /s /q "..\Velocity"`), or keep it as a spare — but do not start it: it would advertise itself on `25565` with a different secret. |
| `OpenDonut/secrets/bridge-secret.txt` | A bridge secret generated during the same earlier attempt, with a relative path. It is gitignored, nothing references it, and its value differs from the one installed on the two backends and the proxy. | Delete it. A stale secret that nothing uses is only a hazard: copying it by mistake produces `BAD_SIGNATURE` rejections that look like an attack. |

The working proxy runtime lives at `%LOCALAPPDATA%\OpenDonut\velocity` — outside the workspace, because of the
path limitation above — and holds the same bridge secret as both backends (`config/secrets/bridge-secret.txt` in
this repository).

## Keeping the results honest

When you run the checklist:

1. Save the three logs to `results/` with a date and a description, for example
   `results/2026-11-17-boundary-crossing-west-to-east.log`.
2. Update the table above with what you observed, including what failed.
3. Do not record a check you did not perform. A short, accurate list is worth more than a complete-looking one —
   the purpose of this file is to tell the next person what is actually safe to rely on.
