package net.opendonut.common.playerdata;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The reference implementation of {@link PlayerDataStore}: a few maps, no dependencies, no persistence.
 *
 * <p>This is the implementation to read when you want to know what the rules are, and it is what
 * {@code PlayerDataStoreContractTest} exercises alongside the SQL-backed store. The two must behave identically
 * for the same sequence of calls, or one of them is wrong — and because the contract test runs against both, a
 * divergence shows up as a failing test rather than as duplicate items on a live server.
 *
 * <p>It is also a legitimate production choice in one situation: a proxy whose data does not need to survive a
 * restart and whose players all pass through it continuously. Losing the store means losing <em>stored</em>
 * snapshots, not the players' current items, which are still on the server they are standing on.
 *
 * <p>All operations are guarded by one lock. That is not a compromise: the SQL implementation does the same
 * thing for the same reason (see {@code SqlitePlayerDataStore}), and the traffic here is a handful of operations
 * per player per minute, not a hot path.
 */
public final class InMemoryPlayerDataStore implements PlayerDataStore {

	private record Entry(long revision, PlayerDataSnapshot snapshot, long updatedAtMillis, String updatedBy) {
	}

	private record Lease(String ownerServer, long revision, long expiresAtMillis) {
	}

	private final Object lock = new Object();
	private final Map<UUID, Entry> snapshots = new HashMap<>();
	private final Map<UUID, Lease> leases = new HashMap<>();
	private final Map<UUID, Deque<Entry>> history = new HashMap<>();

	@Override
	public Optional<StoredSnapshot> load(UUID playerId) {
		synchronized (lock) {
			return Optional.ofNullable(snapshots.get(playerId)).map(InMemoryPlayerDataStore::toStored);
		}
	}

	@Override
	public SaveOutcome save(UUID playerId, long baseRevision, PlayerDataSnapshot snapshot, String serverId,
			long nowMillis, int keepHistory) {
		synchronized (lock) {
			Entry current = snapshots.get(playerId);
			long currentRevision = current == null ? 0L : current.revision();

			if (currentRevision != baseRevision) {
				return SaveOutcome.refused(currentRevision, "the store holds revision " + currentRevision
						+ " but the writer was based on " + baseRevision
						+ "; the snapshot it produced is stale and was not applied");
			}

			long newRevision = currentRevision + 1;
			Entry entry = new Entry(newRevision, snapshot, nowMillis, serverId);
			snapshots.put(playerId, entry);
			if (keepHistory > 0) {
				Deque<Entry> entries = history.computeIfAbsent(playerId, key -> new ArrayDeque<>());
				entries.addFirst(entry);
				while (entries.size() > keepHistory) {
					entries.removeLast();
				}
			}
			return SaveOutcome.accepted(newRevision);
		}
	}

	@Override
	public LeaseOutcome acquireLease(UUID playerId, String serverId, long nowMillis, long leaseMillis) {
		synchronized (lock) {
			long expiresAt = nowMillis + leaseMillis;
			Lease existing = leases.get(playerId);
			long revision = snapshots.containsKey(playerId) ? snapshots.get(playerId).revision() : 0L;

			if (existing == null) {
				leases.put(playerId, new Lease(serverId, revision, expiresAt));
				return new LeaseOutcome(true, serverId, revision, false, "granted");
			}
			if (existing.ownerServer().equals(serverId)) {
				leases.put(playerId, new Lease(serverId, revision, expiresAt));
				return new LeaseOutcome(true, serverId, revision, false, "renewed");
			}
			if (existing.expiresAtMillis() <= nowMillis) {
				// The previous holder stopped responding. Taking over is the documented recovery path, and it is
				// reported so that a crash is visible in the log rather than silent.
				leases.put(playerId, new Lease(serverId, revision, expiresAt));
				return new LeaseOutcome(true, serverId, revision, true,
						"taken over from '" + existing.ownerServer() + "', whose lease expired");
			}
			return new LeaseOutcome(false, existing.ownerServer(), existing.revision(), false,
					"'" + existing.ownerServer() + "' holds the lease for another "
							+ (existing.expiresAtMillis() - nowMillis) + " ms");
		}
	}

	@Override
	public Optional<LeaseState> lease(UUID playerId) {
		synchronized (lock) {
			return Optional.ofNullable(leases.get(playerId))
					.map(lease -> new LeaseState(lease.ownerServer(), lease.revision(),
							lease.expiresAtMillis()));
		}
	}

	@Override
	public boolean releaseLease(UUID playerId, String serverId) {
		synchronized (lock) {
			Lease existing = leases.get(playerId);
			if (existing == null || !existing.ownerServer().equals(serverId)) {
				return false;
			}
			leases.remove(playerId);
			return true;
		}
	}

	@Override
	public List<StoredSnapshot> history(UUID playerId, int limit) {
		synchronized (lock) {
			Deque<Entry> entries = history.get(playerId);
			if (entries == null || limit <= 0) {
				return List.of();
			}
			List<StoredSnapshot> result = new ArrayList<>(Math.min(limit, entries.size()));
			int taken = 0;
			for (Entry entry : entries) {
				if (taken++ >= limit) {
					break;
				}
				result.add(toStored(entry));
			}
			return List.copyOf(result);
		}
	}

	@Override
	public String describe() {
		synchronized (lock) {
			int playersWithSnapshots = snapshots.size();
			long now = System.currentTimeMillis();
			int activeLeases = 0;
			for (Lease lease : leases.values()) {
				if (lease.expiresAtMillis() > now) {
					activeLeases++;
				}
			}
			return "in-memory store: " + playersWithSnapshots + " snapshot(s), " + activeLeases
					+ " active lease(s), not persisted";
		}
	}

	@Override
	public void close() {
		synchronized (lock) {
			snapshots.clear();
			leases.clear();
			history.clear();
		}
	}

	/**
	 * Converts an internal entry to the public record.
	 *
	 * @param entry the entry
	 * @return the stored snapshot
	 */
	private static StoredSnapshot toStored(Entry entry) {
		return new StoredSnapshot(entry.revision(), entry.snapshot(), entry.updatedAtMillis(), entry.updatedBy());
	}
}
