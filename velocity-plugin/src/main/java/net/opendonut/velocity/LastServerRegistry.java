package net.opendonut.velocity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which backend each player was last connected to, so a login can go straight there.
 *
 * <p>Without this, Velocity's default initial server is the first registered one, so a player who logged
 * off in the east is first connected to the west — which loads chunks around their logout position there —
 * and only then moved on. Remembering the last server avoids that detour entirely.
 *
 * <p>The memory is in-process only: after a proxy restart the first login of every player falls back to the
 * default behaviour, which is safe, just not optimal. Entries are never removed on disconnect — that is the
 * point — and the map is capped so a long-lived proxy cannot grow it without bound.
 */
final class LastServerRegistry {

	/** Upper bound on remembered players; beyond this, unknown players are simply not remembered. */
	static final int MAX_ENTRIES = 10_000;

	private final Map<UUID, String> lastServer = new ConcurrentHashMap<>();

	/**
	 * Records where a player is now.
	 *
	 * @param playerId the player
	 * @param serverName the server they connected to
	 */
	void remember(UUID playerId, String serverName) {
		if (serverName == null || serverName.isEmpty()) {
			return;
		}
		if (lastServer.size() >= MAX_ENTRIES && !lastServer.containsKey(playerId)) {
			return;
		}
		lastServer.put(playerId, serverName);
	}

	/**
	 * Looks up where a player was last connected.
	 *
	 * @param playerId the player
	 * @return the server name, or {@code null} when never seen
	 */
	String lookup(UUID playerId) {
		return lastServer.get(playerId);
	}

	/**
	 * Drops an entry, for tests and housekeeping.
	 *
	 * @param playerId the player
	 */
	void forget(UUID playerId) {
		lastServer.remove(playerId);
	}

	/** @return how many players are remembered */
	int size() {
		return lastServer.size();
	}
}
