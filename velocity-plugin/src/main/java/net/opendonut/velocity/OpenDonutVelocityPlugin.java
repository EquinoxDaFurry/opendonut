package net.opendonut.velocity;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;

import org.slf4j.Logger;

import net.opendonut.common.bridge.BridgeCodec;
import net.opendonut.common.bridge.BridgeDecodeResult;
import net.opendonut.common.bridge.BridgeEnvelope;
import net.opendonut.common.bridge.BridgeMessage;
import net.opendonut.common.bridge.BridgeSecret;
import net.opendonut.common.bridge.DecodeFailure;
import net.opendonut.common.bridge.NonceCache;
import net.opendonut.common.config.ConfigLoadResult;
import net.opendonut.common.config.ConfigLoader;
import net.opendonut.common.config.SeedResolution;
import net.opendonut.common.config.SeedStore;
import net.opendonut.common.config.ValidationIssue;
import net.opendonut.common.model.OpenDonutConfig;
import net.opendonut.common.model.PlayerDataSettings;
import net.opendonut.common.model.ServerDefinition;
import net.opendonut.common.model.TimeSettings;
import net.opendonut.common.playerdata.PlayerDataCoordinator;
import net.opendonut.common.playerdata.PlayerDataStore;
import net.opendonut.common.playerdata.SqlitePlayerDataStore;
import net.opendonut.common.timesync.TimeSyncCoordinator;

/**
 * The OpenDonut Velocity plugin: the authority on where a player is allowed to be.
 *
 * <h2>Division of responsibility</h2>
 * <table border="1">
 *   <caption>Who decides what</caption>
 *   <tr><th>Component</th><th>Decides</th></tr>
 *   <tr><td>Client</td><td>Nothing. It is never consulted and never trusted.</td></tr>
 *   <tr><td>Fabric backend</td><td>Where its own players are, and that its region and world match the
 *       configuration. It may <em>ask</em> for a transfer.</td></tr>
 *   <tr><td>This plugin</td><td>Which server owns a coordinate, whether a backend may receive players, the
 *       network seed, and whether a transfer happens at all.</td></tr>
 * </table>
 *
 * <h2>The transfer, step by step</h2>
 * <ol>
 *   <li>A backend detects that one of its players has left its region and sends an authenticated
 *       {@code TRANSFER_REQUEST} through that player's connection.</li>
 *   <li>Velocity delivers it as a {@link PluginMessageEvent} whose source is a
 *       {@code ServerConnection} — <em>or</em> a {@code Player}, if a modified client sent it. The latter is
 *       refused and logged.</li>
 *   <li>The frame's HMAC, timestamp and nonce are verified before a single field is read.</li>
 *   <li>The destination is re-derived from this plugin's own region table. The backend's hint is only used
 *       to detect and report a disagreement.</li>
 *   <li>The destination is checked to be a registered, verified backend.</li>
 *   <li>The transfer is recorded and the connection request is issued.</li>
 *   <li>When the player has connected, {@link ServerPostConnectEvent} sends the destination backend a
 *       {@code PLACE_PLAYER} with the global coordinates. Velocity's plugin message API needs an
 *       established connection to send through, which is exactly why the coordinates arrive <em>after</em>
 *       the connect and not before.</li>
 *   <li>The requesting backend is told the outcome, so it can clear its state or apply its failure policy.</li>
 * </ol>
 *
 * <h2>Player data</h2>
 * When {@code player-data.enabled} is true, this plugin also owns the players' synchronised state: a store
 * (SQLite by default) holding one versioned snapshot per player, a lease saying which backend may write it,
 * and the flush-before-move sequence that hands authority over without ever having two owners. The whole
 * decision logic lives in {@link PlayerDataCoordinator}, which is plain Java and unit tested without a proxy;
 * this class only wires events to it. See {@code docs/plan-player-data-sync.md}.
 */
@Plugin(
		id = "opendonut",
		name = "OpenDonut",
		version = "0.1.0-dev",
		description = "Routes players between Fabric backends so that several servers behave like one "
				+ "continuous world.",
		authors = {"OpenDonut contributors"},
		url = "https://github.com/opendonut/opendonut")
public final class OpenDonutVelocityPlugin {

	/** The Minecraft version this build was made for. Surfaced in HELLO acknowledgements. */
	private static final String MINECRAFT_VERSION = "26.3";

	/** How long a transfer stays meaningful. Matches the backend's pending timeout by default. */
	private static final long TRANSFER_MAX_AGE_MILLIS = 60_000L;

	private final ProxyServer server;
	private final Logger logger;
	private final Path dataDirectory;

	private final NonceCache nonceCache = new NonceCache();

	private OpenDonutConfig config;
	private ProxyRoutingTable routing;
	private byte[] secret;
	private ChannelIdentifier channel;
	private long resolvedSeed;
	private boolean active;

	/**
	 * The player-data store, or {@code null} when {@code player-data.enabled} is false or the store could not
	 * be opened. Null means "no synchronisation", never "synchronise unsafely".
	 */
	private PlayerDataStore playerDataStore;

	/** The lease/compare-and-set authority over {@link #playerDataStore}, or {@code null} as above. */
	private PlayerDataCoordinator playerData;

	/**
	 * The world-clock synchronisation coordinator, or {@code null} when {@code time.enabled} is false or the
	 * bridge is down. Null means "no time sync", never "sync unsafely".
	 */
	private TimeSyncCoordinator timeSync;

	/** The periodic time-push task, cancelled and rebuilt on every reload. */
	private ScheduledTask timeSyncTask;

	/**
	 * Transfers waiting for the source backend to flush the player's data, keyed by player.
	 *
	 * @param transfer the transfer being held
	 * @param sourceConnection the source backend's connection, for the timeout abort and the result
	 * @param deadlineMillis when the wait gives up
	 * @param leaseMoved whether the lease was already moved to the destination
	 */
	private record FlushWait(
			ProxyRoutingTable.PendingTransfer transfer,
			ServerConnection sourceConnection,
			long deadlineMillis,
			boolean leaseMoved) {
	}

	private final Map<UUID, FlushWait> flushes = new ConcurrentHashMap<>();

	/**
	 * Players whose lease this proxy has moved to a destination as part of a transfer, and to which server.
	 * Tracked so that an abandoned transfer hands the lease back to the source instead of locking the
	 * player out for a whole lease period.
	 */
	private final Map<UUID, String> leaseHolders = new ConcurrentHashMap<>();

	/**
	 * Which backend each player was last connected to, so logins go straight there instead of bouncing
	 * through the default initial server and loading chunks on a server the player is moved away from.
	 */
	private final LastServerRegistry lastServers = new LastServerRegistry();

	/**
	 * Creates the plugin. Velocity injects everything the plugin needs.
	 *
	 * @param server the proxy
	 * @param logger the plugin logger
	 * @param dataDirectory the directory Velocity gives the plugin for its files
	 */
	@Inject
	public OpenDonutVelocityPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
		this.server = server;
		this.logger = logger;
		this.dataDirectory = dataDirectory;
	}

	/** @return the Minecraft version this build targets, for HELLO checks */
	static String minecraftVersionHint() {
		return MINECRAFT_VERSION;
	}

	/**
	 * Activation: load and validate the configuration, resolve the seed, load the secret, register the
	 * channel, register the backends and the admin command.
	 *
	 * <p>If the configuration is unusable the plugin stays inert and says why. It does not disable the proxy:
	 * the proxy still works as a plain proxy, which is the least surprising outcome for an operator who
	 * mistyped a coordinate.
	 *
	 * @param event the proxy initialisation event
	 */
	@Subscribe
	public void onProxyInitialize(ProxyInitializeEvent event) {
		if (!reload()) {
			return;
		}

		CommandManager commands = server.getCommandManager();
		commands.register(commands.metaBuilder("opendonut").plugin(this).build(),
				new OpenDonutProxyCommand(this));

		logger.info("OpenDonut {} is active: {} region(s) of the world border, seed {}, channel {}, "
						+ "secret fingerprint {}",
				MINECRAFT_VERSION, config.servers().size(), resolvedSeed, config.bridge().channel(),
				BridgeSecret.fingerprint(secret));
		logger.info("OpenDonut: every backend must use the same seed. Put '{}' in each backend's "
						+ "server.properties before its world is created.",
				SeedStore.levelSeedLine(resolvedSeed));
	}

	/**
	 * Loads everything the plugin needs. Also the implementation of {@code /opendonut reload}.
	 *
	 * @return {@code true} when the plugin is now active
	 */
	synchronized boolean reload() {
		active = false;
		flushes.clear();
		closePlayerDataStore();
		closeTimeSync();
		Path configPath = dataDirectory.resolve("opendonut.yml");
		ConfigLoadResult loaded = ConfigLoader.load(configPath);

		if (!loaded.isUsable()) {
			logger.error("OpenDonut is not active: the configuration at {} cannot be used. Validate it with "
							+ "'java -jar opendonut-tools.jar check {}'.{}", configPath, configPath,
					System.lineSeparator() + loaded.result().formatWithSummary());
			return false;
		}
		OpenDonutConfig candidate = loaded.config().orElseThrow();
		for (ValidationIssue issue : loaded.result().issues()) {
			switch (issue.severity()) {
				case ERROR -> logger.error("OpenDonut configuration: {}", issue.format());
				case WARNING -> logger.warn("OpenDonut configuration: {}", issue.format());
				case INFO -> logger.info("OpenDonut configuration: {}", issue.format());
			}
		}

		// The secret is loaded before anything is registered, because a channel that cannot be authenticated
		// must never be listened on: it would accept unauthenticated routing instructions.
		if (candidate.bridge().enabled()) {
			Path secretFile = Path.of(candidate.bridge().secretFile());
			if (!secretFile.isAbsolute()) {
				secretFile = dataDirectory.resolve(secretFile);
			}
			try {
				BridgeSecret.LoadResult loadedSecret = BridgeSecret.loadOrCreate(secretFile);
				secret = loadedSecret.secret();
				for (ValidationIssue issue : loadedSecret.issues()) {
					logger.info("OpenDonut: {}", issue.message());
				}
			} catch (IOException e) {
				logger.error("OpenDonut is not active: the bridge secret at {} could not be used: {}. "
								+ "Generate one with 'java -jar opendonut-tools.jar secret {}' and copy it to "
								+ "every backend.", secretFile, e.getMessage(), secretFile);
				return false;
			}
		} else {
			logger.warn("OpenDonut: bridge.enabled is false, so transfers are switched off. Players will be "
					+ "confined to whichever backend they first connect to.");
			secret = null;
		}

		// The proxy is the authority on the network seed, so it is resolved here, once, and then checked
		// against every backend's report. With 'seed: random' this is also where the value is generated and
		// persisted for the backends to copy.
		SeedResolution seed = SeedStore.resolve(dataDirectory, candidate.world().seed());
		for (ValidationIssue issue : seed.issues()) {
			logger.info("OpenDonut: {}", issue.message());
		}
		if (seed.hasErrors()) {
			logger.error("OpenDonut is not active: the world seed could not be resolved. See the messages "
					+ "above.");
			return false;
		}

		config = candidate;
		resolvedSeed = seed.seed();
		routing = new ProxyRoutingTable(candidate);

		if (candidate.bridge().enabled()) {
			registerChannel(candidate.bridge());
			server.getChannelRegistrar().register(channel);
		}
		if (candidate.proxy().autoRegisterBackends()) {
			registerBackends(candidate);
		}
		verifyBackendsAreRegistered(candidate);
		setUpPlayerData(candidate);
		setUpTimeSync(candidate);

		active = true;
		return true;
	}

	/**
	 * Opens the player-data store when the configuration asks for it.
	 *
	 * <p>Player data travels over the bridge, so it needs the bridge; without one there is nothing to do and
	 * synchronisation stays off. If the store cannot be opened the plugin says so loudly and keeps routing
	 * exactly as before: a proxy that cannot store snapshots must not pretend to synchronise.
	 *
	 * @param candidate the validated configuration
	 */
	private void setUpPlayerData(OpenDonutConfig candidate) {
		PlayerDataSettings settings = candidate.playerData();
		if (!settings.enabled()) {
			return;
		}
		if (!candidate.bridge().enabled()) {
			logger.error("OpenDonut: player-data.enabled is true but bridge.enabled is false. Player data "
					+ "travels over the bridge, so synchronisation is OFF. Set bridge.enabled: true or "
					+ "player-data.enabled: false.");
			return;
		}
		Path file = Path.of(settings.file());
		if (!file.isAbsolute()) {
			file = dataDirectory.resolve(file);
		}
		try {
			playerDataStore = SqlitePlayerDataStore.open(file);
			playerData = new PlayerDataCoordinator(playerDataStore, settings);
			logger.info("OpenDonut: player data sync ready — {}", playerData.describe());
		} catch (IOException | java.sql.SQLException | RuntimeException e) {
			playerDataStore = null;
			playerData = null;
			logger.error("OpenDonut: the player-data store at {} could not be opened ({}). Player data will NOT "
							+ "be synchronised, but routing keeps working. Fix the path or the permissions and "
							+ "run /opendonut reload.", file, e.getMessage());
		}
	}

	/**
	 * Closes the player-data store, if one is open.
	 */
	private void closePlayerDataStore() {
		if (playerDataStore != null) {
			try {
				playerDataStore.close();
			} catch (RuntimeException e) {
				logger.warn("OpenDonut: closing the player-data store failed: {}", e.toString());
			}
			playerDataStore = null;
			playerData = null;
		}
	}

	/**
	 * Sets up world-clock synchronisation when the configuration asks for it.
	 *
	 * <p>Like player data, time travels over the bridge, so it needs the bridge; without one there is nothing to
	 * do and every backend keeps its own clock. The coordinator holds the authority's last report and the
	 * estimate between reports; a periodic task pushes the estimate to every non-authority backend that has a
	 * live connection.
	 *
	 * @param candidate the validated configuration
	 */
	private void setUpTimeSync(OpenDonutConfig candidate) {
		TimeSettings settings = candidate.time();
		if (!settings.enabled()) {
			return;
		}
		if (!candidate.bridge().enabled()) {
			logger.error("OpenDonut: time.enabled is true but bridge.enabled is false. Time reports travel over "
					+ "the bridge, so time sync is OFF. Set bridge.enabled: true or time.enabled: false.");
			return;
		}
		timeSync = new TimeSyncCoordinator(settings);
		logger.info("OpenDonut: world time sync ready — {}", timeSync.describe());

		if (settings.syncIntervalSeconds() <= 0) {
			return;
		}
		long pollMillis = 1000L;
		long[] lastPush = {0L};
		timeSyncTask = server.getScheduler().buildTask(this, () -> {
			if (timeSync == null || !active) {
				return;
			}
			long now = System.currentTimeMillis();
			if (now - lastPush[0] < settings.syncIntervalMillis()) {
				return;
			}
			lastPush[0] = now;
			Optional<TimeSyncCoordinator.ClockReading> estimate = timeSync.estimateAt(now);
			if (estimate.isEmpty()) {
				return;
			}
			// The bridge only has a channel while a player is on a backend, so the pushable backends are
			// exactly the current servers of the online players (deduplicated by name).
			Set<String> pushed = new HashSet<>();
			for (Player player : server.getAllPlayers()) {
				Optional<ServerConnection> current = player.getCurrentServer();
				if (current.isEmpty()) {
					continue;
				}
				ServerConnection connection = current.get();
				String name = connection.getServerInfo().getName();
				if (!pushed.add(name) || timeSync.isAuthority(name)) {
					continue;
				}
				sendTimeSet(connection, estimate.get(), "periodic");
			}
		}).repeat(pollMillis, TimeUnit.MILLISECONDS).schedule();
	}

	/**
	 * Stops the periodic time-push task and drops the coordinator.
	 */
	private void closeTimeSync() {
		if (timeSyncTask != null) {
			timeSyncTask.cancel();
			timeSyncTask = null;
		}
		timeSync = null;
	}

	/**
	 * Pushes the current time estimate to a backend, if time sync is on and a report has been accepted.
	 *
	 * @param connection the connection to the backend
	 * @param reason the reason string for the backend's log: {@code hello}, {@code transfer} or {@code periodic}
	 */
	private void sendTimeSet(ServerConnection connection, String reason) {
		if (timeSync == null) {
			return;
		}
		Optional<TimeSyncCoordinator.ClockReading> estimate = timeSync.estimateAt(System.currentTimeMillis());
		estimate.ifPresent(reading -> sendTimeSet(connection, reading, reason));
	}

	/**
	 * Sends a {@code TIME_SET} carrying a specific reading.
	 *
	 * @param connection the connection to the backend
	 * @param reading the reading to send
	 * @param reason the reason string for the backend's log
	 */
	private void sendTimeSet(
			ServerConnection connection, TimeSyncCoordinator.ClockReading reading, String reason) {
		sendTo(connection, new BridgeMessage.TimeSet(
				reading.clock(), reading.totalTicks(), reading.rate(), reading.paused(), reason));
	}

	/**
	 * Handles a backend's {@code TIME_REPORT}: the authority's reading becomes the new base of the estimate.
	 *
	 * @param connection the connection the report arrived through
	 * @param report the report
	 */
	private void handleTimeReport(ServerConnection connection, BridgeMessage.TimeReport report) {
		if (timeSync == null) {
			return;
		}
		String serverName = connection.getServerInfo().getName();
		TimeSyncCoordinator.ReportVerdict verdict = timeSync.onReport(serverName,
				new TimeSyncCoordinator.ClockReading(
						report.clock(), report.totalTicks(), report.rate(), report.paused()),
				System.currentTimeMillis());
		if (verdict.accepted()) {
			logger.info("OpenDonut: time report from '{}': {}", serverName, verdict.detail());
		} else if (!verdict.detail().isEmpty()) {
			logger.warn("OpenDonut: ignored a time report from '{}': {}", serverName, verdict.detail());
		}
	}

	/**
	 * Registers the plugin message channel this plugin listens on.
	 *
	 * <p>Velocity must be told about the channel, otherwise our traffic would be forwarded to the client
	 * untouched — and a client that does not know the channel can be disconnected for it.
	 *
	 * <p>The namespace and path come from {@link net.opendonut.common.model.BridgeSettings}, which is also what
	 * the Fabric backend uses to build its payload type. Splitting the channel by hand here as well would be a
	 * second implementation of the same rule, and the two would eventually disagree.
	 *
	 * @param settings the bridge settings holding the channel
	 */
	private void registerChannel(net.opendonut.common.model.BridgeSettings settings) {
		channel = MinecraftChannelIdentifier.create(settings.channelNamespace(), settings.channelPath());
	}

	/**
	 * Registers every configured backend with the proxy, so {@code velocity.toml} does not have to list them.
	 *
	 * <p>Adding a server then means editing one YAML file. A name that {@code velocity.toml} already defines
	 * is left alone: the file's entry wins, and the difference is reported, because silently replacing an
	 * operator's own entry would be worse than saying so.
	 *
	 * @param candidate the configuration
	 */
	private void registerBackends(OpenDonutConfig candidate) {
		for (ServerDefinition definition : candidate.servers()) {
			if (server.getServer(definition.id()).isPresent()) {
				continue;
			}
			InetSocketAddress address =
					new InetSocketAddress(definition.address().host(), definition.address().port());
			server.registerServer(new ServerInfo(definition.id(), address));
			logger.info("OpenDonut: registered backend '{}' at {} with the proxy",
					definition.id(), definition.address());
		}
	}

	/**
	 * Reports configured backends that the proxy does not know about.
	 *
	 * <p>A misnamed entry is the most common setup mistake, and it produces a confusing "server not found"
	 * later. Saying so at startup is cheaper.
	 *
	 * @param candidate the configuration
	 */
	private void verifyBackendsAreRegistered(OpenDonutConfig candidate) {
		for (ServerDefinition definition : candidate.servers()) {
			if (server.getServer(definition.id()).isEmpty()) {
				logger.error("OpenDonut: backend '{}' is in the configuration but the proxy has no server "
								+ "with that name. Set proxy.auto-register-backends: true, or add it to "
								+ "velocity.toml with exactly this name.",
						definition.id());
			}
		}
	}

	/**
	 * Handles a plugin message on the OpenDonut channel.
	 *
	 * <p>Three things happen here, in this order, and the order is the security design:
	 *
	 * <ol>
	 *   <li>the message is marked as handled, so it is never forwarded to the player's client;</li>
	 *   <li>the <em>source</em> is checked: only a backend connection is allowed to instruct the proxy;</li>
	 *   <li>the frame is authenticated before any field is interpreted.</li>
	 * </ol>
	 *
	 * @param event the plugin message event
	 */
	@Subscribe
	public void onPluginMessage(PluginMessageEvent event) {
		if (!active || channel == null || !event.getIdentifier().getId().equals(channel.getId())) {
			return;
		}

		// Never let our traffic reach the client: it has no mod, and a client that receives a payload it does
		// not know may be disconnected.
		event.setResult(PluginMessageEvent.ForwardResult.handled());

		if (!(event.getSource() instanceof ServerConnection connection)) {
			// A player sent this. A vanilla client cannot, so it is either a modified client probing the
			// channel or a badly configured plugin.
			logger.warn("OpenDonut: refused a plugin message on {} that came from a player rather than a "
							+ "backend ({}). The channel is only for backends; a client cannot route itself.",
					channel.getId(), BridgeCodec.describeFrame(event.getData()));
			return;
		}

		BridgeDecodeResult decoded = BridgeCodec.decode(event.getData(), secret,
				System.currentTimeMillis(), config.bridge().timestampToleranceSeconds(), nonceCache);
		if (!decoded.isAccepted()) {
			BridgeDecodeResult.Failed failure = (BridgeDecodeResult.Failed) decoded;
			if (failure.failure().isSuspicious()) {
				logger.warn("OpenDonut: refused a {} frame from backend '{}': {} ({}). A mismatched bridge "
								+ "secret is the usual cause; otherwise treat this as an attempt to "
								+ "interfere.",
						BridgeCodec.describeFrame(event.getData()),
						connection.getServerInfo().getName(), failure.failure(), failure.detail());
			} else {
				logger.warn("OpenDonut: refused a frame from backend '{}': {} ({})",
						connection.getServerInfo().getName(), failure.failure(), failure.detail());
			}
			return;
		}

		BridgeMessage message = decoded.envelopeIfAccepted().orElseThrow().message();
		switch (message) {
			case BridgeMessage.Hello hello -> handleHello(connection, hello);
			case BridgeMessage.TransferRequest request -> handleTransferRequest(connection, request);
			case BridgeMessage.PlayerDataLoadRequest request -> handlePlayerDataLoadRequest(connection, request);
			case BridgeMessage.PlayerDataSaveRequest request -> handlePlayerDataSaveRequest(connection, request);
			case BridgeMessage.HelloAck ack -> logger.warn(
					"OpenDonut: backend '{}' sent a HELLO_ACK; that message only travels from the proxy to a "
							+ "backend. Ignoring it.", connection.getServerInfo().getName());
			case BridgeMessage.PlacePlayer placement -> logger.warn(
					"OpenDonut: backend '{}' sent a PLACE_PLAYER; only the proxy may place players. Ignoring it.",
					connection.getServerInfo().getName());
			case BridgeMessage.TransferResult result -> logger.warn(
					"OpenDonut: backend '{}' sent a TRANSFER_RESULT; only the proxy reports outcomes. "
							+ "Ignoring it.", connection.getServerInfo().getName());
			case BridgeMessage.PlayerDataLoadResponse response -> logger.warn(
					"OpenDonut: backend '{}' sent a PLAYER_DATA_LOAD_RESPONSE; only the proxy answers load "
							+ "requests. Ignoring it.", connection.getServerInfo().getName());
			case BridgeMessage.PlayerDataSaveResult result -> logger.warn(
					"OpenDonut: backend '{}' sent a PLAYER_DATA_SAVE_RESULT; only the proxy reports save "
							+ "outcomes. Ignoring it.", connection.getServerInfo().getName());
			case BridgeMessage.PlayerDataFlushRequest request -> logger.warn(
					"OpenDonut: backend '{}' sent a PLAYER_DATA_FLUSH_REQUEST; only the proxy may demand a "
							+ "flush. Ignoring it.", connection.getServerInfo().getName());
			case BridgeMessage.TimeReport report -> handleTimeReport(connection, report);
			case BridgeMessage.TimeSet set -> logger.warn(
					"OpenDonut: backend '{}' sent a TIME_SET; only the proxy may set world time. Ignoring it.",
					connection.getServerInfo().getName());
			case BridgeMessage.TransferDimension dim -> handleTransferDimension(connection, dim);
			case BridgeMessage.PlaceDimension dim -> logger.warn(
					"OpenDonut: backend '{}' sent a PLACE_DIMENSION; only the proxy may place dimensions. "
							+ "Ignoring it.", connection.getServerInfo().getName());
			case BridgeMessage.TransferPortal portal -> handleTransferPortal(connection, portal);
			case BridgeMessage.PlacePortal portal -> logger.warn(
					"OpenDonut: backend '{}' sent a PLACE_PORTAL; only the proxy may replicate portals. "
							+ "Ignoring it.", connection.getServerInfo().getName());
			case BridgeMessage.AdvancementsLoadRequest request ->
					handleAdvancementsLoadRequest(connection, request);
			case BridgeMessage.AdvancementsSaveRequest request ->
					handleAdvancementsSaveRequest(connection, request);
			case BridgeMessage.ChatRelay relay -> handleChatRelay(connection, relay);
			case BridgeMessage.AdvancementsLoadResponse response -> logger.warn(
					"OpenDonut: backend '{}' sent an ADVANCEMENTS_LOAD_RESPONSE; only the proxy answers "
							+ "load requests. Ignoring it.", connection.getServerInfo().getName());
			case BridgeMessage.AdvancementsSaveResult result -> logger.warn(
					"OpenDonut: backend '{}' sent an ADVANCEMENTS_SAVE_RESULT; only the proxy reports save "
							+ "outcomes. Ignoring it.", connection.getServerInfo().getName());
			case BridgeMessage.ChatBroadcast broadcast -> logger.warn(
					"OpenDonut: backend '{}' sent a CHAT_BROADCAST; only the proxy relays chat. Ignoring it.",
					connection.getServerInfo().getName());
		}
	}

	/**
	 * Handles a backend asking for a player's stored advancement progress.
	 *
	 * @param connection the backend connection the request arrived through
	 * @param request the request
	 */
	private void handleAdvancementsLoadRequest(
			ServerConnection connection, BridgeMessage.AdvancementsLoadRequest request) {
		if (playerData == null || !config.playerData().syncAdvancements()) {
			// No store or advancements sync off: saying nothing leaves the backend's local file in place,
			// which is exactly the behaviour of an older proxy.
			return;
		}
		String serverName = connection.getServerInfo().getName();
		PlayerDataCoordinator.AdvancementsLoadOutcome outcome = playerData.handleAdvancementsLoadRequest(
				request.playerId(), serverName, System.currentTimeMillis());
		if (outcome.present()) {
			logger.info("OpenDonut: sent the stored advancements of {} to '{}' ({})", request.playerId(),
					serverName, outcome.detail());
		}
		sendTo(connection, new BridgeMessage.AdvancementsLoadResponse(
				request.playerId(), outcome.present(), outcome.revision(), outcome.payload(), outcome.detail()));
	}

	/**
	 * Handles a backend saving a player's advancement progress.
	 *
	 * @param connection the backend connection the request arrived through
	 * @param request the request
	 */
	private void handleAdvancementsSaveRequest(
			ServerConnection connection, BridgeMessage.AdvancementsSaveRequest request) {
		if (playerData == null || !config.playerData().syncAdvancements()) {
			return;
		}
		String serverName = connection.getServerInfo().getName();
		if (!request.serverId().equals(serverName)) {
			logger.warn("OpenDonut: backend '{}' tried to save advancements as '{}'; the proxy's name for "
					+ "that connection wins", serverName, request.serverId());
		}
		PlayerDataCoordinator.SaveOutcome outcome = playerData.handleAdvancementsSaveRequest(
				request.playerId(), serverName, request.baseRevision(), request.payload(),
				System.currentTimeMillis());
		sendTo(connection, new BridgeMessage.AdvancementsSaveResult(
				request.playerId(), outcome.accepted(), outcome.revision(), outcome.detail()));
		if (outcome.accepted()) {
			logger.debug("OpenDonut: stored the advancements of {} from '{}' ({})", request.playerId(),
					serverName, outcome.detail());
		} else {
			logger.warn("OpenDonut: refused the advancements of {} from '{}': {}", request.playerId(),
					serverName, outcome.detail());
		}
	}

	/**
	 * Relays a chat message from one backend to every other backend that currently has players.
	 *
	 * <p>The source is never sent the broadcast back, so the message is shown exactly once per server. The
	 * receiving backends display it as an unsigned relay and must not relay it again.
	 *
	 * @param connection the backend connection the relay arrived through
	 * @param relay the relay
	 */
	private void handleChatRelay(ServerConnection connection, BridgeMessage.ChatRelay relay) {
		if (!config.chat().enabled()) {
			return;
		}
		String sourceName = connection.getServerInfo().getName();
		BridgeMessage.ChatBroadcast broadcast =
				new BridgeMessage.ChatBroadcast(relay.senderId(), relay.senderName(), relay.content());
		int sent = 0;
		for (Player online : server.getAllPlayers()) {
			Optional<ServerConnection> current = online.getCurrentServer();
			if (current.isEmpty() || current.get().getServerInfo().getName().equals(sourceName)) {
				continue;
			}
			sendTo(current.get(), broadcast);
			sent++;
		}
		logger.info("OpenDonut: relayed chat from '{}' on '{}' to {} other backend(s)", relay.senderName(),
				sourceName, sent);
	}

	/**
	 * Handles a backend asking for a player's stored data, which is how the lease moves to a destination
	 * after a transfer.
	 *
	 * @param connection the backend connection the request arrived through
	 * @param request the request
	 */
	private void handlePlayerDataLoadRequest(ServerConnection connection, BridgeMessage.PlayerDataLoadRequest request) {
		if (playerData == null) {
			return;
		}
		String serverName = connection.getServerInfo().getName();
		if (!request.serverId().equals(serverName)) {
			logger.warn("OpenDonut: backend '{}' asked for player data as '{}'; the proxy's name for that "
					+ "connection wins", serverName, request.serverId());
		}
		PlayerDataCoordinator.LoadOutcome outcome =
				playerData.handleLoadRequest(request.playerId(), serverName, System.currentTimeMillis());
		if (outcome.leaseAcquired() && outcome.leaseStolen()) {
			logger.warn("OpenDonut: the lease for {} was taken over by '{}' from an expired holder",
					request.playerId(), serverName);
		}
		if (outcome.leaseAcquired() && outcome.present()) {
			logger.info("OpenDonut: sent the stored data of {} to '{}' ({})", request.playerId(), serverName,
					outcome.detail());
		} else if (!outcome.leaseAcquired()) {
			logger.warn("OpenDonut: refused to serve player data for {} to '{}': {}", request.playerId(), serverName,
					outcome.detail());
		}
		sendTo(connection, new BridgeMessage.PlayerDataLoadResponse(
				request.playerId(), outcome.present(), outcome.revision(), outcome.envelope(), outcome.detail()));
	}

	/**
	 * Handles a backend saving a player's data.
	 *
	 * <p>When the save answers a flush the proxy demanded for a pending transfer, the transfer continues if
	 * the save was accepted and is aborted if it was not — unless {@code player-data.on-flush-failure} says
	 * to proceed with the last known snapshot. Either way the backend is told the outcome, so its own
	 * bookkeeping (the revision it will base the next save on) stays correct.
	 *
	 * @param connection the backend connection the request arrived through
	 * @param request the request
	 */
	private void handlePlayerDataSaveRequest(ServerConnection connection, BridgeMessage.PlayerDataSaveRequest request) {
		if (playerData == null) {
			return;
		}
		String serverName = connection.getServerInfo().getName();
		if (!request.serverId().equals(serverName)) {
			logger.warn("OpenDonut: backend '{}' tried to save player data as '{}'; the proxy's name for that "
					+ "connection wins", serverName, request.serverId());
		}

		PlayerDataCoordinator.SaveOutcome outcome = playerData.handleSaveRequest(
				request.playerId(), serverName, request.baseRevision(), request.envelope(),
				System.currentTimeMillis());
		sendTo(connection, new BridgeMessage.PlayerDataSaveResult(
				request.playerId(), outcome.accepted(), outcome.revision(), outcome.detail()));

		if (outcome.accepted()) {
			logger.info("OpenDonut: stored the data of {} from '{}' ({})", request.playerId(), serverName,
					outcome.detail());
		} else {
			logger.warn("OpenDonut: refused the data of {} from '{}': {}", request.playerId(), serverName,
					outcome.detail());
		}

		FlushWait wait = flushes.get(request.playerId());
		if (wait == null || !wait.transfer().fromServerId().equals(serverName)) {
			// An ordinary periodic or disconnect save, not an answer to a flush.
			return;
		}
		if (outcome.accepted()) {
			moveLeaseAndConnect(wait, request.playerId(), serverName);
		} else if (config.playerData().mayProceedWithoutFreshData()) {
			logger.warn("OpenDonut: the flush for {} failed ({}), but player-data.on-flush-failure is "
					+ "proceed-with-last-known, so the transfer continues with the last stored snapshot",
					request.playerId(), outcome.detail());
			moveLeaseAndConnect(wait, request.playerId(), serverName);
		} else {
			abortTransferForFlush(wait, "the source could not save the player's data: " + outcome.detail());
		}
	}

	/**
	 * Moves the player's lease to the transfer's destination and starts the connection, once the flush is
	 * committed.
	 *
	 * @param wait the flush wait
	 * @param playerId the player
	 * @param fromServerId the source server id
	 */
	private void moveLeaseAndConnect(FlushWait wait, UUID playerId, String fromServerId) {
		ProxyRoutingTable.PendingTransfer transfer = wait.transfer();
		String detail = playerData.moveLease(playerId, fromServerId, transfer.toServerId(),
				System.currentTimeMillis());
		logger.info("OpenDonut: {}; moving {} to '{}'", detail, playerId, transfer.toServerId());
		leaseHolders.put(playerId, transfer.toServerId());
		flushes.remove(playerId, wait);
		startConnection(wait.sourceConnection(), transfer);
	}

	/**
	 * Hands a moved lease back to the source server when a transfer is abandoned, so a failed move does not
	 * lock the player out until the lease expires.
	 *
	 * @param transfer the abandoned transfer
	 */
	private void returnLeaseIfMoved(ProxyRoutingTable.PendingTransfer transfer) {
		String holder = leaseHolders.remove(transfer.playerId());
		if (playerData != null && holder != null && !holder.equals(transfer.fromServerId())) {
			logger.info("OpenDonut: {}", playerData.moveLease(
					transfer.playerId(), holder, transfer.fromServerId(), System.currentTimeMillis()));
		}
	}

	/**
	 * Aborts a transfer whose flush failed or timed out, telling the source so its failure policy applies.
	 *
	 * @param wait the flush wait
	 * @param reason the explanation
	 */
	private void abortTransferForFlush(FlushWait wait, String reason) {
		ProxyRoutingTable.PendingTransfer transfer = wait.transfer();
		flushes.remove(transfer.playerId(), wait);
		routing.clearPending(transfer.playerId());
		logger.warn("OpenDonut: refusing to move {} to '{}': {}", transfer.playerId(), transfer.toServerId(),
				reason);
		sendTo(wait.sourceConnection(), new BridgeMessage.TransferResult(transfer.playerId(),
				transfer.fromServerId(), transfer.toServerId(), false, reason,
				transfer.globalX(), transfer.globalY(), transfer.globalZ()));
	}

	/**
	 * Watches a flush wait and aborts the transfer when the deadline passes.
	 *
	 * @param wait the flush wait
	 */
	private void scheduleFlushTimeout(FlushWait wait) {
		long pollMillis = 250L;
		server.getScheduler().buildTask(this, task -> {
			FlushWait current = flushes.get(wait.transfer().playerId());
			if (current == null || current != wait) {
				task.cancel();
				return;
			}
			if (System.currentTimeMillis() < wait.deadlineMillis()) {
				return;
			}
			task.cancel();
			if (config.playerData().mayProceedWithoutFreshData()) {
				logger.warn("OpenDonut: the flush for {} did not arrive within {} second(s), but "
						+ "player-data.on-flush-failure is proceed-with-last-known, so the transfer continues "
						+ "with the last stored snapshot", wait.transfer().playerId(),
						config.playerData().flushTimeoutSeconds());
				moveLeaseAndConnect(wait, wait.transfer().playerId(), wait.transfer().fromServerId());
			} else {
				abortTransferForFlush(wait, "the source did not save the player's data within "
						+ config.playerData().flushTimeoutSeconds() + " second(s)");
			}
		}).delay(pollMillis, TimeUnit.MILLISECONDS).repeat(pollMillis, TimeUnit.MILLISECONDS).schedule();
	}

	/**
	 * Handles a backend announcing itself.
	 *
	 * @param connection the backend connection the HELLO arrived through
	 * @param hello the message
	 */
	private void handleHello(ServerConnection connection, BridgeMessage.Hello hello) {
		ProxyRoutingTable.Verdict verdict = routing.acceptHello(
				connection.getServerInfo().getName(),
				hello.serverId(),
				hello.worldName(),
				hello.seed(),
				hello.bounds(),
				hello.minecraftVersion(),
				hello.openDonutVersion(),
				resolvedSeed,
				MINECRAFT_VERSION);

		if (verdict.accepted()) {
			logger.info("OpenDonut: backend '{}' verified ({})", hello.serverId(), verdict.detail());
		} else {
			logger.error("OpenDonut: backend '{}' does NOT match the central configuration and will not "
					+ "receive players: {}", hello.serverId(), verdict.detail());
		}

		sendTo(connection, new BridgeMessage.HelloAck(hello.serverId(), verdict.accepted(),
				verdict.detail(), resolvedSeed));

		// With time sync on, a verified backend becomes (or confirms) the authority, and a backend that just
		// announced itself gets the current estimate straight away — this is what corrects a backend that
		// restarted while the others kept playing.
		if (timeSync != null) {
			String name = connection.getServerInfo().getName();
			timeSync.onBackendVerified(name, System.currentTimeMillis());
			if (verdict.accepted()) {
				logger.info("OpenDonut: {}", timeSync.describe());
				sendTimeSet(connection, "hello");
			}
		}
	}

	/**
	 * Handles a backend's report of which dimension a player is standing in.
	 *
	 * <p>The report arrives immediately before the {@code TRANSFER_REQUEST} for the same move, on the same
	 * connection, and is consumed when that request is accepted — so a dimension can never be attached to the
	 * wrong transfer. The same "the player must actually be on the reporting server" check as for a transfer
	 * request applies: the report is only trusted from the connection the player is really on.
	 *
	 * @param connection the reporting backend's connection
	 * @param dim the report
	 */
	private void handleTransferDimension(ServerConnection connection, BridgeMessage.TransferDimension dim) {
		String sourceServerName = connection.getServerInfo().getName();
		Optional<Player> maybePlayer = server.getPlayer(dim.playerId());
		if (maybePlayer.isEmpty()) {
			return;
		}
		Optional<ServerConnection> currentServer = maybePlayer.get().getCurrentServer();
		if (currentServer.isEmpty() || !currentServer.get().getServerInfo().getName().equals(sourceServerName)) {
			logger.warn("OpenDonut: backend '{}' reported a dimension for {} but that player is on '{}'; "
					+ "ignoring the report", sourceServerName, maybePlayer.get().getUsername(),
					currentServer.map(server -> server.getServerInfo().getName()).orElse("no server"));
			return;
		}
		routing.noteDimension(dim.playerId(), dim.dimension());
		logger.debug("OpenDonut: backend '{}' reports {} is in dimension {}", sourceServerName,
				maybePlayer.get().getUsername(), dim.dimension());
	}

	/**
	 * Handles a backend's report of the blocks around the far side of a portal a player just used.
	 *
	 * <p>Same shape as {@link #handleTransferDimension}: the report arrives immediately before the
	 * {@code TRANSFER_REQUEST} for the same move, on the same connection, and is consumed when that request is
	 * accepted, with the same "the player must actually be on the reporting server" check.
	 *
	 * @param connection the reporting backend's connection
	 * @param portal the report
	 */
	private void handleTransferPortal(ServerConnection connection, BridgeMessage.TransferPortal portal) {
		String sourceServerName = connection.getServerInfo().getName();
		Optional<Player> maybePlayer = server.getPlayer(portal.playerId());
		if (maybePlayer.isEmpty()) {
			return;
		}
		Optional<ServerConnection> currentServer = maybePlayer.get().getCurrentServer();
		if (currentServer.isEmpty() || !currentServer.get().getServerInfo().getName().equals(sourceServerName)) {
			logger.warn("OpenDonut: backend '{}' reported a portal for {} but that player is on '{}'; "
					+ "ignoring the report", sourceServerName, maybePlayer.get().getUsername(),
					currentServer.map(server -> server.getServerInfo().getName()).orElse("no server"));
			return;
		}
		routing.notePortal(portal.playerId(), portal);
		logger.debug("OpenDonut: backend '{}' reports {} portal block(s) around the far side for {}",
				sourceServerName, portal.portal().blocks().size(), maybePlayer.get().getUsername());
	}

	/**
	 * Handles a request to move a player.
	 *
	 * @param connection the requesting backend's connection
	 * @param request the request
	 */
	private void handleTransferRequest(ServerConnection connection, BridgeMessage.TransferRequest request) {
		String sourceServerName = connection.getServerInfo().getName();

		Optional<Player> maybePlayer = server.getPlayer(request.playerId());
		if (maybePlayer.isEmpty()) {
			sendTo(connection, failure(request, "", "the player is not connected to this proxy any more"));
			return;
		}
		Player player = maybePlayer.get();

		// The claim "this player is on the server that is asking" must be true, otherwise the request is about
		// somebody else's player and is ignored.
		Optional<ServerConnection> currentServer = player.getCurrentServer();
		if (currentServer.isEmpty() || !currentServer.get().getServerInfo().getName().equals(sourceServerName)) {
			logger.warn("OpenDonut: backend '{}' asked to move {} but that player is on '{}'; ignoring the "
							+ "request", sourceServerName, player.getUsername(),
					currentServer.map(server -> server.getServerInfo().getName()).orElse("no server"));
			return;
		}
		if (!request.fromServerId().equals(sourceServerName)) {
			logger.warn("OpenDonut: backend '{}' described itself as '{}' in a transfer request; the proxy's "
							+ "name for that connection wins", sourceServerName, request.fromServerId());
		}

		// The decision, made from the proxy's own table and never from the backend's hint.
		Optional<ServerDefinition> owner = routing.owner(request.globalX(), request.globalZ());
		if (owner.isEmpty()) {
			sendTo(connection, failure(request, "",
					"no configured server owns (" + request.globalX() + ", " + request.globalZ()
							+ "), so there is nowhere to move the player"));
			return;
		}
		ServerDefinition destination = owner.get();

		if (!destination.id().equals(request.requestedServerId()) && !request.requestedServerId().isEmpty()) {
			// Not a security failure — the proxy's answer wins — but it means the two configurations disagree,
			// which is worth an administrator's attention.
			logger.warn("OpenDonut: backend '{}' expected '{}' to own ({}, {}) but this proxy's table says "
							+ "'{}'. Both machines must load the same opendonut.yml.",
					sourceServerName, request.requestedServerId(), request.globalX(), request.globalZ(),
					destination.id());
		}

		if (destination.id().equals(sourceServerName)) {
			// The player never left, as far as the proxy is concerned. Move on rather than bounce them.
			sendTo(connection, failure(request, sourceServerName,
					"the coordinate is already owned by the server the player is on"));
			return;
		}

		// Whether the destination may receive a player, and on what terms. See ProxyRoutingTable.Admission: an
		// unknown backend is admitted provisionally rather than refused, because requiring an announcement
		// that only a player's arrival could trigger would deadlock the first transfer to every backend.
		ProxyRoutingTable.Admission admission = routing.admissionFor(destination.id());
		if (admission == ProxyRoutingTable.Admission.REFUSED) {
			sendTo(connection, failure(request, destination.id(),
					"backend '" + destination.id() + "' announced itself and does not match the central "
							+ "configuration, so no player is moved onto it: " + routing.refusalReason(
									destination.id())
							+ ". Fix that backend, then move a player to it explicitly (/server "
							+ destination.id() + " <player>) or restart the proxy so it can announce itself "
							+ "again."));
			return;
		}

		Optional<RegisteredServer> target = server.getServer(destination.id());
		if (target.isEmpty()) {
			sendTo(connection, failure(request, destination.id(),
					"the proxy has no server registered as '" + destination.id() + "'"));
			return;
		}

		routing.pruneStale(TRANSFER_MAX_AGE_MILLIS);

		// The dimension and portal blocks the source reported just before this request, if any: an older
		// backend does not send them, and then the transfer carries neither and the destination keeps its own
		// idea of both.
		String dimension = routing.consumeDimension(request.playerId()).orElse("");
		BridgeMessage.TransferPortal portal = routing.consumePortal(request.playerId()).orElse(null);
		ProxyRoutingTable.PendingTransfer transfer = new ProxyRoutingTable.PendingTransfer(
				request.playerId(),
				sourceServerName,
				destination.id(),
				request.globalX(),
				request.globalY(),
				request.globalZ(),
				request.yaw(),
				request.pitch(),
				request.reason(),
				System.currentTimeMillis(),
				dimension,
				portal);
		routing.markPending(transfer);

		// With player-data sync on, the source must commit the player's snapshot before the connection
		// request is issued: the player is never moved away from data that nobody has stored. The flush is
		// answered by a PLAYER_DATA_SAVE_REQUEST, which handlePlayerDataSaveRequest turns into the connect.
		if (playerData != null) {
			FlushWait wait = new FlushWait(transfer, connection,
					System.currentTimeMillis() + config.playerData().flushTimeoutMillis(), false);
			flushes.put(transfer.playerId(), wait);
			sendTo(connection, new BridgeMessage.PlayerDataFlushRequest(
					transfer.playerId(), sourceServerName, destination.id()));
			scheduleFlushTimeout(wait);
			logger.info("OpenDonut: holding the move of {} to '{}' until '{}' saves their data (up to {} s)",
					player.getUsername(), destination.id(), sourceServerName,
					config.playerData().flushTimeoutSeconds());
			return;
		}

		if (admission == ProxyRoutingTable.Admission.PROVISIONAL) {
			logger.info("OpenDonut: moving {} from '{}' to '{}' at global ({}, {}, {}) because {}. '{}' has "
							+ "not announced itself yet — it will do so as this player arrives, and their "
							+ "position is only applied once it verifies (within {} s).",
					player.getUsername(), sourceServerName, destination.id(), request.globalX(),
					request.globalY(), request.globalZ(), request.reason(), destination.id(),
					config.proxy().arrivalVerificationTimeoutSeconds());
		} else {
			logger.info("OpenDonut: moving {} from '{}' to '{}' at global ({}, {}, {}) because {}",
					player.getUsername(), sourceServerName, destination.id(), request.globalX(),
					request.globalY(), request.globalZ(), request.reason());
		}

		startConnection(connection, transfer);
	}

	/**
	 * Issues the connection request for an accepted transfer.
	 *
	 * @param sourceConnection the requesting backend's connection
	 * @param transfer the transfer
	 */
	private void startConnection(ServerConnection sourceConnection, ProxyRoutingTable.PendingTransfer transfer) {
		Optional<RegisteredServer> target = server.getServer(transfer.toServerId());
		Optional<Player> maybePlayer = server.getPlayer(transfer.playerId());
		if (target.isEmpty() || maybePlayer.isEmpty()) {
			routing.clearPending(transfer.playerId());
			String reason = target.isEmpty()
					? "the proxy has no server registered as '" + transfer.toServerId() + "'"
					: "the player is not connected to this proxy any more";
			sendTo(sourceConnection, new BridgeMessage.TransferResult(transfer.playerId(),
					transfer.fromServerId(), transfer.toServerId(), false, reason,
					transfer.globalX(), transfer.globalY(), transfer.globalZ()));
			return;
		}
		maybePlayer.get().createConnectionRequest(target.get()).connect()
				.whenComplete((result, error) -> completeTransfer(sourceConnection, transfer, result, error));
	}

	/**
	 * Reports the outcome of a connect attempt to the requesting backend.
	 *
	 * @param sourceConnection the requesting backend's connection
	 * @param transfer the transfer
	 * @param result the connection result, or {@code null} when the attempt threw
	 * @param error the exception, or {@code null} on a normal completion
	 */
	private void completeTransfer(
			ServerConnection sourceConnection,
			ProxyRoutingTable.PendingTransfer transfer,
			ConnectionRequestBuilder.Result result,
			Throwable error) {

		if (error != null) {
			routing.clearPending(transfer.playerId());
			returnLeaseIfMoved(transfer);
			logger.error("OpenDonut: the connection request for {} to '{}' failed: {}",
					transfer.playerId(), transfer.toServerId(), error.toString());
			sendTo(sourceConnection, new BridgeMessage.TransferResult(transfer.playerId(),
					transfer.fromServerId(), transfer.toServerId(), false,
					"connection request failed: " + error.getClass().getSimpleName(),
					transfer.globalX(), transfer.globalY(), transfer.globalZ()));
			return;
		}

		boolean alreadyThere = result.getStatus() == ConnectionRequestBuilder.Status.ALREADY_CONNECTED;
		if (result.isSuccessful() || alreadyThere) {
			logger.info("OpenDonut: {} is now on '{}' ({})", transfer.playerId(), transfer.toServerId(),
					result.getStatus());
			sendTo(sourceConnection, new BridgeMessage.TransferResult(transfer.playerId(),
					transfer.fromServerId(), transfer.toServerId(), true, result.getStatus().name(),
					transfer.globalX(), transfer.globalY(), transfer.globalZ()));

			if (alreadyThere) {
				// No ServerPostConnectEvent will fire, so the placement has to be delivered directly. Without
				// this the player would stay wherever they were, which is the wrong side of the boundary.
				deliverPlacement(transfer);
			}
			return;
		}

		routing.clearPending(transfer.playerId());
		returnLeaseIfMoved(transfer);
		String reason = result.getStatus().name() + result.getReasonComponent()
				.map(component -> " (" + component + ")")
				.orElse("");
		logger.warn("OpenDonut: could not move {} to '{}': {}", transfer.playerId(), transfer.toServerId(),
				reason);
		sendTo(sourceConnection, new BridgeMessage.TransferResult(transfer.playerId(),
				transfer.fromServerId(), transfer.toServerId(), false, reason,
				transfer.globalX(), transfer.globalY(), transfer.globalZ()));
	}

	/**
	 * A player has finished connecting to a server: if they were being transferred, place them.
	 *
	 * <p>This is where the global coordinates reach the destination backend. It has to happen here rather than
	 * before the connect, because Velocity can only send a plugin message through an established connection —
	 * there is no "server to server" channel.
	 *
	 * @param event the post-connect event
	 */
	/**
	 * Sends a player straight to the backend they last played on, when there is one.
	 *
	 * <p>Velocity's default initial server is simply the first registered one, so without this a player who
	 * logged off in the east would be connected to the west first — loading chunks around their logout
	 * position there — and only then moved on. When the remembered server is registered and not refused by
	 * the routing table, it becomes the initial server and no detour happens. The memory is in-process, so
	 * after a proxy restart the first login falls back to the default.
	 *
	 * @param event the choose-initial-server event
	 */
	@Subscribe
	public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
		if (!active || config == null || !config.proxy().rememberLastServer()) {
			return;
		}
		String remembered = lastServers.lookup(event.getPlayer().getUniqueId());
		if (remembered == null) {
			return;
		}
		Optional<RegisteredServer> target = server.getServer(remembered);
		if (target.isEmpty()) {
			return;
		}
		if (routing.admissionFor(remembered) == ProxyRoutingTable.Admission.REFUSED) {
			logger.info("OpenDonut: not sending {} to '{}' for this login: that backend is refused ({})",
					event.getPlayer().getUsername(), remembered, routing.refusalReason(remembered));
			return;
		}
		event.setInitialServer(target.get());
		logger.info("OpenDonut: sending {} straight to '{}', the server they last played on",
				event.getPlayer().getUsername(), remembered);
	}

	@Subscribe
	public void onServerPostConnect(ServerPostConnectEvent event) {
		if (!active) {
			return;
		}
		Player player = event.getPlayer();
		// Remember where every player actually lands — including the initial connect, which is what makes
		// the next login direct. A transfer's post-connect records the destination, which is equally right.
		player.getCurrentServer()
				.ifPresent(connection -> lastServers.remember(
						player.getUniqueId(), connection.getServerInfo().getName()));
		Optional<ProxyRoutingTable.PendingTransfer> maybeTransfer = routing.pending(player.getUniqueId());
		if (maybeTransfer.isEmpty()) {
			return;
		}
		ProxyRoutingTable.PendingTransfer transfer = maybeTransfer.get();

		Optional<ServerConnection> currentServer = player.getCurrentServer();
		String landedOn = currentServer.map(connection -> connection.getServerInfo().getName()).orElse("");
		if (!landedOn.equals(transfer.toServerId())) {
			// The player ended up somewhere else — a kick and reconnect, or another plugin redirecting them.
			// The transfer is abandoned, and the backend that asked is told, so its failure policy applies.
			routing.clearPending(player.getUniqueId());
			logger.warn("OpenDonut: {} was being moved to '{}' but connected to '{}' instead; abandoning the "
							+ "transfer", player.getUsername(), transfer.toServerId(),
					landedOn.isEmpty() ? "nothing" : landedOn);
			return;
		}

		// The player has arrived. Whether their position may be applied depends on whether this backend has
		// proven itself yet.
		switch (routing.admissionFor(transfer.toServerId())) {
			case ADMITTED -> {
				// Verified (or verification is off): place them and be done.
				deliverPlacement(transfer);
				routing.clearPending(player.getUniqueId());
			}
			case REFUSED -> {
				routing.clearPending(player.getUniqueId());
				returnPlayer(player, transfer, "it announced itself on arrival and does not match the central "
						+ "configuration: " + routing.refusalReason(transfer.toServerId()));
			}
			case PROVISIONAL -> {
				// Nothing heard yet. The backend is announcing itself right now — its HELLO travels through this
				// very player's connection — so wait briefly for the verdict instead of guessing.
				routing.clearPending(player.getUniqueId());
				awaitVerification(player, transfer);
			}
		}
	}

	/**
	 * Waits for an arriving backend to announce itself, then places the player or returns them.
	 *
	 * <h2>Why the wait is necessary</h2>
	 * A backend sends its {@code HELLO} from its own player-join handling, which happens a moment <em>after</em>
	 * Velocity fires {@link ServerPostConnectEvent}. Checking the admission state once, here, would therefore
	 * always see "unknown" for a backend's first transfer and bounce a player who had done nothing wrong. The
	 * check is repeated on a short timer until the verdict arrives or the configured window expires.
	 *
	 * <p>Meanwhile the player is on the destination but has <em>not</em> been given their arrival coordinates,
	 * so they stand wherever the destination put them while this decision is made — a few hundred milliseconds
	 * in practice. That is the deliberate trade for not deadlocking the first transfer.
	 *
	 * @param player the arriving player
	 * @param transfer the transfer being verified
	 */
	private void awaitVerification(Player player, ProxyRoutingTable.PendingTransfer transfer) {
		long timeoutMillis = config.proxy().arrivalVerificationTimeoutMillis();
		long startedAt = System.currentTimeMillis();
		long pollMillis = 200L;

		logger.info("OpenDonut: waiting up to {} ms for backend '{}' to announce itself, so that {} can be "
						+ "placed at their arrival coordinates",
				timeoutMillis, transfer.toServerId(), player.getUsername());

		server.getScheduler().buildTask(this, task -> {
			Optional<Player> stillConnected = server.getPlayer(transfer.playerId());
			if (stillConnected.isEmpty()) {
				task.cancel();
				return;
			}
			Player arriving = stillConnected.get();

			switch (routing.admissionFor(transfer.toServerId())) {
				case ADMITTED -> {
					task.cancel();
					logger.info("OpenDonut: backend '{}' verified itself; placing {}",
							transfer.toServerId(), arriving.getUsername());
					deliverPlacement(transfer);
				}
				case REFUSED -> {
					task.cancel();
					returnPlayer(arriving, transfer, "it announced itself and does not match the central "
							+ "configuration: " + routing.refusalReason(transfer.toServerId()));
				}
				case PROVISIONAL -> {
					if (System.currentTimeMillis() - startedAt >= timeoutMillis) {
						task.cancel();
						returnPlayer(arriving, transfer, "it did not announce itself within "
								+ (timeoutMillis / 1000) + " second(s), so the proxy could not verify that it "
								+ "is generating the same world");
					}
				}
			}
		}).delay(pollMillis, TimeUnit.MILLISECONDS).repeat(pollMillis, TimeUnit.MILLISECONDS).schedule();
	}

	/**
	 * Sends a player back to the server they were being transferred from.
	 *
	 * <p>Used when a destination turns out not to be trustworthy after the player has already been moved to it:
	 * it announced itself and failed verification, or it never announced itself at all. Leaving the player
	 * there would mean standing on a backend that may be generating a different world, in a region this proxy
	 * cannot reason about, which is exactly the state the verification exists to prevent.
	 *
	 * @param player the player to return
	 * @param transfer the transfer that is being abandoned
	 * @param reason the explanation, for the log and for the backend's failure policy
	 */
	private void returnPlayer(Player player, ProxyRoutingTable.PendingTransfer transfer, String reason) {
		logger.warn("OpenDonut: {} cannot stay on '{}' because {}, returning them to '{}'",
				player.getUsername(), transfer.toServerId(), reason, transfer.fromServerId());
		returnLeaseIfMoved(transfer);

		Optional<RegisteredServer> source = server.getServer(transfer.fromServerId());
		if (source.isEmpty()) {
			logger.error("OpenDonut: cannot return {} to '{}' because the proxy has no such server "
							+ "registered. They stay on '{}'; move them manually.",
					player.getUsername(), transfer.fromServerId(), transfer.toServerId());
			return;
		}

		player.createConnectionRequest(source.get()).connect().whenComplete((result, error) -> {
			if (error != null || !result.isSuccessful()) {
				String status = error != null ? error.getClass().getSimpleName() : result.getStatus().name();
				logger.error("OpenDonut: could not return {} to '{}' ({}). They stay on '{}'; move them "
								+ "manually.", player.getUsername(), transfer.fromServerId(), status,
						transfer.toServerId());
				return;
			}
			logger.info("OpenDonut: returned {} to '{}'", player.getUsername(), transfer.fromServerId());
			// Tell the backend that asked, through the connection the player is now on, so it can clear its
			// pending transfer and apply its failure policy.
			player.getCurrentServer().ifPresent(current -> sendTo(current, new BridgeMessage.TransferResult(
					transfer.playerId(), transfer.fromServerId(), transfer.toServerId(), false, reason,
					transfer.globalX(), transfer.globalY(), transfer.globalZ())));
		});
	}

	/**
	 * Sends a placement instruction to the destination backend.
	 *
	 * @param transfer the transfer whose placement should be delivered
	 */
	private void deliverPlacement(ProxyRoutingTable.PendingTransfer transfer) {
		Optional<Player> player = server.getPlayer(transfer.playerId());
		if (player.isEmpty()) {
			logger.warn("OpenDonut: cannot deliver a placement for {} because they are no longer connected",
					transfer.playerId());
			return;
		}
		Optional<ServerConnection> connection = player.get().getCurrentServer();
		if (connection.isEmpty()) {
			logger.warn("OpenDonut: cannot deliver a placement for {} because they are not on a server",
					transfer.playerId());
			return;
		}
		sendTo(connection.get(), routing.placementFor(transfer));
		// The dimension rides right behind the placement: the destination applies both together, so the
		// player lands at the placement coordinates in the dimension they left in. Sent only when the source
		// reported one; an older destination build refuses the unknown message id cleanly and simply places
		// the player as it always has.
		routing.placementDimensionFor(transfer).ifPresent(dimension -> {
			sendTo(connection.get(), dimension);
			logger.debug("OpenDonut: told '{}' to place {} in dimension {}",
					connection.get().getServerInfo().getName(), transfer.playerId(), dimension.dimension());
		});
		// The far-side portal blocks ride right behind the dimension: the destination recreates them in the
		// arrival level, so a portal whose link crossed the boundary exists on both sides.
		routing.placementPortalFor(transfer).ifPresent(portal -> {
			sendTo(connection.get(), portal);
			logger.debug("OpenDonut: told '{}' to recreate {} portal block(s) at ({}, {}, {}) for {}",
					connection.get().getServerInfo().getName(), portal.portal().blocks().size(),
					portal.portal().originX(), portal.portal().originY(), portal.portal().originZ(),
					transfer.playerId());
		});
		logger.debug("OpenDonut: told '{}' to place {} at global ({}, {}, {})",
				connection.get().getServerInfo().getName(), transfer.playerId(), transfer.globalX(),
				transfer.globalY(), transfer.globalZ());

		// The same connection is the earliest channel to the destination, so the arrival's clock correction
		// rides with the placement: the player lands in a world whose time matches the authority.
		sendTimeSet(connection.get(), "transfer");
	}

	/**
	 * Forgets a player's transfer state when they disconnect.
	 *
	 * <p>The lease itself is deliberately left to expire: a player who disconnects mid-transfer may still be
	 * reconnecting, and releasing the lease here would let a second backend take it while the first still
	 * holds the player's live data.
	 *
	 * @param event the disconnect event
	 */
	@Subscribe
	public void onDisconnect(DisconnectEvent event) {
		if (routing != null) {
			routing.clearPending(event.getPlayer().getUniqueId());
		}
		flushes.remove(event.getPlayer().getUniqueId());
		leaseHolders.remove(event.getPlayer().getUniqueId());
	}

	/**
	 * Closes the player-data store when the proxy stops.
	 *
	 * @param event the shutdown event
	 */
	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent event) {
		closePlayerDataStore();
		closeTimeSync();
	}

	/**
	 * Sends a bridge message to a backend through a connection.
	 *
	 * @param connection the connection
	 * @param message the message
	 */
	private void sendTo(ServerConnection connection, BridgeMessage message) {
		if (secret == null) {
			return;
		}
		try {
			byte[] frame = BridgeCodec.encode(message, secret);
			boolean sent = connection.sendPluginMessage(channel, frame);
			if (!sent) {
				logger.warn("OpenDonut: the proxy refused to send a {} to backend '{}' (channel {} not "
								+ "registered?)", message.type(), connection.getServerInfo().getName(),
						channel.getId());
			}
		} catch (RuntimeException e) {
			logger.error("OpenDonut: could not send a {} to backend '{}': {}", message.type(),
					connection.getServerInfo().getName(), e.toString());
		}
	}

	/**
	 * Builds a failed transfer result.
	 *
	 * @param request the original request
	 * @param toServerId the destination that was attempted, possibly empty
	 * @param reason the explanation
	 * @return the message
	 */
	private BridgeMessage.TransferResult failure(
			BridgeMessage.TransferRequest request, String toServerId, String reason) {
		logger.warn("OpenDonut: refusing to move {}: {}", request.playerName(), reason);
		return new BridgeMessage.TransferResult(request.playerId(), request.fromServerId(), toServerId, false,
				reason, request.globalX(), request.globalY(), request.globalZ());
	}

	/** @return whether the plugin is active and routing */
	boolean isActive() {
		return active;
	}

	/** @return the loaded configuration, or {@code null} when inactive */
	OpenDonutConfig config() {
		return config;
	}

	/** @return the routing table, or {@code null} when inactive */
	ProxyRoutingTable routing() {
		return routing;
	}

	/** @return the resolved network seed */
	long resolvedSeed() {
		return resolvedSeed;
	}

	/** @return the secret's fingerprint, so two machines can be compared without printing the secret */
	String secretFingerprint() {
		return secret == null ? "(no secret)" : BridgeSecret.fingerprint(secret);
	}

	/** @return the proxy this plugin is attached to */
	ProxyServer server() {
		return server;
	}

	/** @return the plugin logger */
	Logger logger() {
		return logger;
	}

	/** @return the plugin's data directory */
	Path dataDirectory() {
		return dataDirectory;
	}

	/** @return the channel identifier, or {@code null} before initialisation */
	ChannelIdentifier channel() {
		return channel;
	}

	/** @return the player-data coordinator, or {@code null} when synchronisation is off */
	PlayerDataCoordinator playerData() {
		return playerData;
	}

	/** @return the world-time coordinator, or {@code null} when time sync is off */
	TimeSyncCoordinator timeSync() {
		return timeSync;
	}

	/**
	 * Tests whether the configuration file exists, for the startup message.
	 *
	 * @return {@code true} when the file is present
	 */
	boolean configurationExists() {
		return Files.exists(dataDirectory.resolve("opendonut.yml"));
	}

	/** @return the player-facing name of a UUID, for messages; falls back to the UUID itself */
	String nameOf(UUID playerId) {
		return server.getPlayer(playerId).map(Player::getUsername).orElse(playerId.toString());
	}
}
