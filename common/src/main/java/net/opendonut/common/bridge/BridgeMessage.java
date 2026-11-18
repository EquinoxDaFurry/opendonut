package net.opendonut.common.bridge;

import java.util.Arrays;
import java.util.List;
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
		BridgeMessage.TransferResult, BridgeMessage.PlacePlayer, BridgeMessage.PlayerDataLoadRequest,
		BridgeMessage.PlayerDataLoadResponse, BridgeMessage.PlayerDataSaveRequest,
		BridgeMessage.PlayerDataSaveResult, BridgeMessage.PlayerDataFlushRequest,
		BridgeMessage.TimeReport, BridgeMessage.TimeSet,
		BridgeMessage.TransferDimension, BridgeMessage.PlaceDimension,
		BridgeMessage.TransferPortal, BridgeMessage.PlacePortal,
		BridgeMessage.AdvancementsLoadRequest, BridgeMessage.AdvancementsLoadResponse,
		BridgeMessage.AdvancementsSaveRequest, BridgeMessage.AdvancementsSaveResult,
		BridgeMessage.ChatRelay, BridgeMessage.ChatBroadcast {

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

	/**
	 * Backend to proxy: "Player X has joined me; send me their stored data."
	 *
	 * <p>Sent from the backend's player-join handling, which is the first moment the backend can receive a
	 * plugin message about that player. The proxy only answers when the asking backend holds — or takes
	 * over — the player's lease, so the answer doubles as the lease acquisition.
	 *
	 * @param playerId the player who just joined
	 * @param serverId the asking backend's id, which the proxy checks against the connection it arrived on
	 */
	record PlayerDataLoadRequest(
			UUID playerId,
			String serverId) implements BridgeMessage {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public PlayerDataLoadRequest {
			Objects.requireNonNull(playerId, "playerId");
			Objects.requireNonNull(serverId, "serverId");
		}

		@Override
		public MessageType type() {
			return MessageType.PLAYER_DATA_LOAD_REQUEST;
		}
	}

	/**
	 * Proxy to backend: the stored data for a player, or the fact that nothing is stored.
	 *
	 * @param playerId the player
	 * @param present whether a snapshot exists; {@code false} means "this player has never been stored,
	 *                keep what your local player file says"
	 * @param revision the revision the snapshot was stored as, which the backend quotes as the base of its
	 *                 next save; {@code 0} when nothing is stored
	 * @param envelope the snapshot, encoded by {@code SnapshotCodec}; empty when {@code present} is false.
	 *                 Carried as bytes rather than as fields so the store and the wire use one encoding
	 * @param detail an explanation for the log, always present
	 */
	record PlayerDataLoadResponse(
			UUID playerId,
			boolean present,
			long revision,
			byte[] envelope,
			String detail) implements BridgeMessage {

		/**
		 * Validates the record and copies the payload.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public PlayerDataLoadResponse {
			Objects.requireNonNull(playerId, "playerId");
			Objects.requireNonNull(envelope, "envelope");
			Objects.requireNonNull(detail, "detail");
			envelope = envelope.clone();
		}

		@Override
		public byte[] envelope() {
			return envelope.clone();
		}

		@Override
		public MessageType type() {
			return MessageType.PLAYER_DATA_LOAD_RESPONSE;
		}

		// Records compare arrays by reference by default; a message is a value, so compare the payload bytes.
		@Override
		public boolean equals(Object other) {
			return other instanceof PlayerDataLoadResponse response
					&& playerId.equals(response.playerId)
					&& present == response.present
					&& revision == response.revision
					&& Arrays.equals(envelope, response.envelope)
					&& detail.equals(response.detail);
		}

		@Override
		public int hashCode() {
			return Objects.hash(playerId, present, revision, Arrays.hashCode(envelope), detail);
		}

		@Override
		public String toString() {
			return "PlayerDataLoadResponse[playerId=" + playerId + ", present=" + present + ", revision="
					+ revision + ", envelope=" + envelope.length + " bytes, detail=" + detail + "]";
		}
	}

	/**
	 * Backend to proxy: "Store this snapshot of player X; I based it on revision R."
	 *
	 * <p>The base revision is the compare-and-set token: the proxy applies the snapshot only if the store
	 * still holds {@code baseRevision}, which is what stops a slow backend from overwriting a newer
	 * snapshot with an older one.
	 *
	 * @param playerId the player
	 * @param serverId the writing backend's id; the proxy refuses the write when the lease is held by
	 *                 somebody else
	 * @param baseRevision the revision the writer read last (0 when the player has never been stored)
	 * @param envelope the snapshot, encoded by {@code SnapshotCodec}
	 */
	record PlayerDataSaveRequest(
			UUID playerId,
			String serverId,
			long baseRevision,
			byte[] envelope) implements BridgeMessage {

		/**
		 * Validates the record and copies the payload.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public PlayerDataSaveRequest {
			Objects.requireNonNull(playerId, "playerId");
			Objects.requireNonNull(serverId, "serverId");
			Objects.requireNonNull(envelope, "envelope");
			envelope = envelope.clone();
		}

		@Override
		public byte[] envelope() {
			return envelope.clone();
		}

		@Override
		public MessageType type() {
			return MessageType.PLAYER_DATA_SAVE_REQUEST;
		}

		// Records compare arrays by reference by default; a message is a value, so compare the payload bytes.
		@Override
		public boolean equals(Object other) {
			return other instanceof PlayerDataSaveRequest request
					&& playerId.equals(request.playerId)
					&& serverId.equals(request.serverId)
					&& baseRevision == request.baseRevision
					&& Arrays.equals(envelope, request.envelope);
		}

		@Override
		public int hashCode() {
			return Objects.hash(playerId, serverId, baseRevision, Arrays.hashCode(envelope));
		}

		@Override
		public String toString() {
			return "PlayerDataSaveRequest[playerId=" + playerId + ", serverId=" + serverId + ", baseRevision="
					+ baseRevision + ", envelope=" + envelope.length + " bytes]";
		}
	}

	/**
	 * Proxy to backend: the outcome of a {@link PlayerDataSaveRequest}.
	 *
	 * @param playerId the player
	 * @param accepted whether the snapshot was stored
	 * @param revision the revision the store holds after the attempt: the new revision when accepted, the
	 *                 current one when refused
	 * @param detail why, phrased for the backend's log
	 */
	record PlayerDataSaveResult(
			UUID playerId,
			boolean accepted,
			long revision,
			String detail) implements BridgeMessage {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public PlayerDataSaveResult {
			Objects.requireNonNull(playerId, "playerId");
			Objects.requireNonNull(detail, "detail");
		}

		@Override
		public MessageType type() {
			return MessageType.PLAYER_DATA_SAVE_RESULT;
		}
	}

	/**
	 * Proxy to backend: "Save player X's data now; they are about to be moved to another server."
	 *
	 * <p>The proxy sequences this before the connection request, so that the snapshot is committed in the
	 * store before the destination can read it. A backend that cannot answer in time causes the transfer to
	 * be refused (unless {@code player-data.on-flush-failure} says otherwise), because a player is never
	 * moved away from their own items.
	 *
	 * @param playerId the player being moved
	 * @param fromServerId the server the player is leaving, which must match the receiving backend
	 * @param toServerId the destination, for the log
	 */
	record PlayerDataFlushRequest(
			UUID playerId,
			String fromServerId,
			String toServerId) implements BridgeMessage {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public PlayerDataFlushRequest {
			Objects.requireNonNull(playerId, "playerId");
			Objects.requireNonNull(fromServerId, "fromServerId");
			Objects.requireNonNull(toServerId, "toServerId");
		}

		@Override
		public MessageType type() {
			return MessageType.PLAYER_DATA_FLUSH_REQUEST;
		}
	}

	/**
	 * Backend to proxy: a reading of a world clock.
	 *
	 * <p>Every backend with time sync enabled sends this periodically; the proxy accepts only the reading of
	 * the configured authority. The clock is named by its registry id (for example {@code minecraft:overworld})
	 * so that datapack-defined clocks work too, not just the two vanilla ones.
	 *
	 * @param clock the registry id of the world clock, for example {@code minecraft:overworld}
	 * @param totalTicks the clock's {@code totalTicks()} at the moment of the report
	 * @param rate the clock's rate, carried for forward compatibility; Phase 1 does not apply it
	 * @param paused whether the clock is paused, carried for forward compatibility; Phase 1 does not apply it
	 */
	record TimeReport(
			String clock,
			long totalTicks,
			float rate,
			boolean paused) implements BridgeMessage {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public TimeReport {
			Objects.requireNonNull(clock, "clock");
		}

		@Override
		public MessageType type() {
			return MessageType.TIME_REPORT;
		}
	}

	/**
	 * Proxy to backend: set a world clock to a reading.
	 *
	 * @param clock the registry id of the world clock
	 * @param totalTicks the {@code totalTicks} to set
	 * @param rate the clock's rate, carried for forward compatibility; Phase 1 does not apply it
	 * @param paused whether the clock is paused, carried for forward compatibility; Phase 1 does not apply it
	 * @param reason why the proxy sent this, for the backend's log: {@code hello}, {@code transfer} or
	 *             {@code periodic}
	 */
	record TimeSet(
			String clock,
			long totalTicks,
			float rate,
			boolean paused,
			String reason) implements BridgeMessage {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public TimeSet {
			Objects.requireNonNull(clock, "clock");
			Objects.requireNonNull(reason, "reason");
		}

		@Override
		public MessageType type() {
			return MessageType.TIME_SET;
		}
	}

	/**
	 * Backend to proxy: the dimension a player is standing in when they leave.
	 *
	 * <p>Sent on the player's own connection immediately before the {@code TRANSFER_REQUEST} for the same
	 * move, so the proxy can attach it to the pending transfer. {@code TRANSFER_REQUEST} cannot carry it
	 * (its fields are frozen), which is why this message exists.
	 *
	 * @param playerId the player
	 * @param dimension the registry id of the level the player is in, for example
	 *                  {@code minecraft:the_nether}
	 */
	record TransferDimension(
			UUID playerId,
			String dimension) implements BridgeMessage {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public TransferDimension {
			Objects.requireNonNull(playerId, "playerId");
			Objects.requireNonNull(dimension, "dimension");
		}

		@Override
		public MessageType type() {
			return MessageType.TRANSFER_DIMENSION;
		}
	}

	/**
	 * Proxy to backend: the dimension an arriving player should be placed in.
	 *
	 * <p>Sent right after the {@code PLACE_PLAYER} for the same transfer, carrying the dimension the source
	 * reported in its {@code TRANSFER_DIMENSION}. The destination applies it with a cross-level teleport to
	 * the placement coordinates — same global coordinates, other dimension, with no nether scaling, because
	 * OpenDonut backends share one coordinate space.
	 *
	 * @param playerId the arriving player
	 * @param dimension the registry id of the level to place them in
	 */
	record PlaceDimension(
			UUID playerId,
			String dimension) implements BridgeMessage {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public PlaceDimension {
			Objects.requireNonNull(playerId, "playerId");
			Objects.requireNonNull(dimension, "dimension");
		}

		@Override
		public MessageType type() {
			return MessageType.PLACE_DIMENSION;
		}
	}

	/**
	 * One block of a portal payload: an offset from the origin plus the block state as opaque NBT bytes.
	 *
	 * <p>The common module has no Minecraft dependency, so the block state travels as the bytes
	 * {@code NbtIo.write(NbtUtils.writeBlockState(state), ...)} produced by the backend; the destination
	 * decodes it with the matching reader. {@code equals}/{@code hashCode} compare the bytes by content, because a
	 * record's default would compare the array by reference.
	 *
	 * @param dx offset east of the origin
	 * @param dy offset above the origin
	 * @param dz offset south of the origin
	 * @param nbt the serialised block state
	 */
	record BlockEntry(int dx, int dy, int dz, byte[] nbt) {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if the nbt is missing
		 */
		public BlockEntry {
			Objects.requireNonNull(nbt, "nbt");
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof BlockEntry entry
					&& dx == entry.dx && dy == entry.dy && dz == entry.dz
					&& Arrays.equals(nbt, entry.nbt);
		}

		@Override
		public int hashCode() {
			return 31 * (31 * (31 * dx + dy) + dz) + Arrays.hashCode(nbt);
		}

		@Override
		public String toString() {
			return "BlockEntry[(" + dx + ", " + dy + ", " + dz + "), " + nbt.length + " bytes]";
		}
	}

	/**
	 * The block payload shared by {@link TransferPortal} and {@link PlacePortal}: a small box of non-air blocks
	 * around a portal's far side, to be recreated on the other server.
	 *
	 * @param dimension the registry id of the level the origin is in, for example {@code minecraft:overworld}
	 * @param originX the origin block X (the arrival point the box is centred on)
	 * @param originY the origin block Y
	 * @param originZ the origin block Z
	 * @param blocks the blocks, offsets relative to the origin; never null, possibly empty
	 */
	record PortalBlocks(
			String dimension,
			int originX,
			int originY,
			int originZ,
			List<BlockEntry> blocks) {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public PortalBlocks {
			Objects.requireNonNull(dimension, "dimension");
			blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
		}
	}

	/**
	 * Backend to proxy: the blocks around the far side of a portal a player just used to leave the region.
	 *
	 * <p>Sent on the player's own connection immediately before the {@code TRANSFER_REQUEST} for the same move.
	 *
	 * @param playerId the player
	 * @param portal the block payload
	 */
	record TransferPortal(
			UUID playerId,
			PortalBlocks portal) implements BridgeMessage {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public TransferPortal {
			Objects.requireNonNull(playerId, "playerId");
			Objects.requireNonNull(portal, "portal");
		}

		@Override
		public MessageType type() {
			return MessageType.TRANSFER_PORTAL;
		}
	}

	/**
	 * Proxy to backend: recreate these blocks in this level when the player arrives.
	 *
	 * <p>Sent right after the {@code PLACE_DIMENSION} for the same transfer, carrying what the source sent in its
	 * {@code TRANSFER_PORTAL}.
	 *
	 * @param playerId the arriving player
	 * @param portal the block payload
	 */
	record PlacePortal(
			UUID playerId,
			PortalBlocks portal) implements BridgeMessage {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public PlacePortal {
			Objects.requireNonNull(playerId, "playerId");
			Objects.requireNonNull(portal, "portal");
		}

		@Override
		public MessageType type() {
			return MessageType.PLACE_PORTAL;
		}
	}

	/**
	 * Backend to proxy: ask for the stored advancement progress of a player.
	 *
	 * <p>Sent on the player's own connection right after the {@code PLAYER_DATA_LOAD_REQUEST} for the same
	 * join. Advancements are stored per player in {@code world/advancements/<uuid>.json}, not in the player
	 * data file, so they travel as their own blob.
	 *
	 * @param playerId the player
	 * @param serverId the asking backend's id
	 */
	record AdvancementsLoadRequest(
			UUID playerId,
			String serverId) implements BridgeMessage {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public AdvancementsLoadRequest {
			Objects.requireNonNull(playerId, "playerId");
			Objects.requireNonNull(serverId, "serverId");
		}

		@Override
		public MessageType type() {
			return MessageType.ADVANCEMENTS_LOAD_REQUEST;
		}
	}

	/**
	 * Proxy to backend: the stored advancement progress of a player.
	 *
	 * <p>The payload is the raw bytes of the source backend's advancements JSON file. When {@code present} is
	 * false the payload is empty and the destination keeps its own local file.
	 *
	 * @param playerId the player
	 * @param present whether the store holds a payload
	 * @param revision the revision of the stored payload
	 * @param payload the raw advancements JSON bytes
	 * @param detail a human readable status
	 */
	record AdvancementsLoadResponse(
			UUID playerId,
			boolean present,
			long revision,
			byte[] payload,
			String detail) implements BridgeMessage {

		/**
		 * Validates the record and copies the payload.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public AdvancementsLoadResponse {
			Objects.requireNonNull(playerId, "playerId");
			Objects.requireNonNull(payload, "payload");
			Objects.requireNonNull(detail, "detail");
			payload = payload.clone();
		}

		@Override
		public byte[] payload() {
			return payload.clone();
		}

		@Override
		public MessageType type() {
			return MessageType.ADVANCEMENTS_LOAD_RESPONSE;
		}

		// Records compare arrays by reference by default; a message is a value, so compare the payload bytes.
		@Override
		public boolean equals(Object other) {
			return other instanceof AdvancementsLoadResponse response
					&& playerId.equals(response.playerId)
					&& present == response.present
					&& revision == response.revision
					&& Arrays.equals(payload, response.payload)
					&& detail.equals(response.detail);
		}

		@Override
		public int hashCode() {
			return Objects.hash(playerId, present, revision, Arrays.hashCode(payload), detail);
		}

		@Override
		public String toString() {
			return "AdvancementsLoadResponse[playerId=" + playerId + ", present=" + present + ", revision="
					+ revision + ", payload=" + payload.length + " bytes, detail=" + detail + "]";
		}
	}

	/**
	 * Backend to proxy: store this advancement progress for a player.
	 *
	 * <p>Sent right after the matching {@code PLAYER_DATA_SAVE_REQUEST}: on the periodic save, on
	 * disconnect, and on the flush before a transfer. The store applies it compare-and-set on
	 * {@code baseRevision}, exactly like player data, so a stale backend cannot overwrite a fresher payload.
	 *
	 * @param playerId the player
	 * @param serverId the saving backend's id
	 * @param baseRevision the revision the sender based this payload on
	 * @param payload the raw advancements JSON bytes
	 */
	record AdvancementsSaveRequest(
			UUID playerId,
			String serverId,
			long baseRevision,
			byte[] payload) implements BridgeMessage {

		/**
		 * Validates the record and copies the payload.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public AdvancementsSaveRequest {
			Objects.requireNonNull(playerId, "playerId");
			Objects.requireNonNull(serverId, "serverId");
			Objects.requireNonNull(payload, "payload");
			payload = payload.clone();
		}

		@Override
		public byte[] payload() {
			return payload.clone();
		}

		@Override
		public MessageType type() {
			return MessageType.ADVANCEMENTS_SAVE_REQUEST;
		}

		// Records compare arrays by reference by default; a message is a value, so compare the payload bytes.
		@Override
		public boolean equals(Object other) {
			return other instanceof AdvancementsSaveRequest request
					&& playerId.equals(request.playerId)
					&& serverId.equals(request.serverId)
					&& baseRevision == request.baseRevision
					&& Arrays.equals(payload, request.payload);
		}

		@Override
		public int hashCode() {
			return Objects.hash(playerId, serverId, baseRevision, Arrays.hashCode(payload));
		}

		@Override
		public String toString() {
			return "AdvancementsSaveRequest[playerId=" + playerId + ", serverId=" + serverId + ", baseRevision="
					+ baseRevision + ", payload=" + payload.length + " bytes]";
		}
	}

	/**
	 * Proxy to backend: the store's answer to an {@code ADVANCEMENTS_SAVE_REQUEST}.
	 *
	 * @param playerId the player
	 * @param accepted whether the payload was stored
	 * @param revision the revision after the attempt
	 * @param detail a human readable status
	 */
	record AdvancementsSaveResult(
			UUID playerId,
			boolean accepted,
			long revision,
			String detail) implements BridgeMessage {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public AdvancementsSaveResult {
			Objects.requireNonNull(playerId, "playerId");
			Objects.requireNonNull(detail, "detail");
		}

		@Override
		public MessageType type() {
			return MessageType.ADVANCEMENTS_SAVE_RESULT;
		}
	}

	/**
	 * Backend to proxy: a chat message a player sent on this backend, to be shown on the others.
	 *
	 * <p>The content is the plain text of the signed chat body; the proxy relays it as an unsigned message
	 * and never forwards signatures, so a relayed line can always be told apart from the sender's own signed
	 * chat.
	 *
	 * @param senderId the sending player
	 * @param senderName the sending player's display name
	 * @param content the plain chat text
	 */
	record ChatRelay(
			UUID senderId,
			String senderName,
			String content) implements BridgeMessage {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public ChatRelay {
			Objects.requireNonNull(senderId, "senderId");
			Objects.requireNonNull(senderName, "senderName");
			Objects.requireNonNull(content, "content");
		}

		@Override
		public MessageType type() {
			return MessageType.CHAT_RELAY;
		}
	}

	/**
	 * Proxy to backend: show this chat message on this server.
	 *
	 * <p>Sent to every backend except the source of the matching {@code CHAT_RELAY}. The receiving backend
	 * displays it to its local players and must not relay it again.
	 *
	 * @param senderId the sending player
	 * @param senderName the sending player's display name
	 * @param content the plain chat text
	 */
	record ChatBroadcast(
			UUID senderId,
			String senderName,
			String content) implements BridgeMessage {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public ChatBroadcast {
			Objects.requireNonNull(senderId, "senderId");
			Objects.requireNonNull(senderName, "senderName");
			Objects.requireNonNull(content, "content");
		}

		@Override
		public MessageType type() {
			return MessageType.CHAT_BROADCAST;
		}
	}
}
