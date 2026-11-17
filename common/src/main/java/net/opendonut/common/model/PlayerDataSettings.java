package net.opendonut.common.model;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The {@code player-data} section: whether a player's belongings follow them across a boundary, and where they
 * are kept while they are away.
 *
 * <h2>The rule this configuration exists to serve</h2>
 * At most one backend may write a player's data at any moment. That is enforced with a lease in the store, not
 * by this record, but two of the settings here decide what happens when the lease cannot be moved: refusing the
 * transfer (the default) or proceeding with the last committed snapshot (which can lose a few minutes of play).
 *
 * <h2>What Phase 1 implements, and what it refuses</h2>
 * This version stores snapshots in a local SQLite file next to the proxy's data directory and synchronises the
 * <em>session</em> scope: inventory, ender chest, experience, health, hunger, effects and a few related fields.
 * The other storages and scopes are accepted by the schema and rejected at validation time with an explanation,
 * because a configuration that asks for something unimplemented must fail loudly rather than half-work.
 *
 * @param enabled whether player data is synchronised at all; {@code false} leaves the system exactly as it was
 *                before this feature existed
 * @param storage which store to use; only {@value #STORAGE_SQLITE} is implemented
 * @param file the SQLite database file, resolved against the proxy's data directory when not absolute
 * @param syncIntervalSeconds how often a backend saves while a player is online; {@code 0} disables periodic
 *                            saves, leaving saves to transfers and disconnects. Bounds what a crash can lose
 * @param leaseSeconds how long a lease survives a backend that stops responding; after this the next backend to
 *                     see the player may take it over, which is what stops a crash from locking a player out
 * @param onFlushFailure what the proxy does when a backend cannot hand over a fresh snapshot before a transfer
 * @param scope how much of the player travels; only {@link SyncScope#SESSION} is implemented
 * @param keepHistory how many previous snapshots to keep per player for recovery; {@code 0} keeps none
 * @param maxPayloadBytes the largest snapshot, after compression, that may be sent in one frame. A snapshot
 *                        larger than this refuses the transfer with an explanation rather than being truncated
 * @param flushTimeoutSeconds how long the proxy waits for a source backend to save a player's data before
 *                            the transfer is refused. Must stay below {@code transfer.max-pending-seconds},
 *                            which is the source backend's own timeout for the same transfer
 */
public record PlayerDataSettings(
		boolean enabled,
		String storage,
		String file,
		int syncIntervalSeconds,
		int leaseSeconds,
		OnFlushFailure onFlushFailure,
		SyncScope scope,
		int keepHistory,
		int maxPayloadBytes,
		int flushTimeoutSeconds) {

	/** The only storage implemented in this version. */
	public static final String STORAGE_SQLITE = "sqlite";

	/** The default database file name, relative to the proxy's data directory. */
	public static final String DEFAULT_FILE = "playerdata.db";

	/**
	 * The default snapshot ceiling.
	 *
	 * <p>Derived from the transport rather than chosen: a bridge frame carries at most
	 * {@link net.opendonut.common.bridge.BridgeCodec#MAX_PAYLOAD_BYTES} (64 KiB) in total, and a frame also
	 * carries the envelope, the nonce, the timestamp and the MAC. 48 KiB leaves comfortable room for those and
	 * still fits an ordinary inventory several times over. Raising this above ~60 KiB requires chunking, which
	 * is planned but not implemented.
	 */
	public static final int DEFAULT_MAX_PAYLOAD_BYTES = 48 * 1024;

	/** The upper bound that can be configured, short of implementing chunking. */
	public static final int MAX_PAYLOAD_BYTES_LIMIT = 60 * 1024;

	// Declared before DEFAULT on purpose: the compact constructor reads it, and static fields initialise
	// in declaration order, so a pattern declared after DEFAULT would still be null when DEFAULT is built.
	private static final Pattern FILE_NAME = Pattern.compile("[^\\\\/:*?\"<>|]+");

	/** Disabled by default: a network that upgrades to this build keeps behaving exactly as it did. */
	public static final PlayerDataSettings DEFAULT = new PlayerDataSettings(
			false,
			STORAGE_SQLITE,
			DEFAULT_FILE,
			60,
			60,
			OnFlushFailure.REFUSE_TRANSFER,
			SyncScope.SESSION,
			20,
			DEFAULT_MAX_PAYLOAD_BYTES,
			10);

	/**
	 * What to do when a backend cannot hand over a fresh snapshot in time.
	 *
	 * <p>Both options are defensible, and the default is the one that cannot lose anything.
	 */
	public enum OnFlushFailure {

		/**
		 * Cancel the transfer and leave the player where they are. They keep everything they own; they simply
		 * do not move this time. The default.
		 */
		REFUSE_TRANSFER,

		/**
		 * Move the player anyway, using the last snapshot the store has. Faster and more forgiving, at the cost
		 * of whatever happened since that snapshot — up to {@code sync-interval-seconds} of play.
		 */
		PROCEED_WITH_LAST_KNOWN;

		/**
		 * Parses the configuration value.
		 *
		 * @param raw the raw text
		 * @return the value, or empty when it is not recognised
		 */
		public static Optional<OnFlushFailure> parse(String raw) {
			if (raw == null) {
				return Optional.empty();
			}
			return switch (raw.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
				case "refuse-transfer", "refuse", "cancel" -> Optional.of(REFUSE_TRANSFER);
				case "proceed-with-last-known", "proceed", "last-known" -> Optional.of(PROCEED_WITH_LAST_KNOWN);
				default -> Optional.empty();
			};
		}
	}

	/**
	 * How much of a player travels with them.
	 */
	public enum SyncScope {

		/** Inventory, ender chest, experience, vitals, effects and equipment. Implemented. */
		SESSION,

		/** Adds long-term progress. Not implemented in this version. */
		PROGRESS,

		/** Everything the player file holds except position, dimension and server-owned state. Not implemented. */
		EVERYTHING;

		/**
		 * Parses the configuration value.
		 *
		 * @param raw the raw text
		 * @return the value, or empty when it is not recognised
		 */
		public static Optional<SyncScope> parse(String raw) {
			if (raw == null) {
				return Optional.empty();
			}
			return switch (raw.trim().toLowerCase(Locale.ROOT)) {
				case "session" -> Optional.of(SESSION);
				case "progress" -> Optional.of(PROGRESS);
				case "everything" -> Optional.of(EVERYTHING);
				default -> Optional.empty();
			};
		}
	}

	/**
	 * Validates the settings.
	 *
	 * @throws IllegalArgumentException if a value is outside its supported range or names an unimplemented
	 *                                  option; the message is written to be shown to an administrator
	 */
	public PlayerDataSettings {
		Objects.requireNonNull(storage, "storage");
		Objects.requireNonNull(file, "file");
		Objects.requireNonNull(onFlushFailure, "onFlushFailure");
		Objects.requireNonNull(scope, "scope");

		String normalisedStorage = storage.trim().toLowerCase(Locale.ROOT);
		if (!normalisedStorage.equals(STORAGE_SQLITE)) {
			throw new IllegalArgumentException("player-data.storage '" + storage + "' is not implemented yet; "
					+ "this version supports only '" + STORAGE_SQLITE + "'. PostgreSQL and MariaDB are planned "
					+ "and documented in docs/plan-player-data-sync.md.");
		}
		if (file.isBlank() || !FILE_NAME.matcher(file).matches()) {
			throw new IllegalArgumentException("player-data.file must be a plain file name, got '" + file + "'");
		}
		if (syncIntervalSeconds < 0 || syncIntervalSeconds > 3600) {
			throw new IllegalArgumentException(	
					"player-data.sync-interval-seconds must be between 0 and 3600, got " + syncIntervalSeconds);
		}
		if (leaseSeconds < 10 || leaseSeconds > 3600) {
			throw new IllegalArgumentException(
					"player-data.lease-seconds must be between 10 and 3600, got " + leaseSeconds);
		}
		if (keepHistory < 0 || keepHistory > 1000) {
			throw new IllegalArgumentException("player-data.keep-history must be between 0 and 1000, got "
					+ keepHistory);
		}
		if (maxPayloadBytes < 1024 || maxPayloadBytes > MAX_PAYLOAD_BYTES_LIMIT) {
			throw new IllegalArgumentException("player-data.max-payload-bytes must be between 1024 and "
					+ MAX_PAYLOAD_BYTES_LIMIT + " (chunking is not implemented yet, so a snapshot has to fit one "
					+ "frame), got " + maxPayloadBytes);
		}
		if (flushTimeoutSeconds < 1 || flushTimeoutSeconds > 60) {
			throw new IllegalArgumentException("player-data.flush-timeout-seconds must be between 1 and 60, got "
					+ flushTimeoutSeconds + "; it must also stay below transfer.max-pending-seconds, the "
					+ "source backend's own timeout for the same transfer");
		}
		if (scope != SyncScope.SESSION) {
			throw new IllegalArgumentException("player-data.scope '" + scope.name().toLowerCase(Locale.ROOT)
					+ "' is not implemented yet; this version synchronises '" + SyncScope.SESSION.name()
							.toLowerCase(Locale.ROOT) + "' data only. See docs/plan-player-data-sync.md for the "
							+ "scope levels.");
		}
	}

	/**
	 * The configured sync interval in milliseconds.
	 *
	 * @return the interval, or {@code 0} when periodic saving is disabled
	 */
	public long syncIntervalMillis() {
		return syncIntervalSeconds * 1000L;
	}

	/**
	 * The configured lease duration in milliseconds.
	 *
	 * @return the duration
	 */
	public long leaseMillis() {
		return leaseSeconds * 1000L;
	}

	/**
	 * The configured flush timeout in milliseconds.
	 *
	 * @return the timeout
	 */
	public long flushTimeoutMillis() {
		return flushTimeoutSeconds * 1000L;
	}

	/**
	 * Tests whether the proxy may move a player whose data could not be flushed.
	 *
	 * @return {@code true} for {@link OnFlushFailure#PROCEED_WITH_LAST_KNOWN}
	 */
	public boolean mayProceedWithoutFreshData() {
		return onFlushFailure == OnFlushFailure.PROCEED_WITH_LAST_KNOWN;
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
		return storage + " (" + file + "), scope " + scope.name().toLowerCase(Locale.ROOT)
				+ ", lease " + leaseSeconds + " s, saves every " + (syncIntervalSeconds == 0
						? "transfer/disconnect only"
						: syncIntervalSeconds + " s")
				+ ", on flush failure " + onFlushFailure.name().toLowerCase(Locale.ROOT).replace('_', '-')
				+ ", flush timeout " + flushTimeoutSeconds + " s"
				+ ", history " + keepHistory + ", snapshot limit " + maxPayloadBytes + " bytes";
	}
}
