package net.opendonut.common.timesync;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.opendonut.common.model.TimeSettings;

/**
 * The proxy's half of world-clock synchronisation: which backend is the authority, its last reported reading,
 * and the estimate of the clock right now.
 *
 * <h2>Why an estimate and not a query</h2>
 * The bridge only has a channel while a player is on a backend, so the proxy cannot ask the authority for its
 * clock whenever it wants to — an empty authority is unreachable. Instead the authority <em>pushes</em> a
 * reading every {@code time.report-interval-seconds}, and between reports the proxy advances the reading by
 * wall clock at the reported rate. A clock that is not being changed by hand advances at exactly that rate,
 * so the estimate is exact until somebody runs {@code /time} somewhere; the next report then snaps it back,
 * because the authority's reading always wins. See {@code docs/plan-time-sync.md}.
 *
 * <h2>Authority resolution</h2>
 * With {@code time.authority: auto} the authority is the first backend whose {@code HELLO} the proxy accepts —
 * resolved once, for the lifetime of the coordinator, via {@link #onBackendVerified(String, long)}. An
 * explicit id is taken from the configuration at construction; {@code ConfigValidator} rejects an id that names
 * no configured server.
 *
 * <p>All methods are synchronized: the proxy calls them from Velocity event threads, and a reload replaces the
 * coordinator wholesale rather than mutating it.
 */
public final class TimeSyncCoordinator {

	/** The Minecraft game tick rate, in ticks per second: the rate a clock advances at by default. */
	public static final double TICKS_PER_SECOND = 20.0;

	/** A reading of a world clock. */
	public record ClockReading(String clock, long totalTicks, float rate, boolean paused) {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if the clock id is missing
		 */
		public ClockReading {
			Objects.requireNonNull(clock, "clock");
		}
	}

	/** What the proxy concluded about a {@code TIME_REPORT}. */
	public record ReportVerdict(boolean accepted, String detail) {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if the detail is missing
		 */
		public ReportVerdict {
			Objects.requireNonNull(detail, "detail");
		}
	}

	private final TimeSettings settings;

	/** The resolved authority id, or {@code null} while {@code auto} has not seen a verified backend yet. */
	private String authorityId;

	private ClockReading lastReading;
	private long lastReportWallMillis;
	private final Set<String> warnedNonAuthority = new HashSet<>();

	/**
	 * Creates the coordinator.
	 *
	 * @param settings the validated {@code time} settings; must be enabled
	 */
	public TimeSyncCoordinator(TimeSettings settings) {
		this.settings = Objects.requireNonNull(settings, "settings");
		this.authorityId = settings.authorityIsAuto() ? null : settings.explicitAuthority();
	}

	/**
	 * Records that a backend's {@code HELLO} was accepted. With {@code auto}, the first verified backend becomes
	 * the authority.
	 *
	 * @param serverId the verified backend's id
	 * @param nowMillis the current wall clock, unused but kept for symmetry with the other callbacks
	 */
	public synchronized void onBackendVerified(String serverId, long nowMillis) {
		if (settings.authorityIsAuto() && authorityId == null) {
			authorityId = serverId;
		}
	}

	/**
	 * Handles a {@code TIME_REPORT} from a backend. Only the authority's reading is stored; reports from other
	 * backends are refused (the first one per backend with a detail, the rest silently), so a misconfigured
	 * backend cannot move the network clock.
	 *
	 * @param serverId the reporting backend's id
	 * @param reading the reading
	 * @param nowMillis the current wall clock, stamped as the reading's arrival time
	 * @return the verdict, for logging
	 */
	public synchronized ReportVerdict onReport(String serverId, ClockReading reading, long nowMillis) {
		if (authorityId == null) {
			return new ReportVerdict(false, "no authority has been resolved yet, so the report is parked");
		}
		if (!authorityId.equals(serverId)) {
			if (warnedNonAuthority.add(serverId)) {
				return new ReportVerdict(false,
						"'" + serverId + "' is not the time authority ('" + authorityId + "'); ignoring its "
								+ "clock reading");
			}
			return new ReportVerdict(false, "");
		}
		if (!settings.clockId().equals(reading.clock())) {
			return new ReportVerdict(false, "the authority reported clock '" + reading.clock()
					+ "' but this proxy synchronises '" + settings.clockId() + "'; ignoring the reading");
		}
		lastReading = reading;
		lastReportWallMillis = nowMillis;
		return new ReportVerdict(true, "stored " + reading.totalTicks() + " tick(s) of clock " + reading.clock()
				+ " as the base reading");
	}

	/**
	 * The best estimate of the authority's clock at a given moment: the last reading advanced by the elapsed
	 * wall time at the reported rate, or unchanged while the clock is paused.
	 *
	 * @param nowMillis the moment to estimate for
	 * @return the estimate, or empty when no report has been accepted yet
	 */
	public synchronized Optional<ClockReading> estimateAt(long nowMillis) {
		if (lastReading == null) {
			return Optional.empty();
		}
		long elapsedMillis = Math.max(0L, nowMillis - lastReportWallMillis);
		long advance = lastReading.paused()
				? 0L
				: Math.round(elapsedMillis / 1000.0 * TICKS_PER_SECOND * lastReading.rate());
		return Optional.of(new ClockReading(
				lastReading.clock(), lastReading.totalTicks() + advance, lastReading.rate(),
				lastReading.paused()));
	}

	/**
	 * Whether a backend is the resolved authority.
	 *
	 * @param serverId the backend's id
	 * @return {@code true} when the backend is the authority
	 */
	public synchronized boolean isAuthority(String serverId) {
		return authorityId != null && authorityId.equals(serverId);
	}

	/** @return the resolved authority id, or {@code null} when not resolved yet */
	public synchronized String authorityId() {
		return authorityId;
	}

	/** @return the last accepted reading, or empty when none has arrived yet */
	public synchronized Optional<ClockReading> lastReading() {
		return Optional.ofNullable(lastReading);
	}

	/**
	 * Renders the coordinator for a log line or a status command.
	 *
	 * @return a one-line description
	 */
	public synchronized String describe() {
		return "time sync: authority " + (authorityId == null ? "(not resolved yet)" : "'" + authorityId + "'")
				+ ", " + settings.describe()
				+ (lastReading == null
						? ", no report received yet"
						: ", last report " + lastReading.totalTicks() + " tick(s) of " + lastReading.clock());
	}
}
