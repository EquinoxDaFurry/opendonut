package net.opendonut.common.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The {@code time} section: whether the backends' world clocks are kept in step, and which backend is the
 * authority they converge on.
 *
 * <h2>The rule this configuration exists to serve</h2>
 * Exactly one backend is the time authority; every other backend is corrected towards the authority's clock
 * when it announces itself, when a player arrives on it, and periodically. The proxy caches the authority's
 * last report and extrapolates it by wall clock between reports, so an authority with no players connected
 * (and therefore no bridge channel to report on) still yields a good estimate. See
 * {@code docs/plan-time-sync.md} for the full design.
 *
 * <h2>What Phase 1 implements, and what it refuses</h2>
 * Phase 1 synchronises the {@code totalTicks} of one configured world clock (the overworld by default).
 * {@code rate} and {@code paused} travel in the wire messages so a later phase can apply them without a
 * protocol change, but this version deliberately does not act on them. Weather is not synchronised at all.
 *
 * @param enabled whether world time is synchronised at all; {@code false} leaves every backend on its own
 *                clock, exactly as before this feature existed
 * @param authority {@code auto} (the first backend the proxy verifies) or the id of a configured server
 * @param clock the registry id of the world clock to synchronise, for example {@code minecraft:overworld}
 * @param reportIntervalSeconds how often a backend reports its clock reading to the proxy; must be at
 *                              least 1
 * @param syncIntervalSeconds how often the proxy pushes the estimated time to every non-authority backend;
 *                            {@code 0} disables the periodic push, leaving correction to hello and arrival
 * @param logDivergenceTicks a correction that moves a backend's clock by more than this many ticks is
 *                           logged at warning level; {@code 0} logs every correction
 */
public record TimeSettings(
		boolean enabled,
		String authority,
		String clock,
		int reportIntervalSeconds,
		int syncIntervalSeconds,
		int logDivergenceTicks) {

	/** The authority spelling that picks the first backend the proxy verifies. */
	public static final String AUTHORITY_AUTO = "auto";

	/** The default clock: the overworld, which in 26.3 is also the nether's clock. */
	public static final String DEFAULT_CLOCK = "minecraft:overworld";

	// Identifier syntax in 26.3: namespace [a-z0-9_.-]+, path [a-z0-9_/.-]+, either side optional with
	// defaults. The common module has no Minecraft dependency, so the pattern mirrors it instead of using it.
	private static final Pattern CLOCK_ID = Pattern.compile(
			"([a-z0-9_.-]+:)?[a-z0-9_/.-]+");

	/** Disabled by default: a network that upgrades to this build keeps behaving exactly as it did. */
	public static final TimeSettings DEFAULT = new TimeSettings(
			false,
			AUTHORITY_AUTO,
			DEFAULT_CLOCK,
			5,
			30,
			20);

	/**
	 * Validates the settings.
	 *
	 * @throws IllegalArgumentException if a value is outside its supported range; the message is written to be
	 *                                  shown to an administrator
	 */
	public TimeSettings {
		Objects.requireNonNull(authority, "authority");
		Objects.requireNonNull(clock, "clock");

		String normalisedAuthority = authority.trim().toLowerCase(Locale.ROOT);
		if (normalisedAuthority.isEmpty()) {
			throw new IllegalArgumentException(
					"time.authority must be '" + AUTHORITY_AUTO + "' or a server id, got an empty value");
		}
		if (!normalisedAuthority.equals(AUTHORITY_AUTO)
				&& !normalisedAuthority.matches("[a-z0-9_.-]+")) {
			throw new IllegalArgumentException("time.authority must be '" + AUTHORITY_AUTO
					+ "' or a server id (lowercase letters, digits, '_', '-', '.'), got '" + authority + "'");
		}
		String normalisedClock = clock.trim().toLowerCase(Locale.ROOT);
		if (!CLOCK_ID.matcher(normalisedClock).matches()) {
			throw new IllegalArgumentException("time.clock must be a registry id such as '"
					+ DEFAULT_CLOCK + "', got '" + clock + "'");
		}
		if (reportIntervalSeconds < 1 || reportIntervalSeconds > 3600) {
			throw new IllegalArgumentException(
					"time.report-interval-seconds must be between 1 and 3600, got " + reportIntervalSeconds);
		}
		if (syncIntervalSeconds < 0 || syncIntervalSeconds > 3600) {
			throw new IllegalArgumentException(
					"time.sync-interval-seconds must be between 0 and 3600 (0 disables the periodic push), got "
							+ syncIntervalSeconds);
		}
		if (logDivergenceTicks < 0 || logDivergenceTicks > 1_000_000) {
			throw new IllegalArgumentException("time.log-divergence-ticks must be between 0 and 1000000, got "
					+ logDivergenceTicks);
		}
	}

	/** @return {@code true} when the authority is the first backend the proxy verifies */
	public boolean authorityIsAuto() {
		return authority.trim().equalsIgnoreCase(AUTHORITY_AUTO);
	}

	/** @return the configured explicit authority id, lower-cased */
	public String explicitAuthority() {
		return authority.trim().toLowerCase(Locale.ROOT);
	}

	/** @return the configured clock id, lower-cased */
	public String clockId() {
		return clock.trim().toLowerCase(Locale.ROOT);
	}

	/** @return the report interval in milliseconds */
	public long reportIntervalMillis() {
		return reportIntervalSeconds * 1000L;
	}

	/** @return the periodic push interval in milliseconds, or {@code 0} when disabled */
	public long syncIntervalMillis() {
		return syncIntervalSeconds * 1000L;
	}

	/**
	 * Renders the settings for a log line or a validation report.
	 *
	 * @return a one-line description
	 */
	public String describe() {
		if (!enabled) {
			return "disabled";
		}
		return "authority " + explicitAuthority() + ", clock " + clockId() + ", backends report every "
				+ reportIntervalSeconds + " s, proxy pushes every "
				+ (syncIntervalSeconds == 0 ? "arrival only" : syncIntervalSeconds + " s")
				+ ", divergence log threshold " + logDivergenceTicks + " tick(s)";
	}
}
