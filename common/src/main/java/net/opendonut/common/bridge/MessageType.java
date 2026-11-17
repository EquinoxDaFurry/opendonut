package net.opendonut.common.bridge;

import java.util.Optional;

/**
 * Wire identifiers for the OpenDonut bridge protocol.
 *
 * <p>The numbers are part of the protocol and must never be reused or reordered: a backend running an
 * older build must be able to say "I do not understand message 6" instead of misreading it as message 4.
 * Adding a message type is therefore a compatible change; changing the meaning of one is not.
 */
public enum MessageType {

	/**
	 * Backend to proxy. "This is who I am and this is the world I am running."
	 *
	 * <p>Sent once, after the backend's first player connects — plugin message channels are per player in
	 * Velocity, so there is no connection to the proxy to use before that. The proxy uses it to check that
	 * the backend's world name, seed, region and Minecraft version match the central configuration, long
	 * before a player walks into a boundary.
	 */
	HELLO(1),

	/**
	 * Proxy to backend. "I received your HELLO, and here is my verdict."
	 *
	 * <p>A rejection carries the reason so the backend can log a precise, actionable message and refuse to
	 * transfer players instead of silently sending them into a broken route.
	 */
	HELLO_ACK(2),

	/**
	 * Backend to proxy. "Player X has walked from my region into coordinates that should belong to
	 * server Y; please move them."
	 *
	 * <p>The destination in this message is a <em>hint</em>. The proxy re-derives ownership from its own
	 * copy of the configuration and ignores the hint when it disagrees, so a modified client that can
	 * forge this message still cannot choose where it goes.
	 */
	TRANSFER_REQUEST(3),

	/**
	 * Proxy to backend. "The transfer of player X to server Y did or did not happen."
	 *
	 * <p>The requesting backend uses this to clear its pending flag on success, or to apply its failure
	 * policy (by default, moving the player back inside its own region) on failure.
	 */
	TRANSFER_RESULT(4),

	/**
	 * Proxy to backend. "Player X is about to arrive on you at these global coordinates."
	 *
	 * <p>Sent to the destination backend immediately before the proxy starts the connection, so that the
	 * destination has the position ready when the player finishes joining. Coordinates are global, so they
	 * are used as-is: OpenDonut expects every backend to generate the same terrain at the same coordinates.
	 */
	PLACE_PLAYER(5),

	/**
	 * Backend to proxy. "Player X is joining me; send me their stored data."
	 *
	 * <p>A backend asks on join rather than being pushed to, because the backend is the component that knows
	 * when its player object exists and can be given items. The proxy answers only if the asking backend holds
	 * the player's lease, which it confirms or takes over as part of answering.
	 */
	PLAYER_DATA_LOAD_REQUEST(6),

	/**
	 * Proxy to backend. "Here is player X's data, as of revision R." Or, for a player who has never been
	 * stored, "there is nothing".
	 */
	PLAYER_DATA_LOAD_RESPONSE(7),

	/**
	 * Backend to proxy. "Store this snapshot of player X; I based it on revision R."
	 *
	 * <p>The base revision is what makes the write safe: the store applies it only if it has not moved on,
	 * which is what stops a slow backend from overwriting a newer snapshot with an older one.
	 */
	PLAYER_DATA_SAVE_REQUEST(8),

	/**
	 * Proxy to backend. "Your snapshot was stored as revision R" — or refused, with the reason.
	 */
	PLAYER_DATA_SAVE_RESULT(9),

	/**
	 * Proxy to backend. "Save player X now: their lease is about to move."
	 *
	 * <p>Sent before a transfer, so the snapshot is committed before the destination can read it. A backend
	 * that cannot answer in time causes the transfer to be refused (by default), because a player is never
	 * moved away from their own items.
	 */
	PLAYER_DATA_FLUSH_REQUEST(10);

	private final int wireId;

	MessageType(int wireId) {
		this.wireId = wireId;
	}

	/**
	 * The number written on the wire.
	 *
	 * @return the wire identifier
	 */
	public int wireId() {
		return wireId;
	}

	/**
	 * Looks up a message type by wire identifier.
	 *
	 * @param wireId the identifier read from the wire
	 * @return the type, or empty when this build does not know it
	 */
	public static Optional<MessageType> byWireId(int wireId) {
		for (MessageType type : values()) {
			if (type.wireId == wireId) {
				return Optional.of(type);
			}
		}
		return Optional.empty();
	}
}
