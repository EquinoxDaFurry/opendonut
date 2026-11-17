package net.opendonut.fabric;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.ClockInstance;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;

import org.slf4j.Logger;

import net.opendonut.common.bridge.BridgeMessage;
import net.opendonut.common.model.OpenDonutConfig;
import net.opendonut.common.model.TimeSettings;

/**
 * The backend's half of world-clock synchronisation: report this server's clock to the proxy, and apply the
 * {@code TIME_SET} corrections the proxy sends back.
 *
 * <h2>Reporting</h2>
 * Every backend with {@code time.enabled} sends a {@code TIME_REPORT} right after its {@code HELLO_ACK} and then
 * every {@code time.report-interval-seconds}, through whichever player connection is carrying the frame — the
 * bridge has no connection of its own, so a backend with no players simply cannot report, and the proxy's
 * wall-clock estimate stands in until it can. The proxy only trusts the authority's reports; this side sends
 * unconditionally, which is what makes {@code authority: auto} work without telling each backend who won.
 *
 * <h2>Applying</h2>
 * A {@code TIME_SET} sets the configured clock's {@code totalTicks} on this server's {@code ServerClockManager}
 * and broadcasts the manager's full-sync packet, so every connected player sees the correction immediately and
 * the arriving player (whose {@code TIME_SET} arrives with their {@code PLACE_PLAYER}) lands at the right hour.
 * Phase 1 applies {@code totalTicks} only; {@code rate} and {@code paused} are carried on the wire for a later
 * phase and are logged, not applied. See {@code docs/plan-time-sync.md}.
 *
 * <p>Everything here runs on the Minecraft server thread: the tick event and the bridge's message handler both
 * do, so the plain fields are safe.
 */
final class WorldTimeSync {

	private final MinecraftServer server;
	private final TimeSettings settings;
	private final BackendBridge bridge;
	private final Logger logger;

	/** The resolved holder of the configured clock, or {@code null} while unresolved or missing. */
	private Holder<WorldClock> clockHolder;
	private boolean clockLookupFailed;

	private long lastReportMillis;
	private long reportsSent;
	private long setsApplied;
	private long setsIgnored;

	/**
	 * Creates the synchroniser when the configuration enables it.
	 *
	 * @param server the running server
	 * @param config the configuration
	 * @param bridge the bridge, or {@code null} when it could not be set up
	 * @param logger the server logger
	 * @return the synchroniser, or empty when time sync is off or the bridge is unavailable
	 */
	static Optional<WorldTimeSync> create(MinecraftServer server, OpenDonutConfig config, BackendBridge bridge,
			Logger logger) {
		if (!config.time().enabled()) {
			return Optional.empty();
		}
		if (bridge == null) {
			logger.error("OpenDonut: time.enabled is true but the bridge is not up, so world time cannot be "
					+ "synchronised. Fix the bridge findings above, or set time.enabled: false.");
			return Optional.empty();
		}
		WorldTimeSync sync = new WorldTimeSync(server, config.time(), bridge, logger);
		logger.info("OpenDonut: world time sync ready on this backend — {}; reports its clock every {} s and "
				+ "applies the proxy's corrections", sync.settings.describe(),
				sync.settings.reportIntervalSeconds());
		return Optional.of(sync);
	}

	private WorldTimeSync(MinecraftServer server, TimeSettings settings, BackendBridge bridge, Logger logger) {
		this.server = server;
		this.settings = settings;
		this.bridge = bridge;
		this.logger = logger;
	}

	/**
	 * The proxy accepted this backend: report the clock now, so the proxy has a base reading immediately instead
	 * of waiting for the first interval.
	 */
	void onHelloAck() {
		sendReport("after HELLO_ACK");
	}

	/**
	 * Periodic report, at {@code time.report-interval-seconds}.
	 *
	 * @param server the running server
	 */
	void tick(MinecraftServer server) {
		long now = System.currentTimeMillis();
		if (now - lastReportMillis < settings.reportIntervalMillis()) {
			return;
		}
		lastReportMillis = now;
		sendReport("periodic");
	}

	/**
	 * The proxy set the clock: apply the reading and resynchronise every connected player.
	 *
	 * @param set the instruction
	 */
	void onTimeSet(BridgeMessage.TimeSet set) {
		if (!settings.clockId().equals(set.clock())) {
			setsIgnored++;
			logger.warn("OpenDonut: the proxy set clock '{}' but this backend synchronises '{}'; ignoring it",
					set.clock(), settings.clockId());
			return;
		}
		Holder<WorldClock> holder = resolveClockHolder();
		if (holder == null) {
			setsIgnored++;
			return;
		}
		ServerClockManager clocks = server.clockManager();
		long before = clocks.getInstance(holder).totalTicks();
		clocks.setTotalTicks(holder, set.totalTicks());
		setsApplied++;
		long delta = set.totalTicks() - before;
		String detail = "set clock " + set.clock() + " to " + set.totalTicks() + " tick(s) (was " + before
				+ ", " + (delta >= 0 ? "+" : "") + delta + ") because of '" + set.reason() + "'";
		if (Math.abs(delta) > settings.logDivergenceTicks()) {
			logger.warn("OpenDonut: applied the proxy's time correction: {}", detail);
		} else {
			logger.info("OpenDonut: applied the proxy's time correction: {}", detail);
		}
		if (set.rate() != 1.0f || set.paused()) {
			logger.debug("OpenDonut: the TIME_SET also carried rate={} paused={}; Phase 1 applies totalTicks "
					+ "only", set.rate(), set.paused());
		}
		// Resynchronise every connected player so the correction is visible immediately.
		var packet = clocks.createFullSyncPacket();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.connection.send(packet);
		}
	}

	/**
	 * Sends a {@code TIME_REPORT} with the current reading of the configured clock, through any online player.
	 *
	 * @param why why the report is sent, for the log
	 */
	private void sendReport(String why) {
		Holder<WorldClock> holder = resolveClockHolder();
		if (holder == null) {
			return;
		}
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty()) {
			// No player, no channel: the proxy's wall-clock estimate covers the gap until someone joins.
			return;
		}
		ClockInstance instance = server.clockManager().getInstance(holder);
		BridgeMessage.TimeReport report = new BridgeMessage.TimeReport(
				settings.clockId(), instance.totalTicks(), instance.rate(), instance.isPaused());
		if (bridge.send(players.get(0), report)) {
			reportsSent++;
			logger.debug("OpenDonut: reported clock {} at {} tick(s) to the proxy ({})",
					settings.clockId(), instance.totalTicks(), why);
		}
	}

	/**
	 * Resolves the configured clock in the world-clock registry, once. A clock id that does not exist (a
	 * datapack removed, a typo the config validator could not catch because the registry is only known at
	 * runtime) is reported once and then ignored.
	 *
	 * @return the holder, or {@code null} when the clock is unknown on this server
	 */
	private Holder<WorldClock> resolveClockHolder() {
		if (clockHolder != null || clockLookupFailed) {
			return clockHolder;
		}
		Identifier id = Identifier.tryParse(settings.clockId());
		if (id == null) {
			clockLookupFailed = true;
			logger.error("OpenDonut: time.clock '{}' is not a valid registry id; time sync is OFF on this "
					+ "backend", settings.clockId());
			return null;
		}
		Registry<WorldClock> registry = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK);
		Optional<Holder.Reference<WorldClock>> found = registry.get(id);
		if (found.isEmpty()) {
			clockLookupFailed = true;
			logger.error("OpenDonut: this server has no world clock named '{}' (vanilla provides "
					+ "'minecraft:overworld' and 'minecraft:the_end'); time sync is OFF on this backend",
					settings.clockId());
			return null;
		}
		clockHolder = found.get();
		return clockHolder;
	}

	/**
	 * Renders the synchroniser for a status command.
	 *
	 * @return a one-line description
	 */
	String describe() {
		return settings.describe() + "; " + reportsSent + " report(s) sent, " + setsApplied
				+ " correction(s) applied, " + setsIgnored + " ignored";
	}
}
