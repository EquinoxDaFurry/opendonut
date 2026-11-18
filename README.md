# OpenDonut 🍩

**An open-source multi-server Minecraft world system based on DonutSMP's region system.**

OpenDonut lets multiple Minecraft servers share one continuous world. Players connect through a single Velocity proxy and can travel between servers by walking across region boundaries. When they cross into another server's region, they're transferred to the server responsible for those coordinates.

The goal is to recreate the region-based system used by DonutSMP in an open-source project that server owners can run and configure themselves.

## How it works

Each backend server is assigned a rectangular region of a shared coordinate space. The Velocity proxy handles routing players to the correct server based on their location.

For example, a network could have two servers split along the X axis:

```text
                       ┌─────────────────────────────┐
  player ────────────► │  Velocity  127.0.0.1:25565  │
                       │  + OpenDonut plugin         │
                       └──────────┬──────────┬───────┘
                                  │          │
                 X = [-100000,0)  │          │  X = [0,100000)
                                  ▼          ▼
                       ┌──────────────┐  ┌──────────────┐
                       │ Server1      │  │ Server2      │
                       │ Fabric 26.3  │  │ Fabric 26.3  │
                       │ + OpenDonut  │  │ + OpenDonut  │
                       │ Region: West │  │ Region: East │
                       └──────────────┘  └──────────────┘
```

Players don't need to manually switch servers. OpenDonut detects when they cross a region boundary and handles the transfer automatically, keeping their global coordinates consistent.

## Features

- **Custom region layouts:** Assign rectangular regions to servers with configurable X and Z boundaries. Regions can be arranged however your network needs, whether that's two servers, a 2×2 grid, or a much larger setup.
- **Central configuration:** All servers use the same YAML configuration file. Adding or changing regions doesn't require modifying any Java code.
- **Proxy-based routing:** The Velocity plugin makes all routing decisions. Backend servers can request transfers, but the proxy independently determines where the player should go.
- **Backend verification:** Servers verify their identity, assigned region, world name and world seed before accepting players.
- **Automatic transfers:** Players are transferred when they cross into another server's region.
- **Global coordinates:** Players arrive at the same coordinates they had before transferring.
- **Boundary particles:** Optional particle walls show players where their region ends.
- **Configuration validation:** A built-in CLI tool checks configurations for invalid bounds, overlapping regions, missing coverage, invalid addresses and other common issues.

## Current Status

OpenDonut is currently in **0.1.0-dev**. It's an early prototype, so not everything is finished or fully tested yet.

See [docs/architecture.md](docs/architecture.md) for more information about the current implementation and its limitations.

## Requirements

| Component | Version |
|---|---|
| Java | 25 |
| Minecraft | 26.3 |
| Fabric Loader | 0.20.1 |
| Velocity | 4.3.1 |
| Gradle | 9.8.2 (included wrapper) |
| Fabric API | 0.166.2+26.3 |
| FabricProxy-Lite | 2.12.0 |

All backend servers need Fabric, Fabric API, FabricProxy-Lite and the OpenDonut mod installed.

The Velocity proxy needs the OpenDonut plugin.

## Getting Started

### 1. Build the project

```bash
./gradlew build
```

On Windows:

```bat
gradlew.bat build
```

### 2. Validate your configuration

```bash
java -jar common/build/libs/opendonut-tools-0.1.0-dev.jar check config/opendonut.example.yml
```

### 3. Generate and configure the world seed

```bash
java -jar common/build/libs/opendonut-tools-0.1.0-dev.jar seed config/opendonut.example.yml
```

Make sure every backend uses the same seed in its `server.properties`.

### 4. Generate a bridge secret

```bash
java -jar common/build/libs/opendonut-tools-0.1.0-dev.jar secret secrets/bridge-secret.txt
```

Copy the generated secret to the Velocity proxy and every backend server.

### 5. Install OpenDonut

Follow the installation guide
