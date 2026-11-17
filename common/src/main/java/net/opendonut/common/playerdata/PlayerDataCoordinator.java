package net.opendonut.common.playerdata;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.opendonut.common.model.PlayerDataSettings;

/**
 * The proxy-side authority for player data: it owns the {@link PlayerDataStore} and turns bridge messages into
 * the store operations that enforce the one invariant the whole feature rests on.
 *
 * <h2>The invariant</h2>
 * At most one backend is authoritative for a player's data at any moment, and a transfer only completes after
 * that authority has been handed over with the data committed. Concretely, in this class:
 *
 * <ol>
 *   <li>A <strong>save</strong> is accepted only when the writer holds the player's lease, and is applied as a
 *       compare-and-set on the revision, so a stale writer can never overwrite a newer snapshot.</li>
 *   <li>A <strong>load</strong> is answered only for the backend that holds (or takes over) the lease, and the
 *       answering itself acquires the lease, which is what moves authority to the destination after a
 *       transfer.</li>
 *   <li>A <strong>flush</strong> is a save the proxy demanded before a transfer; the transfer may only proceed
 *       once that save is committed, so the player is never in a state where nobody holds their data.</li>
 * </ol>
 *
 * <h2>Why this is separate from the Velocity plugin</h2>
 * Everything here is plain Java over the store, so the ordering rules — the part that decides whether items
 * get duplicated — are unit tested without a proxy, a Minecraft server or a network. The plugin wires events
 * to these methods and does no decision-making of its own.
 *
 * <p>The class is stateless apart from the store and the settings, and the store is safe for concurrent use,
 * so every method may be called from any thread.
 */
public final class PlayerDataCoordinator {

	/**
	 * The outcome of a {@link #handleSaveRequest}.
	 *
	 * @param accepted whether the snapshot was stored
	 * @param revision the revision the store holds after the attempt
	 * @param detail an explanation, for the log and for the backend
	 */
	public record SaveOutcome(boolean accepted, long revision, String detail) {
	}

	/**
	 * The outcome of a {@link #handleLoadRequest}.
	 *
	 * @param leaseAcquired whether the asking backend now holds the lease
	 * @param leaseStolen whether the lease was taken over from an expired holder
	 * @param present whether a snapshot exists for the player
	 * @param revision the stored revision (0 when none)
	 * @param envelope the encoded snapshot, empty when {@code present} is false
	 * @param detail an explanation, for the log and for the backend
	 */
	public record LoadOutcome(
			boolean leaseAcquired,
			boolean leaseStolen,
			boolean present,
			long revision,
			byte[] envelope,
			String detail) {

		/**
		 * Builds the outcome for a refused lease.
		 *
		 * @param holder who holds the lease
		 * @param revision the revision recorded with the lease
		 * @return the outcome
		 */
		public static LoadOutcome leaseRefused(String holder, long revision) {
			return new LoadOutcome(false, false, false, revision, new byte[0],
					"the lease is held by '" + holder + "' and has not expired; keeping the local data");
		}
	}

	private final PlayerDataStore store;
	private final PlayerDataSettings settings;

	/**
	 * Creates the coordinator.
	 *
	 * @param store the store to use
	 * @param settings the settings; the lease duration, history depth and payload limit are taken from here
	 */
	public PlayerDataCoordinator(PlayerDataStore store, PlayerDataSettings settings) {
		this.store = Objects.requireNonNull(store, "store");
		this.settings = Objects.requireNonNull(settings, "settings");
		if (!settings.enabled()) {
			throw new IllegalArgumentException("the coordinator must not be created when player-data is disabled");
		}
	}

	/** @return the underlying store */
	public PlayerDataStore store() {
		return store;
	}

	/** @return the settings in force */
	public PlayerDataSettings settings() {
		return settings;
	}

	/**
	 * Handles a backend's save request: validate the envelope, check the lease, then store with
	 * compare-and-set.
	 *
	 * <p>Order matters and is deliberate: the lease check comes first, because a writer that does not hold
	 * the lease must not be able to move the revision at all, not even by failing; the payload size and
	 * decode checks come next, so a malformed or oversized snapshot is refused before it touches the store;
	 * the store's compare-and-set is last, and its verdict is final.
	 *
	 * @param playerId the player
	 * @param serverId the writing backend, which must be the lease holder
	 * @param baseRevision the revision the writer based the snapshot on
	 * @param envelope the encoded snapshot, as sent on the wire
	 * @param nowMillis the current time
	 * @return the outcome
	 */
	public SaveOutcome handleSaveRequest(
			UUID playerId, String serverId, long baseRevision, byte[] envelope, long nowMillis) {

		if (envelope.length > settings.maxPayloadBytes()) {
			return new SaveOutcome(false, currentRevision(playerId),
					"the snapshot is " + envelope.length + " bytes, over the configured limit of "
							+ settings.maxPayloadBytes() + "; it was not stored");
		}
		SnapshotCodec.Result decoded = SnapshotCodec.decode(envelope);
		if (!decoded.isOk()) {
			return new SaveOutcome(false, currentRevision(playerId),
					"the snapshot envelope did not decode (" + decoded.error() + ": " + decoded.detail()
							+ "); it was not stored");
		}

		Optional<PlayerDataStore.LeaseState> lease = store.lease(playerId);
		if (lease.isPresent() && !lease.get().ownerServer().equals(serverId) && !lease.get().isExpired(nowMillis)) {
			return new SaveOutcome(false, lease.get().revision(),
					"the lease is held by '" + lease.get().ownerServer() + "' and has not expired, so '" + serverId
							+ "' may not write; the snapshot was not stored");
		}

		PlayerDataStore.SaveOutcome outcome = store.save(
				playerId, baseRevision, decoded.requireSnapshot(), serverId, nowMillis, settings.keepHistory());

		// An accepted write renews the writer's lease, so a backend that is saving regularly is never
		// silently dropped. A refused write does not touch the lease: the holder may be somebody else.
		if (outcome.accepted()) {
			store.acquireLease(playerId, serverId, nowMillis, settings.leaseMillis());
		}
		return new SaveOutcome(outcome.accepted(), outcome.newRevision(), outcome.detail());
	}

	/**
	 * Handles a backend's load request: acquire (or take over) the lease, then answer with the stored
	 * snapshot.
	 *
	 * <p>The lease acquisition is part of the answer because this is the moment authority moves to the
	 * destination after a transfer. When the lease is held by a live backend, the request is refused with an
	 * empty payload, and the asking backend keeps its local data — which is the safe outcome, because the
	 * player is still playable.
	 *
	 * @param playerId the player
	 * @param serverId the asking backend
	 * @param nowMillis the current time
	 * @return the outcome
	 */
	public LoadOutcome handleLoadRequest(UUID playerId, String serverId, long nowMillis) {
		PlayerDataStore.LeaseOutcome lease =
				store.acquireLease(playerId, serverId, nowMillis, settings.leaseMillis());
		if (!lease.acquired()) {
			return LoadOutcome.leaseRefused(lease.ownerServer(), lease.revision());
		}

		Optional<PlayerDataStore.StoredSnapshot> stored = store.load(playerId);
		if (stored.isEmpty()) {
			return new LoadOutcome(true, lease.stolen(), false, 0L, new byte[0],
					"no snapshot is stored for this player yet; keep the local data"
							+ (lease.stolen() ? " (lease taken over from an expired holder)" : ""));
		}
		PlayerDataStore.StoredSnapshot snapshot = stored.get();
		byte[] envelope = SnapshotCodec.encode(snapshot.snapshot());
		return new LoadOutcome(true, lease.stolen(), true, snapshot.revision(), envelope,
				"snapshot revision " + snapshot.revision() + ", " + envelope.length + " bytes, written by '"
						+ snapshot.updatedBy() + "'" + (lease.stolen() ? " (lease taken over from an expired holder)"
								: ""));
	}

	/**
	 * Moves a player's lease from one server to another.
	 *
	 * <p>Used twice: to the destination when a flush committed the snapshot before a transfer, and back to
	 * the source when a transfer is abandoned after that point, so a failed move does not lock the player
	 * out for a whole lease period. The move is a release followed by an acquisition; the proxy is the only
	 * writer, so the pair cannot race with anything that matters.
	 *
	 * @param playerId the player
	 * @param fromServerId the server giving up the lease
	 * @param toServerId the server receiving it
	 * @param nowMillis the current time
	 * @return a description for the log
	 */
	public String moveLease(UUID playerId, String fromServerId, String toServerId, long nowMillis) {
		store.releaseLease(playerId, fromServerId);
		PlayerDataStore.LeaseOutcome outcome =
				store.acquireLease(playerId, toServerId, nowMillis, settings.leaseMillis());
		return "lease for " + playerId + " moved from '" + fromServerId + "' to '" + toServerId + "' ("
				+ outcome.detail() + ")";
	}

	/**
	 * Releases a lease outright, for a player who left the network entirely.
	 *
	 * @param playerId the player
	 * @param serverId the server that held it
	 * @return {@code true} when a lease was released
	 */
	public boolean releaseLease(UUID playerId, String serverId) {
		return store.releaseLease(playerId, serverId);
	}

	/**
	 * The current revision of a player's snapshot, 0 when none exists.
	 *
	 * @param playerId the player
	 * @return the revision
	 */
	private long currentRevision(UUID playerId) {
		return store.load(playerId).map(PlayerDataStore.StoredSnapshot::revision).orElse(0L);
	}

	/**
	 * Renders the coordinator for a log line or a status command.
	 *
	 * @return a one-line description
	 */
	public String describe() {
		return "player-data coordinator: " + settings.describe() + "; store: " + store.describe();
	}
}
