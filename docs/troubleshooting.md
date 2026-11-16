# Troubleshooting

Every entry here starts from what you actually see, and works towards the cause. The fastest general tool is the
command line validator — it answers the same questions the running components answer, using the same code:

```bash
java -jar opendonut-tools-0.1.0-dev.jar check  opendonut.yml
java -jar opendonut-tools-0.1.0-dev.jar report opendonut.yml
java -jar opendonut-tools-0.1.0-dev.jar route  opendonut.yml <x> <z>
```

---

## Velocity fails to start with `ProviderNotFoundException: Provider "jar" not found`

**Symptom:** the proxy prints `Booting up Velocity 4.3.1...`, then:

```
ERROR]: Exception in thread "main" java.nio.file.ProviderNotFoundException: Provider "jar" not found
    at java.base/java.nio.file.FileSystems.newFileSystem(FileSystems.java:342)
    at com.velocitypowered.proxy.util.ResourceUtils.visitResources(ResourceUtils.java:64)
    at com.velocitypowered.proxy.VelocityServer.registerTranslations(VelocityServer.java:351)
```

Nothing in that stack trace mentions the real cause, which is the **directory the proxy is run from**. Velocity
opens its own jar through a `jar:` URI, and that URI is built incorrectly when the path contains **both a space
and an exclamation mark** — the `!` that should separate the jar from the entry inside it ends up in the middle
of the path, and the lookup for the "jar" filesystem provider fails.

Measured on 2026-11-17 with Velocity 4.3.1 build 45 on Temurin 25.0.5, Windows 11:

| Proxy directory | Result |
|---|---|
| `C:\servers\velocity` | starts |
| `C:\Users\x\AppData\Local\Temp\opencode\od\vt space test` (space only) | starts |
| `C:\...\od\vt-bang!test` (`!` only) | starts |
| `C:\...\od\vt space and bang!` (both) | **fails** |
| `E:\!!Mature Projects\DonutSMP network\...\velocity` (both) | **fails** |

The fix is to run the proxy from a plain path:

```bash
# Windows
mkdir "%LOCALAPPDATA%\OpenDonut\velocity"
# Linux
mkdir -p /srv/opendonut/velocity
```

The backends are unaffected — Fabric Loader reads its jars with plain zip readers, not through a `jar:` URI — so
only the proxy has to move. `tools/install-to-proxy.ps1` refuses to configure a proxy in such a directory unless
`-Force` is passed, and suggests an alternative.

## The plugin never loads on the proxy

**Symptom:** no `OpenDonut` lines in the proxy log at all.

1. Is the jar in `plugins/`? Velocity lists every plugin it loaded at startup.
2. Is the proxy running **Java 25+**? Velocity 4.3.1 requires it and will refuse to start on older versions.
3. Does `velocity-plugin.json` exist in the jar? It is generated at build time by the velocity-api annotation
   processor:
   ```bash
   unzip -p opendonut-velocity-0.1.0-dev.jar velocity-plugin.json
   ```
4. Check for a `NoClassDefFoundError` naming `org.yaml.snakeyaml`. The plugin shades SnakeYAML and relocates it
   (`net.opendonut.libs.snakeyaml`), so this means the jar was rebuilt without the shadow task. Run
   `./gradlew :velocity-plugin:build` rather than `jar`.

## "OpenDonut is not active: the configuration ... cannot be used"

**Symptom:** the plugin or the mod logs `not active`, followed by findings.

The full validation report is printed. Read it top to bottom; errors are listed first. Two entries in that report
are worth explaining:

* `FILE_NOT_FOUND` — the file is not where the component looks. The proxy reads
  `plugins/opendonut/opendonut.yml`; a backend reads `config/opendonut.yml` inside the game directory (or
  `-Dopendonut.config`).
* `UNKNOWN_FIELD` with a suggestion — a typo, almost always. It is reported together with the
  `MISSING_FIELD` error for the value the typo replaced, which is what makes it obvious.

## `"This server is not active: this server listens on port 25566, which matches no address"`

The backend could not work out which entry of the configuration describes it. It matches its listening port
against the configured addresses.

* Check `server-port` in that backend's `server.properties` against the `address` entries.
* If two servers share a port (common in a container runtime that rewrites ports), set
  `backend.server-id` on the affected machines:
  ```yaml
  backend:
    server-id: west
  ```
  The validator already warns when a port is ambiguous, so `check` should have told you.

## "Transfers are DISABLED on this server"

The mod keeps running but refuses to transfer. Each reason is a separate line; all of them are fixable without
touching code:

| Log line contains | Meaning | Fix |
|---|---|---|
| `accepts-transfers=false` | The client would refuse the proxy's transfer | Set `accepts-transfers=true` and restart |
| `world.seed is ... but this world was generated with ...` | The two sides disagree about the seed | See [changing the seed](#changing-the-seed-deliberately) |
| `level-name is ... but the configuration says` | Level folder name mismatch | Align `level-name` and `world.name` |
| `bridge.enabled is false` | The bridge is switched off in the configuration | Set `bridge.enabled: true` |
| `could not be used` | The secret file is missing or too short | Regenerate with the `secret` CLI command and copy it everywhere |
| `has not verified itself` (on the proxy) | The backend's `HELLO` was rejected | See [a backend is rejected by the proxy](#a-backend-is-rejected-by-the-proxy) |

## A backend is rejected by the proxy

**Symptom:** on the proxy, `backend 'x' does NOT match the central configuration and will not receive players`,
and on the backend `the proxy refused this backend`.

Run `/opendonut backends` on the proxy — it prints what the backend reported and what was expected:

```
  west   X=[-100000,0) Z=[-100000,100000)   seed=-4823049214123456789  mc=26.3   verified
  east   X=[0,100000)   Z=[-100000,100000)  seed=12345                mc=26.3   REJECTED: its world seed
         is 12345 but the network resolved -4823049214123456789; terrain would not line up across the boundary
```

The causes, in order of frequency:

1. **Different `opendonut.yml` on that backend.** It must be the same document as the proxy's. A stale copy is the
   single most common cause of everything in this section.
2. **The proxy's name for the connection differs from the configured id.** If `velocity.toml` defines the server
   as `east_2` while `opendonut.yml` calls it `east`, the backend's own `server-id` cannot match. With
   `proxy.auto-register-backends: true` the plugin registers the names from the YAML itself, so this only happens
   when the name is already defined in `velocity.toml` with a different spelling.
3. **Seed mismatch.** See below.
4. **Wrong region.** The backend reports a rectangle the configuration does not give that id.

## `BAD_MAGIC`: the proxy refuses every frame from a backend

**Symptom:** the backend detects a boundary crossing and asks for a transfer, the proxy logs

```
OpenDonut: refused a bridge frame from backend 'west' claiming to be not an OpenDonut frame: first bytes
86 01 4f 44 4e 54 …; it looks like a VarInt length prefix (137 byte(s)) in front of a valid frame. …
(BAD_MAGIC)
```

and the backend gives up after `transfer.max-pending-seconds` and applies its failure policy.

**This is a framing problem, not a secret problem.** The secret is only checked *after* the magic bytes, so a
mismatched secret produces `BAD_SIGNATURE`, never `BAD_MAGIC`. A current build says so directly in the log:
if the payload "looks like a VarInt length prefix in front of a valid frame", the sender is wrapping the frame
in its own length.

The payload field is already length-delimited by the Minecraft packet, so the frame must be written verbatim —
byte 0 of the payload is byte 0 of the frame. Read
[architecture.md § 6.2.1](architecture.md#621-the-payload-boundary--read-this-before-touching-the-codec) for the
full reasoning. An early revision of the Fabric payload codec used `FriendlyByteBuf.writeByteArray`, which is
exactly this mistake, and it broke every transfer in both directions.

Diagnose it in one command, without needing a client:

```bash
./tools/verify-transport.ps1
```

It drives Velocity's own plugin-message packet in both directions and reports, per direction, whether the
payload the receiver sees is byte-identical to the frame. `RESULT: PASS` means the boundary is consistent and
the problem is elsewhere.

If you are running a backend and a proxy built from *different revisions*, rebuild both from one revision:
`./gradlew build`, then reinstall `fabric-mod/build/libs/opendonut-fabric-*.jar` and
`velocity-plugin/build/libs/opendonut-velocity-*.jar`. A mixed pair is the other way to get a payload the
receiver cannot parse, though it normally shows up as `MALFORMED_PAYLOAD` or `UNSUPPORTED_VERSION` rather than
`BAD_MAGIC`.

## Transfers never happen, and nothing is logged

The bridge needs a player to exist: plugin messages travel through a player's connection, so a backend cannot
announce itself to the proxy until somebody joins it. If no player is online, this is expected.

If a player *is* online and nothing happens:

1. Check the secret fingerprints. Both sides log one at startup, and `/opendonut` on the proxy prints it:
   ```
   OpenDonut: bridge ready on channel opendonut:bridge with secret fingerprint 3f9c1a77
   ```
   Different fingerprints on two machines mean different secrets.
2. Look for `refused a bridge frame ... BAD_SIGNATURE`. That is the same problem, reported when it matters.
3. Look for `STALE_TIMESTAMP`. Clocks disagree by more than `bridge.timestamp-tolerance-seconds`. Fix the clocks
   rather than raising the tolerance — a large tolerance widens the replay window.
4. Check that the player is actually outside their region. `/opendonut route <x> <z>` on the backend answers with
   **that backend's** table. If it says the coordinate belongs to the server the player is on, the backend is
   right not to ask for a transfer.

## A player is bounced back and forth

**Symptom:** the player is teleported repeatedly near a boundary, and the logs show repeated transfer requests.

This is the classic multi-server bug, and OpenDonut has several defences against it. In order:

1. **Are the regions adjacent or overlapping?** `report` catches overlaps. A one-block overlap creates a strip
   where each server thinks the other is wrong. Fix the bounds; they must share exactly one edge.
2. **Is `boundary-margin-blocks` too small for the connection quality?** With `0` or `1`, a player whose client
   predicts ahead of the server can oscillate across the line. `2`–`4` absorbs that.
3. **Is `cooldown-seconds` shorter than the time a transfer takes?** The cooldown is what stops a failed transfer
   from becoming a storm. With a slow backend, raise it.
4. **Is the destination actually accepting players?** If not, the default failure policy pushes the player back
   inside their own region, where the boundary check immediately fires again. The logs will show
   `the proxy could not move player ...`.

## "A player arrived in the wrong place"

Arrival uses the global coordinates the proxy supplied. If the position is wrong by a lot:

* Are the backends generating the same world? A seed mismatch is fatal and logged, but a **different
  world-generation setting** — a mod that changes terrain, a different `level-type`, a datapack that alters
  worldgen — produces the same symptom without a seed mismatch. Compare the two backends' mod lists and
  `server.properties` `level-type`, `generator-settings` and datapack folders.
* Is `world.name` the same? The mod checks it.

If the position is off by up to half a block, that is expected: the protocol carries **block** coordinates and a
player is placed at the centre of their arrival block. Their Y is used unchanged. This is a documented
simplification of 0.1.0-dev, not a bug.

## A player lost their inventory after a transfer

**This is expected in 0.1.0-dev, and it is not a bug to report.** Velocity and Fabric do not synchronise player
data, so a transferred player gets the destination server's copy of their `playerdata/<uuid>.dat`. See
[architecture.md](architecture.md#what-is-not-synchronised) for the full list of what is and is not carried
across a boundary today.

Workarounds used by operators today, all of which are outside OpenDonut's scope:

* Keep the transferred players' items in a proxy-side storage plugin, so every backend sees the same inventory.
* Restrict transfers to an admin-triggered command until player-data synchronisation exists.

## "The world border is not where I expect"

The border is defined as a centre and a size:

```
X = [center-x - size/2, center-x + size/2)
```

With `center-x: 0` and `size: 200000` that is `X = [-100000, 100000)`. Vanilla's `/worldborder center` and
`/worldborder set` use the same numbers, so the two agree if you keep the centre aligned to a chunk boundary. If
you have moved the vanilla border with commands, OpenDonut does not know about it: it only knows what the
configuration says, and the configuration is what the routing decisions use.

## Changing the seed deliberately

**A world that already exists keeps its terrain.** Minecraft saves chunks as it generates them; changing
`level-seed` does not regenerate anything that already has a region file. Setting a new seed only affects chunks
that have never been generated — which, on a fresh network, is all of them, and on a live network, almost none.

Two supported ways to change the seed:

**Option A — set it explicitly.** This is the recommended way, because the value becomes part of the
configuration that is already copied to every machine:

```yaml
world:
  seed: 1234567890
```

Then restart the proxy and every backend. A disagreement with the value in `seed.properties` is reported as
`SEED_STATE_MISMATCH`, which is a warning, not an error: the configuration wins.

**Option B — regenerate a random one.** Delete `seed.properties` and start the **proxy first**, so a single new
seed is generated and persisted, then copy the regenerated file to every backend before starting them:

```bash
rm seed.properties
# start the proxy -> generates and logs the new seed
# copy seed.properties and the new level-seed line to every backend
# then start the backends
```

If you start a backend before copying the file, that backend generates its *own* seed and writes it to its own
`seed.properties`. The two sides then disagree; the proxy's verdict on the backend's `HELLO` will say so, and
transfers stay disabled on that backend until it is fixed.

To actually get new terrain you have to recreate the world folders, which OpenDonut never does for you. If you do
it, do it with every server stopped, and remember that every player-built structure goes with it.

## A configuration change did not take effect

* **On the proxy:** `/opendonut reload` re-reads the file. If the new file has errors the plugin becomes
  **inactive** — that is deliberate, so a half-broken routing table is never used. Fix the file and reload again.
* **On a backend:** the configuration is read once, at server start. Restart the backend. This is deliberate:
  changing which region a running server owns while it is writing chunks there is exactly how worlds get
  corrupted. A future release will support a safe reload that verifies the new region still contains every online
  player.

## Reporting a problem

Include:

```bash
java -jar opendonut-tools-0.1.0-dev.jar report opendonut.yml     # config, routing table, coverage
```

plus the `OpenDonut` lines from the proxy log and from the backend logs, and the `/opendonut` output from both
sides. **Do not paste the bridge secret or `forwarding.secret`** — the fingerprints are enough to compare
machines, and that is what they are for.

If you are reporting a routing problem, the three answers to `/opendonut route <x> <z>` (two backends and the
proxy) are worth more than any description.
