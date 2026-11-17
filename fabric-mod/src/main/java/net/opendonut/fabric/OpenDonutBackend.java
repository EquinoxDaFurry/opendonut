package net.opendonut.fabric;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;

import net.opendonut.common.bridge.BridgeMessage;
import net.opendonut.common.bridge.BridgeSecret;
import net.opendonut.common.config.ConfigLoadResult;
import net.opendonut.common.config.ConfigLoader;
import net.opendonut.common.config.SeedResolution;
import net.opendonut.common.config.SeedStore;
import net.opendonut.common.config.ValidationIssue;
import net.opendonut.common.model.OpenDonutConfig;
import net.opendonut.common.model.RegionBounds;
import net.opendonut.common.model.ServerDefinition;
import net.opendonut.common.model.TransferFailurePolicy;
import net.opendonut.common.model.UnassignedAreaPolicy;
import net.opendonut.common.region.RegionIndex;

/**
 * The running backend: ownership of one rectangular region of the global coordinate space, and the
 * machinery that keeps the right players on the right server.
 *
 * <h2>What this class guarantees</h2>
 * <ol>
 *   <li>A player standing in this server's region is left alone.</li>
 *   <li>A player who walks out of this server's region, by at least
 *       {@code transfer.boundary-margin-blocks}, produces exactly one authenticated transfer request.</li>
 *   <li>A request that is not answered in {@code transfer.max-pending-seconds} is treated as failed, and the
 *       failure policy (by default: move the player back inside their own region) is applied.</li>
 *   <li>A player arriving from another server is placed at the global coordinates the proxy supplied, and
 *       is then left alone for the arrival grace period so that the placement is not mistaken for a
 *       departure.</li>
 *   <li>Nothing is ever routed on the basis of what a <em>client</em> said. The destination is derived from
 *       the region table here, re-derived by the proxy from its own copy of the same file, and the
 *       client's opinion is never consulted.</li>
 * </ol>
 *
 * <h2>What this class deliberately does not do</h2>
 * <ul>
 *   <li><b>It does not move players between servers itself.</b> Only the proxy can do that. A backend
 *       cannot open a connection to another backend on a player's behalf, and it must not try: the proxy
 *       owns the player's session.</li>
 *   <li><b>It does not synchronise player data by itself.</b> Synchronisation happens through the proxy's
 *       store over the bridge (see {@link PlayerDataSync}), and only when {@code player-data.enabled} is
 *       true; with it false, a transfer gives the player the destination's own copy of their data, exactly
 *       as before.</li>
 *   <li><b>It does not teleport a player across a boundary by itself.</b> A player who is inside their own
 *       region is never touched, so terrain, Y position and view direction stay exactly as the client sees
 *       them until the proxy actually moves them.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Every method runs on the Minecraft server thread: boundary checks from the tick event, message handling
 * from the play-phase packet handler, and lifecycle hooks from the server events. The tracker therefore uses
 * plain collections and documents that assumption.
 */
public final class OpenDonutBackend implements BackendBridge.Listener {

	/** How long an arrival instruction stays valid, in milliseconds. */
	private static final long PLACEMENT_MAX_AGE_MILLIS = 30_000L;

	/** How long to wait between HELLO messages when multiple players connect in a burst. */
	private static final long HELLO_RESEND_INTERVAL_MILLIS = 5_000L;

	private final Logger logger;
	private final OpenDonutConfig config;
	private final ServerDefinition self;
	private final RegionIndex index;
	private final PlayerTransferTracker tracker = new PlayerTransferTracker();
	private final List<String> startupFindings = new ArrayList<>();

	private MinecraftServer server;
	private BackendBridge bridge;
	private PlayerDataSync playerDataSync;
	private WorldTimeSync worldTimeSync;
	private Optional<BorderParticles> borderParticles = Optional.empty();
	private Path configPath;
	private Path configDirectory;
	private boolean transfersEnabled;
	private boolean proxyAccepted;
	private long lastHelloMillis;
	private long worldSeed;
	private int transfersRequested;
	private int transfersRejected;

	private OpenDonutBackend(Logger logger, OpenDonutConfig config, ServerDefinition self) {
		this.logger = logger;
		this.config = config;
		this.self = self;
		this.index = config.regionIndex();
	}

	/**
	 * Loads the configuration and performs every startup check a backend can make on its own.
	 *
	 * <p>Returns empty only when there is nothing sensible to run: a configuration that does not parse, no
	 * usable identity, or a bridge that cannot be authenticated. Conditions that make transfers unsafe —
	 * a seed mismatch, {@code accepts-transfers=false}, a disabled bridge — keep the mod loaded but switch
	 * transfers off, and say so loudly at startup, because a half-working network is worse than a
	 * visibly-inert one.
	 *
	 * @param server the running server
	 * @param configDirectory the directory that holds {@code opendonut.yml}
	 * @param logger the server logger
	 * @return the backend, or empty when the mod must stay inert
	 */
	public static Optional<OpenDonutBackend> create(MinecraftServer server, Path configDirectory, Logger logger) {
		return create(server, configDirectory, logger, true);
	}

	/**
	 * Loads the configuration and performs every startup check a backend can make on its own, optionally
	 * quietly.
	 *
	 * <p>The {@code verbose} flag exists because this method is called twice: once while the server is starting,
	 * and again once it has started if the first attempt could not identify the backend. The first call reports
	 * everything in full; the second only speaks up if it succeeds or if there is something new to say, so a
	 * normal startup does not log the same validation report twice.
	 *
	 * @param server the running server
	 * @param configDirectory the directory that holds {@code opendonut.yml}
	 * @param logger the server logger
	 * @param verbose whether to log the full validation report and every failure
	 * @return the backend, or empty when the mod must stay inert
	 */
	public static Optional<OpenDonutBackend> create(
			MinecraftServer server, Path configDirectory, Logger logger, boolean verbose) {

		Path configPath = configPathFor(configDirectory);
		ConfigLoadResult loaded = ConfigLoader.load(configPath);

		if (!loaded.isUsable()) {
			if (verbose) {
				logger.error("OpenDonut is not active: the configuration at {} cannot be used.\n{}",
						configPath, loaded.result().formatWithSummary());
			} else {
				logger.error("OpenDonut is not active: the configuration at {} still cannot be used ({}).",
						configPath, loaded.result().summary());
			}
			return Optional.empty();
		}
		OpenDonutConfig config = loaded.config().orElseThrow();

		if (verbose) {
			// Report anything the administrator should see even though it is not fatal.
			for (ValidationIssue issue : loaded.result().issues()) {
				switch (issue.severity()) {
					case ERROR -> logger.error("OpenDonut configuration: {}", issue.format());
					case WARNING -> logger.warn("OpenDonut configuration: {}", issue.format());
					case INFO -> logger.info("OpenDonut configuration: {}", issue.format());
				}
			}
		}

		Optional<ServerDefinition> identity = resolveIdentity(server, config, logger, verbose);
		if (identity.isEmpty()) {
			return Optional.empty();
		}

		OpenDonutBackend backend = new OpenDonutBackend(logger, config, identity.get());
		backend.server = server;
		backend.configPath = configPath;
		backend.configDirectory = configDirectory;
		backend.setUpBridge(server, configDirectory, configPath);
		backend.playerDataSync = PlayerDataSync.create(server, config, backend.bridge, identity.get().id(), logger)
				.orElse(null);
		backend.worldTimeSync = WorldTimeSync.create(server, config, backend.bridge, logger).orElse(null);
		backend.borderParticles = BorderParticles.create(config.borderParticles(), logger);
		// The full "who am I and where am I" summary is logged by completeStartup, once the world is known.
		return Optional.of(backend);
	}

	/**
	 * Finishes startup now that the world exists.
	 *
	 * <p>Deliberately separate from {@link #create}: the configuration, the identity and the bridge secret are
	 * all available before the world is, while the level name, the world seed and {@code accepts-transfers} are
	 * only readable once the server has loaded its levels. Checking the world too early produced a crash rather
	 * than a finding, which is exactly the kind of startup bug that makes an operator distrust a plugin.
	 *
	 * @param server the started server
	 * @param configDirectory the directory holding the configuration and the seed state
	 */
	public void completeStartup(MinecraftServer server, Path configDirectory) {
		this.server = server;
		this.configDirectory = configDirectory;
		checkWorld(server, configDirectory);
		logStartupSummary(configPath);
	}

	/**
	 * Chooses the configuration file to load.
	 *
	 * <p>{@code -Dopendonut.config=/path/to/opendonut.yml} wins, which is how a development environment or a
	 * container can point at a mounted file. Otherwise {@code config/opendonut.yml} inside the game
	 * directory is used, which is where the documentation tells administrators to put it.
	 *
	 * @param configDirectory the game's config directory
	 * @return the file to load
	 */
	public static Path configPathFor(Path configDirectory) {
		String override = System.getProperty("opendonut.config");
		if (override != null && !override.isBlank()) {
			return Path.of(override);
		}
		return configDirectory.resolve("opendonut.yml");
	}

	/**
	 * Works out which entry of the central configuration describes this server.
	 *
	 * <p>By default the backend does not need to be told: the listening port is compared against the
	 * configured addresses, so adding a server never requires editing a per-machine file. An explicit
	 * {@code backend.server-id} overrides that, and is the answer when a port is ambiguous.
	 *
	 * @param server the running server
	 * @param config the configuration
	 * @param logger the logger
	 * @return the entry describing this backend, or empty when it cannot be determined
	 */
	private static Optional<ServerDefinition> resolveIdentity(
			MinecraftServer server, OpenDonutConfig config, Logger logger, boolean verbose) {

		Optional<String> explicit = config.backend().serverId();
		if (explicit.isPresent()) {
			Optional<ServerDefinition> found = config.server(explicit.get());
			if (found.isEmpty()) {
				logger.error("OpenDonut is not active: backend.server-id is '{}' but the configuration defines "
						+ "these ids: {}. Fix backend.server-id or add the missing entry.",
						explicit.get(), ids(config));
				return Optional.empty();
			}
			logger.info("OpenDonut: this server is '{}' because backend.server-id says so", explicit.get());
			return found;
		}

		int configuredPort = configuredPort(server);
		List<ServerDefinition> candidates = config.serversOnPort(configuredPort);
		if (candidates.size() == 1) {
			logger.info("OpenDonut: this server is '{}' because it listens on port {} ({})",
					candidates.get(0).id(), configuredPort, candidates.get(0).address());
			return Optional.of(candidates.get(0));
		}

		if (candidates.isEmpty()) {
			// A proxy or container runtime can publish a different port from the one the server binds, so the
			// bound port is worth trying before giving up.
			int boundPort = server.getPort();
			if (boundPort > 0 && boundPort != configuredPort) {
				List<ServerDefinition> byBoundPort = config.serversOnPort(boundPort);
				if (byBoundPort.size() == 1) {
					logger.info("OpenDonut: this server is '{}' because it is bound to port {} ({})",
							byBoundPort.get(0).id(), boundPort, byBoundPort.get(0).address());
					return Optional.of(byBoundPort.get(0));
				}
			}
			String message = "this server's port is " + configuredPort + " (configured) / " + boundPort
					+ " (bound), which matches no address in " + config.sourceName()
					+ ". Either the configuration's address for this backend is wrong, or this is an "
					+ "unconfigured server. Configured servers: " + ids(config);
			if (verbose) {
				logger.error("OpenDonut is not active: {}", message);
			} else {
				// The retry after the server finished starting reports the same thing, and there is no point
				// saying it twice.
				logger.debug("OpenDonut is still not active: {}", message);
			}
			return Optional.empty();
		}

		logger.error("OpenDonut is not active: port {} is used by more than one configured server ({}). "
						+ "Set backend.server-id on this machine to say which one it is.",
				configuredPort, candidates.stream().map(ServerDefinition::id).toList());
		return Optional.empty();
	}

	/**
	 * The port this server was configured to listen on.
	 *
	 * <p>Deliberately the value from {@code server.properties} rather than {@link MinecraftServer#getPort()},
	 * and deliberately not {@code DedicatedServer#getServerPort()} either — that method simply returns
	 * {@code getPort()}, which reports the <em>bound</em> port and is {@code -1} until the server socket is
	 * opened. This method is called while the server is starting, which is before that.
	 *
	 * <p>The configured port is also the value the central configuration's {@code address} entries describe, so
	 * it is the right thing to match against even when a proxy or container republishes a different one.
	 *
	 * @param server the running server
	 * @return the configured port, or {@code -1} when it cannot be determined
	 */
	private static int configuredPort(MinecraftServer server) {
		if (server instanceof DedicatedServer dedicated) {
			DedicatedServerProperties properties = dedicated.getProperties();
			if (properties != null && properties.serverPort > 0) {
				return properties.serverPort;
			}
		}
		return server.getPort();
	}

	/**
	 * Renders the configured server ids for an error message.
	 *
	 * @param config the configuration
	 * @return a comma separated list, or a placeholder
	 */
	private static String ids(OpenDonutConfig config) {
		if (config.servers().isEmpty()) {
			return "(none configured)";
		}
		return String.join(", ", config.servers().stream().map(ServerDefinition::id).toList());
	}

	/**
	 * Checks the assumptions the routing model rests on: same level name, same seed, transfers accepted.
	 *
	 * @param server the running server
	 * @param configDirectory the directory holding the configuration and the seed state
	 */
	private void checkWorld(MinecraftServer server, Path configDirectory) {
		worldSeed = server.overworld().getSeed();

		String levelName = server.getWorldData().getLevelName();
		if (!levelName.equals(config.world().name())) {
			startupFindings.add("the server's level-name is '" + levelName
					+ "' but the configuration says world.name is '" + config.world().name()
					+ "'. The routing model assumes every backend generates the same world, so global "
					+ "coordinates only line up when the level names match.");
		}

		// Resolve the configured seed. This also persists a generated one, so the file exists for the other
		// backends to copy; the proxy is the authority once it is running.
		SeedResolution seed = SeedStore.resolve(configDirectory, config.world().seed());
		for (ValidationIssue issue : seed.issues()) {
			// A seed-store disagreement is not by itself a problem for a backend: what matters is whether the
			// world it actually loaded matches, which is checked below. Reporting it at its own severity keeps
			// the message without disabling transfers.
			logAtSeverity("seed: " + issue.message(), issue);
		}
		if (seed.seed() != worldSeed) {
			startupFindings.add("this world was generated with seed " + worldSeed
					+ " but OpenDonut resolved " + seed.seed() + " (" + seed.detail()
					+ "). Terrain would not match across the boundary: players would walk into different "
					+ "landscapes. Set level-seed=" + seed.seed() + " in server.properties and delete this "
					+ "server's world folder, or set world.seed to " + worldSeed + " if the existing world is "
					+ "the one to keep. See docs/troubleshooting.md.");
		}

		if (server.isDedicatedServer() && server instanceof DedicatedServer dedicated
				&& !dedicated.acceptsTransfers()) {
			startupFindings.add("server.properties has accepts-transfers=false. The proxy cannot move a "
					+ "player onto this server until it is true. Set accepts-transfers=true and restart.");
		}
	}

	/**
	 * Logs an advisory finding at the severity it carries.
	 *
	 * <p>Used for findings that are worth knowing about but must not disable transfers: a secret that other
	 * users on the machine can read, a freshly generated seed, a seed store that disagrees with the
	 * configuration. Only conditions that make routing unsafe are collected as startup problems.
	 *
	 * @param message the message
	 * @param issue the finding it came from
	 */
	private void logAtSeverity(String message, ValidationIssue issue) {
		switch (issue.severity()) {
			case ERROR -> logger.error("OpenDonut: {}", message);
			case WARNING -> logger.warn("OpenDonut: {}", message);
			case INFO -> logger.info("OpenDonut: {}", message);
		}
	}

	/**
	 * Loads the shared secret and creates the bridge.
	 *
	 * @param server the running server
	 * @param configDirectory the directory the secret path is resolved against
	 * @param configPath the configuration file, for messages
	 */
	private void setUpBridge(MinecraftServer server, Path configDirectory, Path configPath) {
		if (!config.bridge().enabled()) {
			startupFindings.add("bridge.enabled is false, so this server will never transfer a player. "
					+ "The coordinate system cannot behave as one world without the bridge.");
			return;
		}

		// The payload type was registered during mod initialisation, before this configuration was loaded, so
		// the two can disagree if bridge.channel was edited between mod load and server start. They must not:
		// the proxy listens on the configured channel, and this backend would be speaking on a different one.
		String registeredChannel = BridgePayload.channelName();
		if (!registeredChannel.equals(config.bridge().channel())) {
			startupFindings.add("the bridge payload type was registered on channel '" + registeredChannel
					+ "' when the mod loaded, but the configuration now says '" + config.bridge().channel()
					+ "'. Fabric requires payload types to be registered before any player can connect, so "
					+ "bridge.channel can only be changed in a restart. Transfers are disabled until then.");
			return;
		}

		Path secretFile = Path.of(config.bridge().secretFile());
		if (!secretFile.isAbsolute()) {
			secretFile = configDirectory.resolve(secretFile);
		}

		byte[] secret;
		try {
			BridgeSecret.LoadResult loaded = BridgeSecret.loadOrCreate(secretFile);
			secret = loaded.secret();
			for (ValidationIssue issue : loaded.issues()) {
				// Permission advice and "a secret was created" are informational; only a genuinely unusable
				// secret stops transfers, and that is handled by the catch below.
				logAtSeverity(issue.message(), issue);
			}
		} catch (IOException e) {
			startupFindings.add("the bridge secret at " + secretFile + " could not be used: " + e.getMessage()
					+ ". Generate one with 'java -jar opendonut-tools.jar secret " + secretFile
					+ "' and copy it to the proxy and to every backend.");
			return;
		}

		bridge = new BackendBridge(secret, config.bridge().timestampToleranceSeconds(), logger, this);
		logger.info("OpenDonut: bridge ready on channel {} with secret fingerprint {} (compare this on the "
						+ "proxy and on every backend)",
				config.bridge().channel(), bridge.secretFingerprint());
	}

	/**
	 * Logs the startup summary, and decides whether transfers may be enabled.
	 *
	 * @param configPath the configuration file
	 */
	private void logStartupSummary(Path configPath) {
		logger.info("OpenDonut {} — server '{}' owns {}", OpenDonutMod.version(), self.id(),
				self.bounds().describe());
		logger.info("OpenDonut: world '{}' seed {} on Minecraft {}, configuration {}",
				config.world().name(), worldSeed, server.getServerVersion(), configPath.toAbsolutePath());

		if (bridge == null) {
			transfersEnabled = false;
		} else if (startupFindings.isEmpty()) {
			transfersEnabled = true;
		} else {
			transfersEnabled = false;
			logger.error("OpenDonut: transfers are DISABLED on this server until the following {} problem(s) "
					+ "are fixed:", startupFindings.size());
			for (String finding : startupFindings) {
				logger.error("OpenDonut:   - {}", finding);
			}
		}

		if (transfersEnabled) {
			logger.info("OpenDonut: boundary checks are active; a player who leaves {} by {} block(s) will be "
							+ "handed to the proxy",
					self.bounds().describe(), Math.max(1, config.transfer().boundaryMarginBlocks()));
		}
	}

	/**
	 * A player has finished joining this server.
	 *
	 * @param player the player
	 */
	public void onPlayerJoin(ServerPlayer player) {
		// Start from a clean slate — a player who reconnects must not inherit a cooldown or a stale
		// placement from their previous session — and then start the arrival grace period.
		tracker.forget(player.getUUID());
		tracker.markJoined(player.getUUID(), server.getTickCount());
		helloIfDue(player);
		// Ask the proxy for the stored snapshot before the player can meaningfully play; the answer arrives
		// on this same connection a moment later and is applied by onPlayerDataLoadResponse.
		if (playerDataSync != null) {
			playerDataSync.onPlayerJoin(player);
		}
	}

	/**
	 * A player has disconnected; drop their state.
	 *
	 * @param player the player
	 */
	public void onPlayerDisconnect(ServerPlayer player) {
		// Save first, while the player object is still valid and still holds their items.
		if (playerDataSync != null) {
			playerDataSync.onPlayerDisconnect(player);
		}
		tracker.forget(player.getUUID());
		borderParticles.ifPresent(particles -> particles.forget(player.getUUID()));
	}

	/**
	 * Sends a HELLO to the proxy, at most once every few seconds.
	 *
	 * <p>The bridge has no connection of its own — plugin messages travel through a player — so the proxy
	 * learns who this backend is from the first player who connects. Sending it again on later joins is
	 * harmless and makes a proxy restart self-healing.
	 *
	 * @param player the player whose connection carries the message
	 */
	private void helloIfDue(ServerPlayer player) {
		if (bridge == null || !transfersEnabled) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastHelloMillis < HELLO_RESEND_INTERVAL_MILLIS) {
			return;
		}
		lastHelloMillis = now;

		BridgeMessage.Hello hello = BackendBridge.hello(
				self.id(),
				config.world().name(),
				worldSeed,
				self.bounds(),
				server.getServerVersion(),
				OpenDonutMod.version(),
				self.address().toString());
		bridge.send(player, hello);
	}

	/**
	 * Runs one boundary check for every connected player.
	 *
	 * <p>Called once per server tick. The per-player work is a binary search plus a handful of map lookups,
	 * so the cost is dominated by iterating the player list.
	 *
	 * @param server the running server
	 */
	public void tick(MinecraftServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();

		// Cosmetic, and deliberately before every other check: a player should see where their region ends even
		// when this backend is not allowed to transfer them, because that is exactly when knowing matters.
		borderParticles.ifPresent(particles -> particles.tick(players, self.bounds(), server.getTickCount()));

		// Player-data saving runs even when transfers are disabled: the store can still be filled, so a later
		// re-enable of transfers starts from real data.
		if (playerDataSync != null) {
			playerDataSync.tick(server);
		}

		// Time reporting needs only the bridge, not transfers: a backend whose transfers are off still keeps
		// its clock in step with the authority.
		if (worldTimeSync != null) {
			worldTimeSync.tick(server);
		}

		if (bridge == null || !transfersEnabled) {
			return;
		}
		List<UUID> online = new ArrayList<>(players.size());
		for (ServerPlayer player : players) {
			online.add(player.getUUID());
		}
		tracker.retainOnly(online);

		long now = System.currentTimeMillis();
		long tick = server.getTickCount();
		for (ServerPlayer player : players) {
			try {
				tickPlayer(player, tick, now);
			} catch (RuntimeException e) {
				// One player's problem must never stop the checks for everybody else.
				logger.error("OpenDonut: failed to check the position of {}: {}",
						player.getName().getString(), e.toString(), e);
			}
		}
	}

	/**
	 * The decision procedure for one player.
	 *
	 * @param player the player
	 * @param tick the current server tick
	 * @param now the current wall clock time in milliseconds
	 */
	private void tickPlayer(ServerPlayer player, long tick, long now) {
		UUID playerId = player.getUUID();

		// 1. An arrival instruction wins over everything else: the player is meant to be somewhere else.
		Optional<PlayerTransferTracker.Placement> placement = tracker.peekPlacement(playerId);
		if (placement.isPresent()) {
			PlayerTransferTracker.Placement instruction = placement.get();
			if (now - instruction.issuedAtMillis() > PLACEMENT_MAX_AGE_MILLIS) {
				tracker.takePlacement(playerId);
				logger.warn("OpenDonut: discarded a {} ms old arrival instruction for {}; not teleporting them",
						now - instruction.issuedAtMillis(), player.getName().getString());
			} else {
				applyPlacement(player, instruction);
			}
			return;
		}

		// 2. Respect the arrival grace period: a freshly joined player's position is still settling.
		long joined = tracker.joinedAtTick(playerId);
		if (joined >= 0 && tick - joined < config.transfer().joinGraceTicks()) {
			return;
		}

		// 3. An in-flight transfer is never duplicated; it may however time out.
		Optional<PlayerTransferTracker.PendingTransfer> pending = tracker.pending(playerId);
		if (pending.isPresent()) {
			long age = now - pending.get().requestedAtMillis();
			long limitMillis = config.transfer().maxPendingSeconds() * 1000L;
			if (age > limitMillis) {
				String reason = "the proxy did not answer the transfer request within "
						+ config.transfer().maxPendingSeconds() + " seconds";
				tracker.clearPending(playerId, now, config.transfer().cooldownSeconds());
				logger.warn("OpenDonut: giving up on moving {} to '{}': {}", player.getName().getString(),
						pending.get().targetServerId(), reason);
				applyFailurePolicy(player, reason);
			}
			return;
		}

		// 4. A recent failure or arrival means "leave this player alone for a moment".
		if (tracker.isCoolingDown(playerId, now)) {
			return;
		}

		int blockX = player.blockPosition().getX();
		int blockZ = player.blockPosition().getZ();
		Optional<ServerDefinition> owner = index.findRegion(blockX, blockZ);

		if (owner.isEmpty()) {
			handleUnassignedArea(player, blockX, blockZ);
			return;
		}
		tracker.markUnassigned(playerId, false);

		if (owner.get().id().equals(self.id())) {
			return;
		}

		// 5. Require the player to be genuinely outside, not jittering on the boundary.
		int margin = Math.max(1, config.transfer().boundaryMarginBlocks());
		int distanceOutside = distanceOutside(self.bounds(), blockX, blockZ);
		if (distanceOutside < margin) {
			return;
		}

		requestTransfer(player, owner.get(), distanceOutside, now);
	}

	/**
	 * Handles a player who is standing in a coordinate that no server owns.
	 *
	 * @param player the player
	 * @param blockX the player's global block X
	 * @param blockZ the player's global block Z
	 */
	private void handleUnassignedArea(ServerPlayer player, int blockX, int blockZ) {
		boolean firstTime = tracker.markUnassigned(player.getUUID(), true);
		if (firstTime) {
			logger.info("OpenDonut: {} is at ({}, {}) which no configured server owns. This is allowed "
							+ "(validation.require-full-coverage is false) but they cannot be transferred from "
							+ "here; set transfer.unassigned-area-policy: return to push players back instead.",
					player.getName().getString(), blockX, blockZ);
		}
		if (config.transfer().unassignedAreaPolicy() == UnassignedAreaPolicy.RETURN) {
			pushBackIntoOwnRegion(player, "standing in an unassigned area at (" + blockX + ", " + blockZ + ")");
		}
	}

	/**
	 * Asks the proxy to move a player.
	 *
	 * @param player the player
	 * @param destination the server that owns the player's new coordinates
	 * @param distanceOutside how far outside this server's region the player is
	 * @param now the current time in milliseconds
	 */
	private void requestTransfer(ServerPlayer player, ServerDefinition destination, int distanceOutside, long now) {
		String reason = describeDeparture(player.blockPosition().getX(), player.blockPosition().getZ(),
				distanceOutside);

		// The dimension is reported first, on the same connection, so the proxy can attach it to this very
		// request: the pair is ordered, which is what keeps the dimension from landing on the wrong transfer.
		// If this frame cannot be sent the request still goes out, and the arrival simply carries no
		// dimension — the destination then keeps its own idea of it, exactly as before dimensions were synced.
		bridge.send(player, BackendBridge.transferDimension(player));

		BridgeMessage.TransferRequest request = BackendBridge.transferRequest(
				player, self.id(), destination.id(), reason);

		if (!bridge.send(player, request)) {
			// The request never left the machine, so no answer will arrive. Treat it as a failure straight
			// away rather than waiting for a timeout.
			tracker.clearPending(player.getUUID(), now, config.transfer().cooldownSeconds());
			applyFailurePolicy(player, "the bridge message could not be sent");
			return;
		}

		transfersRequested++;
		tracker.markPending(new PlayerTransferTracker.PendingTransfer(
				player.getUUID(),
				destination.id(),
				player.blockPosition().getX(),
				player.blockPosition().getY(),
				player.blockPosition().getZ(),
				now));

		logger.info("OpenDonut: {} left region '{}' ({}), which is owned by '{}' ({}); asked the proxy to "
						+ "move them",
				player.getName().getString(), self.id(), reason, destination.id(), destination.address());
	}

	/**
	 * Places an arriving player at the global coordinates the proxy supplied.
	 *
	 * <p>The coordinates are global, so they are used as they are: OpenDonut's premise is that every backend
	 * generates the same terrain at the same coordinates, which the startup seed check verifies.
	 *
	 * <p>The player is placed at the centre of the arrival block rather than on its corner. That is a
	 * deliberate, documented simplification of 0.1.0-dev: the protocol carries block coordinates, so the
	 * arrival is exact to the block, not to the sub-block. Their Y is used unchanged, because a player
	 * standing on a block top is already at an integer Y and shifting it risks placing them inside a block.
	 *
	 * <p>When the proxy also sent a {@code PLACE_DIMENSION} for this arrival, the player is moved to that
	 * level at the same global coordinates, with a real cross-level teleport. The coordinates are shared
	 * across the network, so no nether/overworld scaling is applied: a player who left the nether at
	 * (x, y, z) arrives in the nether at (x, y, z). Without the instruction — an older proxy — the player
	 * stays in whatever level their local data put them in, exactly as before dimensions were synchronised.
	 *
	 * @param player the arriving player
	 * @param placement the instruction
	 */
	private void applyPlacement(ServerPlayer player, PlayerTransferTracker.Placement placement) {
		tracker.takePlacement(player.getUUID());
		String dimension = tracker.takeArrivalDimension(player.getUUID()).orElse("");

		if (!placement.playerId().equals(player.getUUID())) {
			logger.warn("OpenDonut: ignoring an arrival instruction addressed to {} but delivered to {}",
					placement.playerId(), player.getName().getString());
			return;
		}
		if (!placement.fromServerId().isEmpty() && placement.fromServerId().equals(self.id())) {
			// The proxy would not do this, so it means the instruction is stale after a bounce.
			logger.warn("OpenDonut: ignoring an arrival instruction that says {} is arriving from '{}', "
							+ "which is this server",
					player.getName().getString(), placement.fromServerId());
			return;
		}

		double targetX = placement.globalX() + 0.5d;
		double targetY = placement.globalY();
		double targetZ = placement.globalZ() + 0.5d;

		String currentDimension = player.level().dimension().identifier().toString();
		boolean placedInOtherDimension = false;
		if (!dimension.isEmpty() && !dimension.equals(currentDimension)) {
			ServerLevel target = resolveLevel(dimension);
			if (target == null) {
				// A dimension this server does not have (a removed datapack): place them in the current
				// level rather than dropping the arrival entirely.
				logger.warn("OpenDonut: the proxy wants {} placed in dimension '{}' but this server has no "
						+ "such level; placing them in '{}' instead", player.getName().getString(),
						dimension, currentDimension);
			} else {
				// A real cross-level teleport, the same call /teleport uses: same global coordinates, other
				// level, no nether scaling, because the backends share one coordinate space.
				placedInOtherDimension = player.teleportTo(target, targetX, targetY, targetZ, Set.of(),
						placement.yaw(), placement.pitch(), true);
				if (!placedInOtherDimension) {
					logger.warn("OpenDonut: the cross-level teleport of {} to '{}' was refused; placing them "
							+ "in '{}' instead", player.getName().getString(), dimension, currentDimension);
				}
			}
		}
		if (!placedInOtherDimension) {
			player.connection.teleport(targetX, targetY, targetZ, placement.yaw(), placement.pitch());
		}
		// The player is now legitimately here: restart the grace period and drop any stale request.
		tracker.clearPending(player.getUUID(), System.currentTimeMillis(), config.transfer().cooldownSeconds());
		tracker.markJoined(player.getUUID(), server.getTickCount());

		if (dimension.isEmpty() || dimension.equals(currentDimension)) {
			logger.info("OpenDonut: placed {} at global ({}, {}, {}) after arriving from '{}'",
					player.getName().getString(), placement.globalX(), placement.globalY(),
					placement.globalZ(),
					placement.fromServerId().isEmpty() ? "unknown" : placement.fromServerId());
		} else {
			logger.info("OpenDonut: placed {} at global ({}, {}, {}) in dimension '{}' after arriving from "
					+ "'{}'", player.getName().getString(), placement.globalX(), placement.globalY(),
					placement.globalZ(), dimension,
					placement.fromServerId().isEmpty() ? "unknown" : placement.fromServerId());
		}
	}

	/**
	 * Resolves a dimension id to a loaded level.
	 *
	 * @param dimension the registry id, for example {@code minecraft:the_nether}
	 * @return the level, or {@code null} when the id is malformed or the server has no such level
	 */
	private ServerLevel resolveLevel(String dimension) {
		Identifier id = Identifier.tryParse(dimension);
		if (id == null) {
			return null;
		}
		return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
	}

	/**
	 * Applies {@code transfer.on-transfer-failure}.
	 *
	 * @param player the player
	 * @param reason the failure, for the log
	 */
	private void applyFailurePolicy(ServerPlayer player, String reason) {
		TransferFailurePolicy policy = config.transfer().onTransferFailure();
		if (policy == TransferFailurePolicy.LOG_ONLY) {
			logger.warn("OpenDonut: {} stays at ({}, {}) for now because the transfer failed: {}. They will be "
							+ "retried after the cooldown.",
					player.getName().getString(), player.blockPosition().getX(), player.blockPosition().getZ(),
					reason);
			return;
		}
		pushBackIntoOwnRegion(player, reason);
	}

	/**
	 * Moves a player back to a safe spot inside this server's own region.
	 *
	 * <h2>Why this is the safe default</h2>
	 * A player who is standing in another server's region while connected to this one is the dangerous
	 * state: this server would generate and write chunks that the other server also considers its own,
	 * which is precisely the corruption OpenDonut exists to prevent. Moving them back is a small, visible
	 * inconvenience; leaving them there is silent data damage.
	 *
	 * @param player the player
	 * @param reason the failure that triggered the push back, for the log
	 */
	private void pushBackIntoOwnRegion(ServerPlayer player, String reason) {
		RegionBounds safe = self.bounds().shrink(Math.max(2, config.transfer().boundaryMarginBlocks()));
		double targetX = clamp(player.getX(), safe.minX() + 0.5d, safe.maxX() - 0.5d);
		double targetZ = clamp(player.getZ(), safe.minZ() + 0.5d, safe.maxZ() - 0.5d);

		player.connection.teleport(targetX, player.getY(), targetZ, player.getYRot(), player.getXRot());
		logger.warn("OpenDonut: moved {} back to ({}, {}, {}) inside '{}': {}",
				player.getName().getString(),
				(int) Math.floor(targetX), player.blockPosition().getY(), (int) Math.floor(targetZ),
				self.id(), reason);
	}

	/**
	 * Clamps a value into a range.
	 *
	 * @param value the value
	 * @param min the inclusive minimum
	 * @param max the inclusive maximum
	 * @return the clamped value
	 */
	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	/**
	 * Describes how a coordinate leaves this server's region.
	 *
	 * @param blockX the global block X
	 * @param blockZ the global block Z
	 * @param distanceOutside how far outside the region the coordinate is
	 * @return a short explanation such as {@code past max-x, 3 blocks outside}
	 */
	private String describeDeparture(int blockX, int blockZ, int distanceOutside) {
		RegionBounds bounds = self.bounds();
		StringBuilder out = new StringBuilder();
		if (blockX < bounds.minX()) {
			out.append("past min-x");
		} else if (blockX >= bounds.maxX()) {
			out.append("past max-x");
		} else if (blockZ < bounds.minZ()) {
			out.append("past min-z");
		} else if (blockZ >= bounds.maxZ()) {
			out.append("past max-z");
		} else {
			out.append("outside the region");
		}
		return out.append(", ").append(distanceOutside).append(" block(s) outside").toString();
	}

	/**
	 * Computes how far a coordinate lies outside a region.
	 *
	 * @param bounds the region
	 * @param blockX the global block X
	 * @param blockZ the global block Z
	 * @return the Chebyshev distance outside the region, {@code 0} when the coordinate is inside
	 */
	static int distanceOutside(RegionBounds bounds, int blockX, int blockZ) {
		int dx = Math.max(0, Math.max(bounds.minX() - blockX, blockX - (bounds.maxX() - 1)));
		int dz = Math.max(0, Math.max(bounds.minZ() - blockZ, blockZ - (bounds.maxZ() - 1)));
		return Math.max(dx, dz);
	}

	@Override
	public void onHelloAck(BridgeMessage.HelloAck ack) {
		if (!ack.accepted()) {
			proxyAccepted = false;
			transfersEnabled = false;
			logger.error("OpenDonut: the proxy refused this backend and will not route players here: {}. "
					+ "Transfers are disabled on this server until the cause is fixed.", ack.detail());
			return;
		}
		proxyAccepted = true;
		if (ack.resolvedSeed() != worldSeed) {
			transfersEnabled = false;
			logger.error("OpenDonut: the proxy resolved seed {} for the network but this world was generated "
							+ "with {}. Terrain would not line up across the boundary, so transfers are disabled "
							+ "here. See docs/troubleshooting.md.",
					ack.resolvedSeed(), worldSeed);
			return;
		}
		logger.info("OpenDonut: the proxy accepted this backend as '{}' (seed {}, {} transfer(s) in flight)",
				ack.serverId(), ack.resolvedSeed(), tracker.pendingCount());
		if (worldTimeSync != null) {
			worldTimeSync.onHelloAck();
		}
	}

	@Override
	public void onPlacePlayer(BridgeMessage.PlacePlayer placement) {
		if (!placement.toServerId().equals(self.id())) {
			logger.warn("OpenDonut: the proxy addressed an arrival to '{}' but this server is '{}'; ignoring it",
					placement.toServerId(), self.id());
			return;
		}
		tracker.storePlacement(new PlayerTransferTracker.Placement(
				placement.playerId(),
				placement.fromServerId(),
				placement.globalX(),
				placement.globalY(),
				placement.globalZ(),
				placement.yaw(),
				placement.pitch(),
				placement.issuedAtMillis()));
		logger.debug("OpenDonut: queued an arrival of {} at ({}, {}, {}) from '{}'",
				placement.playerId(), placement.globalX(), placement.globalY(), placement.globalZ(),
				placement.fromServerId());
	}

	@Override
	public void onPlaceDimension(BridgeMessage.PlaceDimension dim) {
		// Stored separately from the placement so the two messages can arrive in either order; both are
		// consumed together when the placement is applied.
		tracker.storeArrivalDimension(dim.playerId(), dim.dimension());
		logger.debug("OpenDonut: queued dimension {} for arriving player {}", dim.dimension(), dim.playerId());
	}

	@Override
	public void onTransferResult(BridgeMessage.TransferResult result) {
		Optional<PlayerTransferTracker.PendingTransfer> pending = tracker.pending(result.playerId());
		long now = System.currentTimeMillis();

		if (result.success()) {
			tracker.clearPending(result.playerId(), now, 0);
			logger.info("OpenDonut: transferred player {} to '{}' ({})", result.playerId(),
					result.toServerId(), result.statusMessage());
			return;
		}

		transfersRejected++;
		tracker.clearPending(result.playerId(), now, config.transfer().cooldownSeconds());
		logger.warn("OpenDonut: the proxy could not move player {} to '{}': {}", result.playerId(),
				result.toServerId(), result.statusMessage());

		ServerPlayer player = server.getPlayerList().getPlayer(result.playerId());
		if (player == null) {
			return;
		}
		String reason = "the destination server did not accept the player: " + result.statusMessage();
		if (pending.isPresent()) {
			applyFailurePolicy(player, reason);
		} else {
			logger.warn("OpenDonut: {} failed a transfer that this server did not request; not touching them",
					player.getName().getString());
		}
	}

	@Override
	public void onPlayerDataLoadResponse(BridgeMessage.PlayerDataLoadResponse response) {
		if (playerDataSync != null) {
			playerDataSync.onLoadResponse(response);
		}
	}

	@Override
	public void onPlayerDataSaveResult(BridgeMessage.PlayerDataSaveResult result) {
		if (playerDataSync != null) {
			playerDataSync.onSaveResult(result);
		}
	}

	@Override
	public void onPlayerDataFlushRequest(BridgeMessage.PlayerDataFlushRequest request) {
		if (playerDataSync != null) {
			playerDataSync.onFlushRequest(request);
		} else {
			logger.warn("OpenDonut: the proxy asked this server to flush {} but player-data sync is not active "
					+ "here; the transfer will time out and be refused", request.playerId());
		}
	}

	@Override
	public void onTimeSet(BridgeMessage.TimeSet set) {
		if (worldTimeSync != null) {
			worldTimeSync.onTimeSet(set);
		}
	}

	/** @return the configuration this backend is running on */
	public OpenDonutConfig config() {
		return config;
	}

	/** @return the entry of the configuration that describes this server */
	public ServerDefinition self() {
		return self;
	}

	/** @return the region lookup engine in use */
	public RegionIndex index() {
		return index;
	}

	/** @return whether this backend is currently allowed to transfer players */
	public boolean transfersEnabled() {
		return transfersEnabled;
	}

	/** @return whether the proxy has accepted this backend */
	public boolean proxyAccepted() {
		return proxyAccepted;
	}

	/** @return the seed this world was actually generated with */
	public long worldSeed() {
		return worldSeed;
	}

	/** @return the per-player transfer state, for the status command */
	PlayerTransferTracker tracker() {
		return tracker;
	}

	/**
	 * Describes what the border-particle wall looks like from a player's position, for {@code /opendonut border}.
	 *
	 * @param player the player asking
	 * @return a multi-line report
	 */
	public String describeBorderFor(ServerPlayer player) {
		return borderParticles
				.map(particles -> particles.describeFor(player, self.bounds()))
				.orElse("border particles are disabled (border-particles.enabled: false)");
	}

	/** @return the world-time synchroniser, or {@code null} when time sync is off */
	WorldTimeSync worldTimeSync() {
		return worldTimeSync;
	}

	/** @return the bridge, or empty when it could not be created */
	Optional<BackendBridge> bridge() {
		return Optional.ofNullable(bridge);
	}

	/** @return the player-data synchroniser, or {@code null} when it is not active */
	PlayerDataSync playerDataSync() {
		return playerDataSync;
	}

	/** @return how many transfers this backend has asked for since it started */
	public int transfersRequested() {
		return transfersRequested;
	}

	/** @return how many of those failed */
	public int transfersRejected() {
		return transfersRejected;
	}

	/** @return the startup findings that keep transfers disabled, in order */
	public List<String> startupFindings() {
		return List.copyOf(startupFindings);
	}

	/**
	 * Renders the state of this backend for {@code /opendonut status}.
	 *
	 * @return a multi-line report
	 */
	public String describeStatus() {
		StringBuilder out = new StringBuilder();
		out.append("OpenDonut ").append(OpenDonutMod.version()).append(System.lineSeparator());
		out.append("  server      : ").append(self.id()).append(" (").append(self.address()).append(')')
				.append(System.lineSeparator());
		out.append("  region      : ").append(self.bounds().describe()).append(System.lineSeparator());
		out.append("  world       : ").append(config.world().name()).append(", seed ").append(worldSeed)
				.append(System.lineSeparator());
		out.append("  transfers   : ").append(transfersEnabled ? "enabled" : "DISABLED")
				.append(proxyAccepted ? " (proxy accepted)" : " (proxy has not confirmed yet)")
				.append(System.lineSeparator());
		out.append("  activity    : ").append(transfersRequested).append(" requested, ")
				.append(transfersRejected).append(" refused").append(System.lineSeparator());
		out.append("  players     : ").append(tracker.describe()).append(System.lineSeparator());
		out.append("  player data : ")
				.append(playerDataSync == null ? "disabled (player-data.enabled: false)"
						: playerDataSync.describe())
				.append(System.lineSeparator());
		out.append("  world time  : ")
				.append(worldTimeSync == null ? "disabled (time.enabled: false)" : worldTimeSync.describe())
				.append(System.lineSeparator());
		bridge().ifPresent(b -> out.append("  bridge      : ").append(b.sentFrames()).append(" sent, ")
				.append(b.receivedFrames()).append(" received, ").append(b.rejectedFrames())
				.append(" refused, ").append(b.replayCacheSize()).append(" nonces cached")
				.append(System.lineSeparator()));
		if (!startupFindings.isEmpty()) {
			out.append("  startup problems:").append(System.lineSeparator());
			for (String finding : startupFindings) {
				out.append("    - ").append(finding).append(System.lineSeparator());
			}
		}
		return out.toString();
	}

	/**
	 * Tests whether a configuration file exists, for the startup log.
	 *
	 * @param configDirectory the config directory
	 * @return {@code true} when the file is present
	 */
	public static boolean configurationExists(Path configDirectory) {
		return Files.exists(configPathFor(configDirectory));
	}
}
