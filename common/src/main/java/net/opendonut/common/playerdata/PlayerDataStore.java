package net.opendonut.common.playerdata;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a player's data lives while they are between servers.
 *
 * <h2>The two rules every implementation must keep</h2>
 * <ol>
 *   <li><strong>A lease.</strong> At most one server may write a player's data at a time. The lease is what
 *       prevents the failure mode this whole feature exists to avoid: two backends that both believe they own a
 *       player's inventory will both save it, and the later save wins, which is how a player ends up with two
 *       copies of the same sword.</li>
 *   <li><strong>Compare-and-set.</strong> A write states the revision it was based on. If the store has moved
 *       on, the write is refused rather than applied, and the caller has to deal with it — which it does by
 *       refusing the transfer and leaving the player where they were.</li>
 * </ol>
 *
 * <p>A lease also has to be able to expire, or a backend that crashes locks its player out of the network
 * forever. Expiry is deliberate: a takeover of an expired lease is the recovery path, and implementations
 * report it so it can be logged.
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link InMemoryPlayerDataStore} — the reference implementation: obviously correct, no dependencies, used
 *       by the tests and by the contract they run against every other implementation.</li>
 *   <li>{@link SqlitePlayerDataStore} — the production one for a single machine. It lives in this module but is
 *       only opened by the proxy plugin, which bundles the JDBC driver.</li>
 * </ul>
 *
 * <p>Implementations must be safe for concurrent use: Velocity dispatches events on several threads, so two
 * players can be saving at the same time.
 */
public interface PlayerDataStore extends AutoCloseable {

	/**
	 * A snapshot as the store holds it.
	 *
	 * @param revision the revision this snapshot was stored as; a writer must quote it as its base
	 * @param snapshot the snapshot itself
	 * @param updatedAtMillis when it was written
	 * @param updatedBy which server wrote it
	 */
	record StoredSnapshot(long revision, PlayerDataSnapshot snapshot, long updatedAtMillis, String updatedBy) {
	}

	/**
	 * Advancement progress as the store holds it: the raw bytes of the player's
	 * {@code world/advancements/<uuid>.json} file.
	 *
	 * @param revision the revision this payload was stored as; a writer must quote it as its base
	 * @param payload the raw advancements JSON bytes
	 * @param updatedAtMillis when it was written
	 * @param updatedBy which server wrote it
	 */
	record StoredAdvancements(long revision, byte[] payload, long updatedAtMillis, String updatedBy) {
	}

	/**
	 * The outcome of a write.
	 *
	 * @param accepted whether the snapshot was stored
	 * @param newRevision the revision after the write, or the current revision when it was refused
	 * @param detail an explanation, for the log and for the backend that will be told about it
	 */
	record SaveOutcome(boolean accepted, long newRevision, String detail) {

		/**
		 * Builds an accepted outcome.
		 *
		 * @param newRevision the revision the write produced
		 * @return the outcome
		 */
		public static SaveOutcome accepted(long newRevision) {
			return new SaveOutcome(true, newRevision, "stored as revision " + newRevision);
		}

		/**
		 * Builds a refusal.
		 *
		 * @param currentRevision the revision the store holds
		 * @param detail why the write was refused
		 * @return the outcome
		 */
		public static SaveOutcome refused(long currentRevision, String detail) {
			return new SaveOutcome(false, currentRevision, detail);
		}
	}

	/**
	 * Who holds a player's lease.
	 *
	 * @param ownerServer the server that may write
	 * @param revision the revision the owner is expected to write from
	 * @param expiresAtMillis when the lease stops being valid
	 */
	record LeaseState(String ownerServer, long revision, long expiresAtMillis) {

		/**
		 * Tests whether the lease has expired.
		 *
		 * @param nowMillis the current time
		 * @return {@code true} when the lease is no longer valid
		 */
		public boolean isExpired(long nowMillis) {
			return expiresAtMillis <= nowMillis;
		}
	}

	/**
	 * The outcome of trying to take a lease.
	 *
	 * @param acquired whether the caller now holds it
	 * @param ownerServer who holds it after the attempt
	 * @param revision the revision recorded with the lease
	 * @param stolen whether the lease was taken from an expired holder
	 * @param detail an explanation, for the log
	 */
	record LeaseOutcome(boolean acquired, String ownerServer, long revision, boolean stolen, String detail) {
	}

	/**
	 * Reads the stored snapshot for a player.
	 *
	 * @param playerId the player
	 * @return the snapshot, or empty when nothing has ever been stored for them
	 */
	Optional<StoredSnapshot> load(UUID playerId);

	/**
	 * Writes a snapshot, if and only if the store still holds the revision the writer based it on.
	 *
	 * @param playerId the player
	 * @param baseRevision the revision the caller read (0 for a player with nothing stored)
	 * @param snapshot the snapshot to store
	 * @param serverId the server writing, recorded for the log
	 * @param nowMillis the current time
	 * @param keepHistory how many previous snapshots to keep for recovery
	 * @return the outcome
	 */
	SaveOutcome save(UUID playerId, long baseRevision, PlayerDataSnapshot snapshot, String serverId,
			long nowMillis, int keepHistory);

	/**
	 * Takes or refreshes the lease for a player.
	 *
	 * @param playerId the player
	 * @param serverId the server that wants it
	 * @param nowMillis the current time
	 * @param leaseMillis how long the lease should be valid for
	 * @return the outcome
	 */
	LeaseOutcome acquireLease(UUID playerId, String serverId, long nowMillis, long leaseMillis);

	/**
	 * Reads who holds a player's lease.
	 *
	 * @param playerId the player
	 * @return the lease, or empty when there is none
	 */
	Optional<LeaseState> lease(UUID playerId);

	/**
	 * Gives up a lease, so the next server can take it immediately instead of waiting for expiry.
	 *
	 * @param playerId the player
	 * @param serverId the server releasing it; a release from anybody else is ignored
	 * @return {@code true} when a lease held by that server was released
	 */
	boolean releaseLease(UUID playerId, String serverId);

	/**
	 * The most recent snapshots for a player, newest first, for recovery.
	 *
	 * @param playerId the player
	 * @param limit the most entries to return
	 * @return the history, possibly empty
	 */
	List<StoredSnapshot> history(UUID playerId, int limit);

	/**
	 * Reads the stored advancement progress for a player.
	 *
	 * <p>Advancements live in their own per-player file on each backend, not in the player data, so the
	 * store keeps them in their own table with their own revision counter; the lease on the player data is
	 * what serialises writers, the same as for snapshots.
	 *
	 * @param playerId the player
	 * @return the payload, or empty when nothing has ever been stored for them
	 */
	Optional<StoredAdvancements> loadAdvancements(UUID playerId);

	/**
	 * Writes advancement progress, if and only if the store still holds the revision the writer based it on.
	 *
	 * @param playerId the player
	 * @param baseRevision the revision the caller read (0 for a player with nothing stored)
	 * @param payload the raw advancements JSON bytes
	 * @param serverId the server writing, recorded for the log
	 * @param nowMillis the current time
	 * @return the outcome
	 */
	SaveOutcome saveAdvancements(UUID playerId, long baseRevision, byte[] payload, String serverId,
			long nowMillis);

	/**
	 * A one-line description of the store, for the status command.
	 *
	 * @return a description
	 */
	String describe();

	@Override
	void close();
}
