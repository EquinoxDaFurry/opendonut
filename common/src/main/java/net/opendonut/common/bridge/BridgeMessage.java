package net.opendonut.common.bridge;

import java.util.Objects;
import java.util.UUID;

import net.opendonut.common.model.RegionBounds;

/**
 * A message on the OpenDonut bridge between a Fabric backend and the Velocity proxy.
 *
 * <h2>Why a custom protocol instead of ad hoc plugin messages</h2>
 * Plugin messages are the only channel a backend mod and a proxy plugin share, but on their own they
 * carry no identity, no ordering, no authentication and no version. A player's modified client can send
 * anything on any channel, and Velocity cannot tell those packets apart from packets a backend plugin
 * sent. Every message therefore arrives here wrapped in an HMAC and a nonce
 * (see {@link BridgeCodec}), and every message is a typed record so that both sides agree on what was
 * sent. The design is deliberately boring: fixed field order, explicit version, no reflection, no JSON.
 *
 * <h2>Trust model</h2>
 * <ul>
 *   <li>The <strong>proxy</strong> is authoritative for routing, for the resolved seed, and for the
 *       region table. It re-derives the destination of every transfer from its own configuration.</li>
 *   <li>A <strong>backend</strong> is authoritative for what it observes: where its players are, that it
 *       owns the region it claims, and that it is still alive.</li>
 *   <li>The <strong>client</strong> is trusted with nothing.</li>
 * </ul>
 *
 * <h2>Compatibility</h2>
 * Messages are sealed: adding a type is a deliberate act and the compiler points at every switch that has
 * to handle it. Unknown message <em>types</em> from a newer peer are rejected cleanly by
 * {@link MessageType#byWireId(int)} rather than misparsed.
 */
public sealed interface BridgeMessage
		permits BridgeMessage.Hello, BridgeMessage.HelloAck, BridgeMessage.TransferRequest,
		BridgeMessage.TransferResult, BridgeMessage.PlacePlayer {

	/**
	 * The wire type of this message.
	 *
	 * @return the message type
	 */
	MessageType type();

	/**
	 * Backend to proxy: a declaration of identity and world configuration.
	 *
	 * @param serverId the id this backend believes it is, as configured in {@code backend.server-id} or
	 *                 discovered by port
	 * @param worldName the Minecraft level name the backend has loaded
	 * @param seed the seed the backend's world was actually created with, or
	 *             {@link #UNKNOWN_SEED} when it could not be determined
	 * @param bounds the region the backend believes it owns
	 * @param minecraftVersion the Minecraft version string the backend is running
	 * @param openDonutVersion the OpenDonut build version
	 * @param advertisedAddress the address the backend believes it listens on, for diagnostics only
	 */
	record Hello(
			String serverId,
			String worldName,
			long seed,
			RegionBounds bounds,
			String minecraftVersion,
			String openDonutVersion,
			String advertisedAddress) implements BridgeMessage {

		/** Sentinel for "this backend could not determine the seed of its world". */
		public static final long UNKNOWN_SEED = Long.MIN_VALUE;

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public Hello {
			Objects.requireNonNull(serverId, "serverId");
			Objects.requireNonNull(worldName, "worldName");
			Objects.requireNonNull(bounds, "bounds");
			Objects.requireNonNull(minecraftVersion, "minecraftVersion");
			Objects.requireNonNull(openDonutVersion, "openDonutVersion");
			Objects.requireNonNull(advertisedAddress, "advertisedAddress");
		}

		@Override
		public MessageType type() {
			return MessageType.HELLO;
		}

		/** @return {@code true} when the backend did not know its own seed */
		public boolean seedUnknown() {
			return seed == UNKNOWN_SEED;
		}
	}

	/**
	 * Proxy to backend: the verdict on a {@link Hello}.
	 *
	 * @param serverId the id the proxy resolved for this backend
	 * @param accepted whether the proxy will route players to this backend
	 * @param detail a human readable reason, always present, phrased for a server log
	 * @param resolvedSeed the seed the proxy resolved for the whole network, which is what the backend
	 *                     must generate with
	 */
	record HelloAck(String serverId, boolean accepted, String detail, long resolvedSeed) implements BridgeMessage {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public HelloAck {
			Objects.requireNonNull(serverId, "serverId");
			Objects.requireNonNull(detail, "detail");
		}

		@Override
		public MessageType type() {
			return MessageType.HELLO_ACK;
		}
	}

	/**
	 * Backend to proxy: request that a player be moved to the server that owns their new coordinates.
	 *
	 * @param playerId the player's Mojang UUID, which is what the proxy keys everything on
	 * @param playerName the player's name, for logs only; never used for routing
	 * @param fromServerId the server sending the request; the proxy checks that the player really is on it
	 * @param globalX the player's global block X at the moment of the request
	 * @param globalY the player's Y, preserved across the transfer so they do not fall through ungenerated
	 *                terrain
	 * @param globalZ the player's global block Z
	 * @param yaw the player's yaw, preserved so the view does not jump
	 * @param pitch the player's pitch
	 * @param requestedServerId the destination the backend believes is correct, or an empty string; a hint
	 *                          the proxy verifies rather than trusts
	 * @param reason a short explanation for logs, for example {@code "crossed max-x"}
	 */
	record TransferRequest(
			UUID playerId,
			String playerName,
			String fromServerId,
			int globalX,
			int globalY,
			int globalZ,
			float yaw,
			float pitch,
			String requestedServerId,
			String reason) implements BridgeMessage {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public TransferRequest {
			Objects.requireNonNull(playerId, "playerId");
			Objects.requireNonNull(playerName, "playerName");
			Objects.requireNonNull(fromServerId, "fromServerId");
			Objects.requireNonNull(requestedServerId, "requestedServerId");
			Objects.requireNonNull(reason, "reason");
		}

		@Override
		public MessageType type() {
			return MessageType.TRANSFER_REQUEST;
		}

		/** @return the coordinates as a compact log fragment */
		public String position() {
			return globalX + "," + globalY + "," + globalZ;
		}
	}

	/**
	 * Proxy to backend: the outcome of a {@link TransferRequest}.
	 *
	 * @param playerId the player the request was about
	 * @param fromServerId the server that requested the transfer
	 * @param toServerId the server the proxy actually connected the player to, or an empty string when
	 *                   nothing was attempted
	 * @param success whether the player is now connected to {@code toServerId}
	 * @param statusMessage the proxy's own status code or explanation, for example
	 *                      {@code SERVER_DISCONNECTED} or {@code already connected}
	 * @param globalX echoed coordinates, so the backend can apply its failure policy without a second
	 *                lookup
	 * @param globalY echoed Y
	 * @param globalZ echoed Z
	 */
	record TransferResult(
			UUID playerId,
			String fromServerId,
			String toServerId,
			boolean success,
			String statusMessage,
			int globalX,
			int globalY,
			int globalZ) implements BridgeMessage {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public TransferResult {
			Objects.requireNonNull(playerId, "playerId");
			Objects.requireNonNull(fromServerId, "fromServerId");
			Objects.requireNonNull(toServerId, "toServerId");
			Objects.requireNonNull(statusMessage, "statusMessage");
		}

		@Override
		public MessageType type() {
			return MessageType.TRANSFER_RESULT;
		}
	}

	/**
	 * Proxy to backend: a player is about to arrive at these global coordinates.
	 *
	 * @param playerId the arriving player's UUID
	 * @param fromServerId the server the player is coming from, for logs and system messages
	 * @param toServerId the destination server, which must match the receiving backend's own id
	 * @param globalX the global block X to place the player at
	 * @param globalY the global Y
	 * @param globalZ the global block Z
	 * @param yaw the yaw to apply
	 * @param pitch the pitch to apply
	 * @param issuedAtMillis the proxy's timestamp for this placement, used to expire stale instructions
	 */
	record PlacePlayer(
			UUID playerId,
			String fromServerId,
			String toServerId,
			int globalX,
			int globalY,
			int globalZ,
			float yaw,
			float pitch,
			long issuedAtMillis) implements BridgeMessage {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public PlacePlayer {
			Objects.requireNonNull(playerId, "playerId");
			Objects.requireNonNull(fromServerId, "fromServerId");
			Objects.requireNonNull(toServerId, "toServerId");
		}

		@Override
		public MessageType type() {
			return MessageType.PLACE_PLAYER;
		}

		/** @return the coordinates as a compact log fragment */
		public String position() {
			return globalX + "," + globalY + "," + globalZ;
		}
	}
}
