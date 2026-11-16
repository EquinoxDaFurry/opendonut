# Installation

This document installs OpenDonut on a live network: the proxy, the backends, secure forwarding, the shared
seed, and the checks that prove each step worked.

Everything below assumes **Java 25 or newer**. Minecraft 26.3 requires it and Velocity 4.3.1 requires it as a
minimum, so there is no way to run this stack on Java 21:

```bash
java -version      # must report 25 or 26
```

If your `java` is older, point `JAVA_HOME` at the newer JDK before starting anything — Minecraft reads it, and
so does the Gradle wrapper.

---

## 0. Build the artifacts

```bash
git clone <your fork or checkout>
cd OpenDonut
./gradlew build          # Windows: gradlew.bat build
```

Artifacts:

| File | Install it as |
|---|---|
| `fabric-mod/build/libs/opendonut-fabric-0.1.0-dev.jar` | `mods/opendonut-fabric-0.1.0-dev.jar` on every backend |
| `velocity-plugin/build/libs/opendonut-velocity-0.1.0-dev.jar` | `plugins/opendonut-velocity-0.1.0-dev.jar` on the proxy |
| `common/build/libs/opendonut-tools-0.1.0-dev.jar` | Nowhere. It is the administration CLI — keep it on your workstation |
| `gradle.properties` | The version matrix. Every value in it is documented in [architecture.md](architecture.md#version-matrix) |

The mod jar contains the shared engine and SnakeYAML as nested jars, and the plugin jar is shaded with SnakeYAML
relocated, so neither needs anything else installed alongside it.

---

## 1. The proxy

### 1.0 Run it from a plain directory path

Velocity 4.3.1 fails to start when the directory it runs from contains **both a space and an `!`**, with a
misleading `ProviderNotFoundException: Provider "jar" not found` message and no hint about the cause. Pick a
plain path for the proxy — the backends are unaffected:

```bash
mkdir /srv/opendonut/velocity            # Linux
mkdir "%LOCALAPPDATA%\OpenDonut\velocity"  # Windows
```

`tools/install-to-proxy.ps1` refuses to configure a proxy in an affected directory. The measurements behind this
rule are in [troubleshooting.md](troubleshooting.md#velocity-fails-to-start-with-providernotfoundexception-provider-jar-not-found).

### 1.1 Download and verify Velocity

Velocity 4.3.1, build 45:

```bash
curl -LO https://fill-data.papermc.io/v1/objects/0844bcabdcdb4f5b875906fd4c3365bc1bb76918e42bc595ed88ad195258dd75/velocity-4.3.1-45.jar
echo "0844bcabdcdb4f5b875906fd4c3365bc1bb76918e42bc595ed88ad195258dd75  velocity-4.3.1-45.jar" | sha256sum -c
```

Start it once to generate `velocity.toml`, then stop it.

### 1.2 Configure secure forwarding

**Never run a proxy in front of backends with `online-mode=false` on the backends, and never use legacy
(BungeeCord-style) IP forwarding on a modern stack.** Both let anyone who can reach the backend port log in as
anybody. OpenDonut uses Velocity's modern forwarding, which is authenticated by a secret the proxy and the
backend share.

In `velocity.toml`:

```toml
player-info-forwarding-mode = "modern"

[servers]
    # The proxy's own names must match the `id` values in opendonut.yml.
    west = "127.0.0.1:25566"
    east = "127.0.0.1:25567"
try = ["west"]

[advanced]
    # Path of the forwarding secret Velocity generates on first start.
    forwarding-secret-file = "forwarding.secret"
```

`forwarding.secret` must be readable by the proxy only (`chmod 600 forwarding.secret`) and must never be
committed to version control.

The backends accept that forwarding through **FabricProxy-Lite**, which is the Fabric-side half of Velocity's
modern forwarding. It is not optional: a vanilla or unmodified Fabric server cannot complete Velocity's login
handshake, and without it players either cannot connect or connect with an unverified identity.

### 1.3 Install the plugin

```bash
mkdir -p plugins
cp opendonut-velocity-0.1.0-dev.jar plugins/
mkdir -p plugins/opendonut
cp opendonut.yml plugins/opendonut/opendonut.yml
cp -r secrets plugins/opendonut/           # the bridge secret created in step 3
```

The proxy reads `plugins/opendonut/opendonut.yml` and keeps `seed.properties` next to it.

---

## 2. Every backend

### 2.1 Mods

Fabric Loader **0.20.1**, Minecraft **26.3**. Into `mods/`:

| File | Version | Source | Verify |
|---|---|---|---|
| `fabric-api-0.166.2+26.3.jar` | 0.166.2+26.3 | Modrinth / CurseForge / `maven.fabricmc.net` | — |
| `FabricProxy-Lite-2.12.0.jar` | 2.12.0 | [GitHub release](https://github.com/OKTW-Network/FabricProxy-Lite/releases/tag/v2.12.0) | `sha256=dca0d05685afaa25d554372ad118d90b6b27f85ded93e6db0b85d822aa29342a` |
| `opendonut-fabric-0.1.0-dev.jar` | 0.1.0-dev | your build | — |

FabricProxy-Lite 2.12.0's release notes state support for Minecraft 26.1, 26.1.1, 26.1.2, 26.2 and **26.3**,
which is why that version is pinned here.

### 2.2 Configuration

```bash
mkdir -p config
cp opendonut.yml config/opendonut.yml
cp -r secrets config/                      # the same secret file as the proxy
```

Do **not** set `backend.server-id` unless auto-detection cannot work: the mod finds its own entry by matching
its listening port against the configured addresses. See
[configuration.md](configuration.md#backend).

### 2.3 `server.properties`

Five settings matter:

```properties
accepts-transfers=true
online-mode=true
server-port=25566
level-name=world
level-seed=-4823049214123456789
```

| Key | Value | Why |
|---|---|---|
| `accepts-transfers` | `true` | Without it the client refuses a transfer from the proxy. The mod checks this at startup, logs an error and **disables transfers** rather than letting players cross a boundary that cannot complete |
| `online-mode` | `true` | Leave authentication on. With FabricProxy-Lite, players authenticate against Mojang *through the proxy*, and the backend trusts the proxy's signed identity instead of re-authenticating |
| `server-port` | as configured | The port is how a backend identifies itself in the shared configuration |
| `level-name` | matches `world.name` | The backend checks this at startup and reports a mismatch as a fatal finding |
| `level-seed` | the resolved seed | See below — set it **before** the world is created |

FabricProxy-Lite has its own configuration file in the backend's `config/` directory (it is generated on first
run), into which the proxy's `forwarding.secret` value has to be pasted. Use its own documentation for the exact
key names and set the mode to Velocity's **modern** forwarding; do not use legacy/BungeeCord forwarding, which
lets any client claim any identity.

Also make sure the backend is not reachable from the internet. It should listen on loopback, or be firewalled
so that only the proxy can connect. A backend with `accepts-transfers=true` and insecure forwarding is an open
door.

### 2.4 Seeding the worlds

Run this once, on the machine that holds the configuration:

```bash
java -jar opendonut-tools-0.1.0-dev.jar seed opendonut.yml
```

```
world seed resolution
  configured   : random
  resolved     : -4823049214123456789
  source       : generated and persisted to seed.properties
  state file   : /srv/opendonut/seed.properties

Put this line in every backend's server.properties before its world is created:
  level-seed=-4823049214123456789
```

Copy `seed.properties` to the proxy's directory and to every backend's configuration directory, and put the
`level-seed` line into every `server.properties`. A backend that starts with a different seed logs an error and
disables transfers, because players would otherwise cross a boundary into terrain that does not match.

**A world that already exists keeps its terrain.** Changing `level-seed` afterwards does not regenerate
anything; see [troubleshooting.md](troubleshooting.md#changing-the-seed-deliberately).

### 2.5 Command line flags

Optional, useful in containers and development environments:

```bash
java -Dopendonut.config=/etc/opendonut/opendonut.yml -jar server.jar nogui
```

---

## 3. The bridge secret

Generate it once and copy the same file to the proxy and to every backend:

```bash
java -jar opendonut-tools-0.1.0-dev.jar secret secrets/bridge-secret.txt
```

```
bridge secret
  file        : secrets/bridge-secret.txt
  status      : created
  length      : 32 bytes
  fingerprint : 3f9c1a77   (compare this on the proxy and on every backend)
  value       : hidden; re-run with --show to print it
```

Every component logs the same fingerprint at startup. If two machines print different fingerprints they hold
different secrets, which is the most common cause of "transfers never happen". Copy the file over a secure
channel; never paste the secret into chat, an issue tracker or a backup that is shared.

Permissions: on Linux/macOS the file is written with `0600` and a warning is emitted if it is group- or
world-readable. On Windows there are no POSIX permissions, so make sure the directory is not shared.

---

## 4. Start-up order and verification

1. **Start the backends first.** They will report that the bridge is ready and that transfers are enabled or,
   if something is wrong, exactly what.
2. **Start the proxy.**
3. **Connect one player.** The bridge becomes usable when the first player joins, because plugin messages travel
   through a player's connection — a backend announces itself with a `HELLO` at that moment.

What a healthy start looks like.

Backend log:

```
OpenDonut: this server is 'west' because it listens on port 25566 (127.0.0.1:25566)
OpenDonut: bridge ready on channel opendonut:bridge with secret fingerprint 3f9c1a77 (...)
OpenDonut 0.1.0-dev — server 'west' owns X=[-100000,0) Z=[-100000,100000)
OpenDonut: world 'world' seed -4823049214123456789 on Minecraft 26.3, configuration .../config/opendonut.yml
OpenDonut: boundary checks are active; a player who leaves X=[-100000,0) Z=[-100000,100000) by 2 block(s)
           will be handed to the proxy
OpenDonut: the proxy accepted this backend as 'west' (seed -4823049214123456789, 0 transfer(s) in flight)
```

Proxy log:

```
OpenDonut: registered backend 'west' at 127.0.0.1:25566 with the proxy
OpenDonut: registered backend 'east' at 127.0.0.1:25567 with the proxy
OpenDonut 26.3 is active: 2 region(s) of the world border, seed -4823049214123456789, channel opendonut:bridge,
           secret fingerprint 3f9c1a77
OpenDonut: every backend must use the same seed. Put 'level-seed=-4823049214123456789' in each backend's
           server.properties before its world is created.
OpenDonut: backend 'west' verified (world, seed and region match the central configuration)
```

In game, as an operator:

```
/opendonut                  # this backend: region, seed, transfer counters, bridge counters
/opendonut route -1 0       # -> this server
/opendonut route 0 0        # -> server 'east'
```

On the proxy console:

```
/opendonut regions          # the routing table, with each backend's verification state
/opendonut backends         # what each backend reported about itself
/opendonut route 0 0
```

If the two sides answer `/opendonut route` differently, they are not loading the same document — that is what
this pair of commands exists to reveal.

---

## 5. Running the integration tests

The unit tests need nothing but the JDK:

```bash
./gradlew test                                   # 207 tests
./gradlew :common:toolsJar                       # the CLI
java -jar common/build/libs/opendonut-tools-0.1.0-dev.jar check config/opendonut.example.yml
```

The **integration** environment is described in [`../tests/README.md`](../tests/README.md): two Fabric servers, a
Velocity proxy, and a checklist of things to observe — including what a boundary crossing looks like in the
logs on both sides. It requires a Minecraft client, so it is a manual procedure rather than an automated suite.

Before trusting a network, run this sequence once:

1. `/opendonut route` on each backend and on the proxy for a point on every side of every boundary. All three
   answers must agree.
2. Walk from the middle of one region to the middle of the next. Watch the backend that loses the player log the
   request, the proxy log the move, and the destination log the placement.
3. Walk back. The reverse path must work identically — the protocol has no direction.
4. Stop one backend and walk into its region from another. The proxy must refuse the move, and the player must be
   pushed back (with the default `on-transfer-failure: revert`) rather than left standing in somebody else's
   region.
5. Restart the proxy while a player is online. The backend re-sends its `HELLO` on the next join, so the network
   heals without restarting the backends.

---

## 6. Uninstalling

Remove the mod jar, the plugin jar, and the `config/opendonut.yml` / `plugins/opendonut/` directories. Nothing
else is touched: OpenDonut never modifies a world folder, `server.properties` or `velocity.toml`. The
`accepts-transfers=true` and `server-port` settings you added are harmless on their own, and reverting
`accepts-transfers` is only necessary if you want to stop accepting transfers entirely.
