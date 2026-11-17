package net.opendonut.fabric;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-player transfer bookkeeping: what has been asked for, what is still in flight, and what has just
 * arrived.
 *
 * <h2>Why the state is centralised</h2>
 * Every entry here exists to prevent one of the classic multi-server bugs:
 *
 * <ul>
 *   <li>{@code pending} — a transfer is asked for once and not again until it is answered. Without this, a
 *       player standing on a boundary for two seconds produces two hundred identical requests.</li>
 *   <li>{@code cooldownUntil} — after a failure, or right after an arrival, the player is left alone for a
 *       moment. This is what stops a failed transfer from turning into a request storm, and what stops an
 *       arrival from immediately looking like a departure.</li>
 *   <li>{@code placements} — the coordinates the proxy told us to put an arriving player at. Held until
 *       applied, so a placement that arrives a tick before the player is fully ready is not lost.</li>
 *   <li>{@code joinTick} — an arrival grace period, during which the player's position is still settling
 *       and must not be interpreted as a boundary crossing.</li>
 * </ul>
 *
 * <p>All methods are called from the Minecraft server thread only — boundary checks, packet handling and
 * lifecycle events all run there — so the plain maps are safe. The class documents that assumption rather
 * than paying for synchronisation that is not needed.
 */
final class PlayerTransferTracker {

	/**
	 * A transfer this backend has asked the proxy for.
	 *
	 * @param playerId the player
	 * @param targetServerId the server the backend believes should own the player now
	 * @param globalX the global block X the player was at when the request was made
	 * @param globalY the global Y at that moment
	 * @param globalZ the global block Z at that moment
	 * @param requestedAtMillis when the request was sent, used for the pending timeout
	 */
	record PendingTransfer(
			UUID playerId,
			String targetServerId,
			int globalX,
			int globalY,
			int globalZ,
			long requestedAtMillis) {
	}

	/**
	 * A placement instruction received from the proxy.
	 *
	 * @param playerId the arriving player
	 * @param fromServerId where the player came from, for the arrival message
	 * @param globalX the global X to place the player at
	 * @param globalY the global Y to place the player at
	 * @param globalZ the global Z to place the player at
	 * @param yaw the yaw to apply
	 * @param pitch the pitch to apply
	 * @param issuedAtMillis the proxy's timestamp, used to discard stale instructions
	 */
	record Placement(
			UUID playerId,
			String fromServerId,
			int globalX,
			int globalY,
			int globalZ,
			float yaw,
			float pitch,
			long issuedAtMillis) {
	}

	private final Map<UUID, PendingTransfer> pending = new HashMap<>();
	private final Map<UUID, Placement> placements = new HashMap<>();

	/**
	 * The dimension the proxy said an arriving player should be placed in, via {@code PLACE_DIMENSION}.
	 *
	 * <p>Kept separately from the placement so the two messages can arrive in either order; both are consumed
	 * together when the placement is applied. Empty/absent means "leave the dimension alone", which is what
	 * an older proxy produces.
	 */
	private final Map<UUID, String> arrivalDimensions = new HashMap<>();

	/**
	 * The portal block payload the proxy sent for an arriving player, via {@code PLACE_PORTAL}.
	 *
	 * <p>Same lifecycle as the arrival dimension: stored separately so the messages can arrive in any order,
	 * consumed together with the placement.
	 */
	private final Map<UUID, net.opendonut.common.bridge.BridgeMessage.PlacePortal> arrivalPortals = new HashMap<>();

	private final Map<UUID, Long> cooldownUntil = new HashMap<>();
	private final Map<UUID, Long> joinTick = new HashMap<>();

	/**
	 * Players currently standing in a coordinate that no server owns.
	 *
	 * <p>Kept so that entering an unassigned area is logged once, rather than once per tick for as long as
	 * the player stays there. A player crossing a gap between two regions at speed would otherwise produce
	 * hundreds of identical log lines a second.
	 */
	private final java.util.Set<UUID> inUnassignedArea = new java.util.HashSet<>();

	/**
	 * Records that a transfer request has been sent.
	 *
	 * @param transfer the request
	 */
	void markPending(PendingTransfer transfer) {
		pending.put(transfer.playerId(), transfer);
	}

	/**
	 * The in-flight request for a player, if any.
	 *
	 * @param playerId the player
	 * @return the request, or empty
	 */
	Optional<PendingTransfer> pending(UUID playerId) {
		return Optional.ofNullable(pending.get(playerId));
	}

	/**
	 * Clears the in-flight request and starts the cooldown.
	 *
	 * @param playerId the player
	 * @param nowMillis the current time
	 * @param cooldownSeconds how long to leave the player alone
	 */
	void clearPending(UUID playerId, long nowMillis, int cooldownSeconds) {
		pending.remove(playerId);
		if (cooldownSeconds > 0) {
			cooldownUntil.put(playerId, nowMillis + cooldownSeconds * 1000L);
		}
	}

	/**
	 * Tests whether a player is still inside a cooldown window.
	 *
	 * @param playerId the player
	 * @param nowMillis the current time
	 * @return {@code true} if the player must not be transferred yet
	 */
	boolean isCoolingDown(UUID playerId, long nowMillis) {
		Long until = cooldownUntil.get(playerId);
		if (until == null) {
			return false;
		}
		if (until <= nowMillis) {
			cooldownUntil.remove(playerId);
			return false;
		}
		return true;
	}

	/**
	 * Records an arrival grace period starting now.
	 *
	 * @param playerId the player
	 * @param tick the current server tick
	 */
	void markJoined(UUID playerId, long tick) {
		joinTick.put(playerId, tick);
	}

	/**
	 * The tick at which a player joined, or {@code -1} when unknown.
	 *
	 * @param playerId the player
	 * @return the join tick
	 */
	long joinedAtTick(UUID playerId) {
		return joinTick.getOrDefault(playerId, -1L);
	}

	/**
	 * Stores a placement instruction for a player.
	 *
	 * @param placement the instruction
	 */
	void storePlacement(Placement placement) {
		placements.put(placement.playerId(), placement);
	}

	/**
	 * Takes the pending placement for a player, removing it.
	 *
	 * @param playerId the player
	 * @return the placement, or empty
	 */
	Optional<Placement> takePlacement(UUID playerId) {
		return Optional.ofNullable(placements.remove(playerId));
	}

	/**
	 * The pending placement for a player without removing it.
	 *
	 * @param playerId the player
	 * @return the placement, or empty
	 */
	Optional<Placement> peekPlacement(UUID playerId) {
		return Optional.ofNullable(placements.get(playerId));
	}

	/**
	 * Stores the dimension the proxy wants an arriving player placed in.
	 *
	 * @param playerId the arriving player
	 * @param dimension the registry id of the level, for example {@code minecraft:the_nether}
	 */
	void storeArrivalDimension(UUID playerId, String dimension) {
		arrivalDimensions.put(playerId, dimension);
	}

	/**
	 * Takes the stored arrival dimension for a player, removing it.
	 *
	 * @param playerId the player
	 * @return the dimension, or empty when the proxy sent none
	 */
	Optional<String> takeArrivalDimension(UUID playerId) {
		return Optional.ofNullable(arrivalDimensions.remove(playerId));
	}

	/**
	 * Stores the portal block payload the proxy wants recreated for an arriving player.
	 *
	 * @param playerId the arriving player
	 * @param portal the payload
	 */
	void storeArrivalPortal(UUID playerId, net.opendonut.common.bridge.BridgeMessage.PlacePortal portal) {
		arrivalPortals.put(playerId, portal);
	}

	/**
	 * Takes the stored arrival portal payload for a player, removing it.
	 *
	 * @param playerId the player
	 * @return the payload, or empty when the proxy sent none
	 */
	Optional<net.opendonut.common.bridge.BridgeMessage.PlacePortal> takeArrivalPortal(UUID playerId) {
		return Optional.ofNullable(arrivalPortals.remove(playerId));
	}

	/**
	 * Records whether a player is currently standing in an unassigned coordinate.
	 *
	 * @param playerId the player
	 * @param unassigned the new state
	 * @return {@code true} if this is a change of state, which is when the caller should log something
	 */
	boolean markUnassigned(UUID playerId, boolean unassigned) {
		if (unassigned) {
			return inUnassignedArea.add(playerId);
		}
		return inUnassignedArea.remove(playerId);
	}

	/**
	 * Forgets everything about a player.
	 *
	 * @param playerId the player
	 */
	void forget(UUID playerId) {
		pending.remove(playerId);
		placements.remove(playerId);
		arrivalDimensions.remove(playerId);
		arrivalPortals.remove(playerId);
		cooldownUntil.remove(playerId);
		joinTick.remove(playerId);
		inUnassignedArea.remove(playerId);
	}

	/**
	 * Drops state for players who are no longer connected.
	 *
	 * <p>Called every tick with the current player list. Without this, a server that runs for weeks would
	 * accumulate an entry per player who ever crossed a boundary.
	 *
	 * @param online the UUIDs currently connected
	 * @return the number of players forgotten
	 */
	int retainOnly(Collection<UUID> online) {
		List<UUID> known = new ArrayList<>(pending.size() + placements.size() + joinTick.size()
				+ arrivalDimensions.size() + arrivalPortals.size());
		known.addAll(pending.keySet());
		known.addAll(placements.keySet());
		known.addAll(arrivalDimensions.keySet());
		known.addAll(arrivalPortals.keySet());
		known.addAll(joinTick.keySet());

		int forgotten = 0;
		for (UUID candidate : known) {
			if (!online.contains(candidate)) {
				forget(candidate);
				forgotten++;
			}
		}
		return forgotten;
	}

	/** @return how many transfers are currently in flight */
	int pendingCount() {
		return pending.size();
	}

	/** @return how many placements are waiting to be applied */
	int placementCount() {
		return placements.size();
	}

	/**
	 * Describes the tracked state for the {@code /opendonut status} command.
	 *
	 * @return a one-line summary
	 */
	String describe() {
		return pendingCount() + " pending transfer(s), " + placementCount() + " queued placement(s), "
				+ joinTick.size() + " tracked player(s)";
	}

	/**
	 * A snapshot of the in-flight requests, for diagnostics.
	 *
	 * @return the pending transfers
	 */
	Collection<PendingTransfer> pendingTransfers() {
		return new ArrayList<>(pending.values());
	}
}
