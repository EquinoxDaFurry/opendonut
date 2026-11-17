package net.opendonut.fabric;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;

import net.opendonut.common.bridge.BridgeCodec;
import net.opendonut.common.bridge.BridgeDecodeResult;
import net.opendonut.common.bridge.BridgeEnvelope;
import net.opendonut.common.bridge.BridgeMessage;
import net.opendonut.common.bridge.DecodeFailure;
import net.opendonut.common.bridge.NonceCache;

/**
 * The backend's end of the bridge: encoding, authentication and dispatch to the backend logic.
 *
 * <h2>Transport choice</h2>
 * The only channel a Fabric mod and a Velocity plugin share is the plugin message channel, so that is what
 * is used. It has three properties worth stating plainly, because they shape the security design:
 *
 * <ol>
 *   <li>Messages are addressed to a <em>player</em>. Velocity's {@code ServerConnection.sendPluginMessage}
 *       needs an established connection, which is why the proxy sends an arrival's coordinates after the
 *       player has connected rather than before.</li>
 *   <li>From the backend's point of view, a plugin message looks like it came from that player's client,
 *       because that is how the proxy delivers it. A modified client can therefore send arbitrary bytes on
 *       this channel.</li>
 *   <li>Nothing is delivered before the player reaches the play phase, so the bridge becomes usable when
 *       the first player joins. The proxy learns who a backend is from that player's HELLO.</li>
 * </ol>
 *
 * <p>Point 2 is why every frame carries an HMAC over a shared secret plus a nonce and a timestamp
 * (see {@link BridgeCodec}). A forged {@code TRANSFER_REQUEST} is rejected before a single field is read,
 * and the proxy — not this side — decides where a player actually goes.
 */
final class BackendBridge {

	/**
	 * The backend's reactions to authenticated proxy messages.
	 *
	 * <p>Implemented by {@link OpenDonutBackend}. Kept as an interface so the transport and the logic can be
	 * read separately, and so the logic is testable without a Minecraft connection.
	 */
	interface Listener {

		/**
		 * The proxy's verdict on this backend's HELLO.
		 *
		 * @param ack the acknowledgement
		 */
		void onHelloAck(BridgeMessage.HelloAck ack);

		/**
		 * The proxy says a player is arriving at these coordinates.
		 *
		 * @param placement the arrival instruction
		 */
		void onPlacePlayer(BridgeMessage.PlacePlayer placement);

		/**
		 * The proxy reports the outcome of a requested transfer.
		 *
		 * @param result the outcome
		 */
		void onTransferResult(BridgeMessage.TransferResult result);

		/**
		 * The proxy answered a player-data load request.
		 *
		 * @param response the response
		 */
		void onPlayerDataLoadResponse(BridgeMessage.PlayerDataLoadResponse response);

		/**
		 * The proxy reported the outcome of a player-data save.
		 *
		 * @param result the outcome
		 */
		void onPlayerDataSaveResult(BridgeMessage.PlayerDataSaveResult result);

		/**
		 * The proxy demands a save of a player's data before moving them.
		 *
		 * @param request the flush request
		 */
		void onPlayerDataFlushRequest(BridgeMessage.PlayerDataFlushRequest request);

		/**
		 * The proxy set the world clock.
		 *
		 * @param set the instruction
		 */
		void onTimeSet(BridgeMessage.TimeSet set);

		/**
		 * The proxy said which dimension an arriving player should be placed in.
		 *
		 * @param dim the instruction
		 */
		void onPlaceDimension(BridgeMessage.PlaceDimension dim);

		/**
		 * The proxy said which blocks to recreate around an arriving player's portal.
		 *
		 * @param portal the instruction
		 */
		void onPlacePortal(BridgeMessage.PlacePortal portal);
	}

	private final byte[] secret;
	private final int toleranceSeconds;
	private final NonceCache nonceCache = new NonceCache();
	private final Logger logger;
	private final Listener listener;

	private long sentFrames;
	private long receivedFrames;
	private long rejectedFrames;

	/**
	 * Creates the bridge.
	 *
	 * @param secret the shared secret, at least {@link BridgeCodec#MIN_SECRET_LENGTH} bytes
	 * @param toleranceSeconds the accepted timestamp window
	 * @param logger the server logger
	 * @param listener the message handler
	 */
	BackendBridge(byte[] secret, int toleranceSeconds, Logger logger, Listener listener) {
		if (secret == null || secret.length < BridgeCodec.MIN_SECRET_LENGTH) {
			throw new IllegalArgumentException("the bridge secret must be at least "
					+ BridgeCodec.MIN_SECRET_LENGTH + " bytes");
		}
		this.secret = secret.clone();
		this.toleranceSeconds = toleranceSeconds;
		this.logger = logger;
		this.listener = listener;
	}

	/**
	 * Sends a message to the proxy through a player's connection.
	 *
	 * <p>The player is the transport, not the recipient: the proxy intercepts the payload on the OpenDonut
	 * channel and never forwards it to the client, which is what allows an unmodified client to play.
	 *
	 * @param player the player whose connection carries the message
	 * @param message the message to send
	 * @return {@code true} if the payload was handed to the connection
	 */
	boolean send(ServerPlayer player, BridgeMessage message) {
		byte[] frame;
		try {
			frame = BridgeCodec.encode(message, secret);
		} catch (RuntimeException e) {
			logger.error("OpenDonut: could not encode a {} message: {}", message.type(), e.toString());
			return false;
		}
		try {
			ServerPlayNetworking.send(player, new BridgePayload(frame));
			sentFrames++;
			logger.debug("OpenDonut: sent {} to the proxy via {}", message.type(), player.getName().getString());
			return true;
		} catch (RuntimeException e) {
			logger.error("OpenDonut: could not send a {} message via {}: {}",
					message.type(), player.getName().getString(), e.toString());
			return false;
		}
	}

	/**
	 * Handles a raw frame received from the proxy.
	 *
	 * <p>The frame is authenticated before anything is interpreted. A rejection is logged with the reason,
	 * and anything that looks like interference is logged at warning level, because a burst of
	 * {@code BAD_SIGNATURE} usually means either a mismatched secret or somebody probing the channel.
	 *
	 * @param player the player whose connection carried the message
	 * @param frame the raw frame
	 */
	void receive(ServerPlayer player, byte[] frame) {
		receivedFrames++;
		BridgeDecodeResult result = BridgeCodec.decode(frame, secret, System.currentTimeMillis(),
				toleranceSeconds, nonceCache);
		if (!result.isAccepted()) {
			rejectedFrames++;
			DecodeFailure failure = ((BridgeDecodeResult.Failed) result).failure();
			String detail = ((BridgeDecodeResult.Failed) result).detail();
			String sender = player == null ? "unknown player" : player.getName().getString();
			if (failure.isSuspicious()) {
				logger.warn("OpenDonut: refused a bridge frame from {} claiming to be {}: {} ({}). "
								+ "If the secret is wrong on one side this is the symptom; fix the secret, "
								+ "otherwise treat it as an attempt to interfere.",
						sender, BridgeCodec.describeFrame(frame), failure, detail);
			} else {
				logger.warn("OpenDonut: refused a bridge frame from {}: {} ({})", sender, failure, detail);
			}
			return;
		}

		BridgeEnvelope envelope = result.envelopeIfAccepted().orElseThrow();
		dispatch(envelope.message());
	}

	/**
	 * Routes a decoded message to its handler.
	 *
	 * @param message the message
	 */
	private void dispatch(BridgeMessage message) {
		switch (message) {
			case BridgeMessage.HelloAck ack -> listener.onHelloAck(ack);
			case BridgeMessage.PlacePlayer placement -> listener.onPlacePlayer(placement);
			case BridgeMessage.TransferResult transferResult -> listener.onTransferResult(transferResult);
			case BridgeMessage.PlayerDataLoadResponse response -> listener.onPlayerDataLoadResponse(response);
			case BridgeMessage.PlayerDataSaveResult result -> listener.onPlayerDataSaveResult(result);
			case BridgeMessage.PlayerDataFlushRequest request -> listener.onPlayerDataFlushRequest(request);
			case BridgeMessage.TimeSet set -> listener.onTimeSet(set);
			case BridgeMessage.PlaceDimension dim -> listener.onPlaceDimension(dim);
			case BridgeMessage.PlacePortal portal -> listener.onPlacePortal(portal);
			case BridgeMessage.TransferPortal ignored -> logger.warn(
					"OpenDonut: the proxy sent a TRANSFER_PORTAL to a backend; only backends report their "
							+ "portal blocks. Ignoring it.");
			case BridgeMessage.TimeReport ignored -> logger.warn(
					"OpenDonut: the proxy sent a TIME_REPORT to a backend; only backends report their clock. "
							+ "Ignoring it.");
			case BridgeMessage.TransferDimension ignored -> logger.warn(
					"OpenDonut: the proxy sent a TRANSFER_DIMENSION to a backend; only backends report their "
							+ "dimension. Ignoring it.");
			case BridgeMessage.Hello ignored -> logger.warn(
					"OpenDonut: the proxy sent a HELLO to a backend; that message only travels the other way. "
							+ "Ignoring it.");
			case BridgeMessage.TransferRequest ignored -> logger.warn(
					"OpenDonut: the proxy sent a TRANSFER_REQUEST to a backend; only backends send requests. "
							+ "Ignoring it.");
			case BridgeMessage.PlayerDataLoadRequest ignored -> logger.warn(
					"OpenDonut: the proxy sent a PLAYER_DATA_LOAD_REQUEST to a backend; only backends ask for "
							+ "data. Ignoring it.");
			case BridgeMessage.PlayerDataSaveRequest ignored -> logger.warn(
					"OpenDonut: the proxy sent a PLAYER_DATA_SAVE_REQUEST to a backend; only backends save "
							+ "data. Ignoring it.");
		}
	}

	/**
	 * Builds the HELLO describing this backend.
	 *
	 * @param serverId the id this backend believes it is
	 * @param worldName the loaded level name
	 * @param seed the world's actual seed, or {@link BridgeMessage.Hello#UNKNOWN_SEED}
	 * @param bounds the region this backend believes it owns
	 * @param minecraftVersion the running Minecraft version
	 * @param openDonutVersion the OpenDonut build version
	 * @param advertisedAddress the address this backend listens on
	 * @return the message
	 */
	static BridgeMessage.Hello hello(
			String serverId,
			String worldName,
			long seed,
			net.opendonut.common.model.RegionBounds bounds,
			String minecraftVersion,
			String openDonutVersion,
			String advertisedAddress) {
		return new BridgeMessage.Hello(serverId, worldName, seed, bounds, minecraftVersion,
				openDonutVersion, advertisedAddress);
	}

	/**
	 * Builds a transfer request from a player's current state.
	 *
	 * @param player the player
	 * @param fromServerId this backend's id
	 * @param requestedServerId the destination this backend believes is correct
	 * @param reason a short explanation for the log
	 * @return the message
	 */
	static BridgeMessage.TransferRequest transferRequest(
			ServerPlayer player, String fromServerId, String requestedServerId, String reason) {
		Entity entity = player;
		return new BridgeMessage.TransferRequest(
				entity.getUUID(),
				entity.getName().getString(),
				fromServerId,
				entity.blockPosition().getX(),
				entity.blockPosition().getY(),
				entity.blockPosition().getZ(),
				entity.getYRot(),
				entity.getXRot(),
				requestedServerId,
				reason);
	}

	/**
	 * Builds the dimension report for a transfer request, from the player's current level.
	 *
	 * <p>Sent on the same connection immediately before the {@code TRANSFER_REQUEST}, so the proxy can attach
	 * it to that transfer; the pair is ordered, which is what keeps the dimension from landing on the wrong
	 * transfer.
	 *
	 * @param player the player leaving
	 * @return the message
	 */
	static BridgeMessage.TransferDimension transferDimension(ServerPlayer player) {
		return new BridgeMessage.TransferDimension(
				player.getUUID(),
				player.level().dimension().identifier().toString());
	}

	/**
	 * How far around the arrival point the portal-block box reaches, horizontally. A nether portal frame is at
	 * most 4 wide, so 3 covers it with margin even if the player drifted a block past the frame before the
	 * boundary check fired.
	 */
	private static final int PORTAL_SCAN_RADIUS = 3;

	/** How far below and above the arrival point the box reaches. */
	private static final int PORTAL_SCAN_DOWN = 3;
	private static final int PORTAL_SCAN_UP = 5;

	/** The most blocks a single portal payload may carry; larger boxes are not sent at all. */
	private static final int PORTAL_MAX_BLOCKS = 256;

	/**
	 * Captures the blocks around a player who just went through a portal and landed outside this region.
	 *
	 * <p>The backends have separate world folders, so the far side of a boundary-straddling portal exists only
	 * here: this box is what the destination recreates so the portal exists on both sides. Only non-air,
	 * non-liquid blocks are included — terrain is identical on both servers anyway (same seed), and liquids
	 * must not be copied because they would start flowing on the destination.
	 *
	 * @param player the player, standing at the far side of the portal
	 * @return the message, or empty when there is nothing worth replicating (a plain walk across the boundary)
	 */
	static Optional<BridgeMessage.TransferPortal> capturePortal(ServerPlayer player) {
		Level level = player.level();
		BlockPos origin = player.blockPosition();
		List<BridgeMessage.BlockEntry> blocks = new ArrayList<>();
		for (int dx = -PORTAL_SCAN_RADIUS; dx <= PORTAL_SCAN_RADIUS; dx++) {
			for (int dy = -PORTAL_SCAN_DOWN; dy <= PORTAL_SCAN_UP; dy++) {
				for (int dz = -PORTAL_SCAN_RADIUS; dz <= PORTAL_SCAN_RADIUS; dz++) {
					BlockPos pos = origin.offset(dx, dy, dz);
					BlockState state = level.getBlockState(pos);
					FluidState fluid = state.getFluidState();
					if (state.isAir() || !fluid.isEmpty()) {
						continue;
					}
					try {
						blocks.add(new BridgeMessage.BlockEntry(
								dx, dy, dz, PlayerDataCapture.encodeNbt(NbtUtils.writeBlockState(state))));
					} catch (IOException e) {
						// In-memory serialisation cannot fail; if it ever does, skip the block rather than
						// the whole payload.
						continue;
					}
					if (blocks.size() > PORTAL_MAX_BLOCKS) {
						return Optional.empty();
					}
				}
			}
		}
		if (blocks.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new BridgeMessage.TransferPortal(
				player.getUUID(),
				new BridgeMessage.PortalBlocks(
						level.dimension().identifier().toString(),
						origin.getX(), origin.getY(), origin.getZ(),
						blocks)));
	}

	/** @return how many frames this backend has sent */
	long sentFrames() {
		return sentFrames;
	}

	/** @return how many frames this backend has accepted and handled */
	long receivedFrames() {
		return receivedFrames;
	}

	/** @return how many frames this backend has refused */
	long rejectedFrames() {
		return rejectedFrames;
	}

	/** @return how many nonces are currently remembered for replay protection */
	int replayCacheSize() {
		return nonceCache.size();
	}

	/**
	 * The secret's fingerprint, for comparing two machines without printing the secret.
	 *
	 * @return eight hex characters
	 */
	String secretFingerprint() {
		return net.opendonut.common.bridge.BridgeSecret.fingerprint(secret);
	}

	/**
	 * Forgets all remembered nonces.
	 *
	 * <p>Called when the secret is replaced, because nonces minted under the old secret must not shadow the
	 * new one.
	 */
	void clearReplayCache() {
		nonceCache.clear();
	}

	/** @return the configured timestamp tolerance, for diagnostics */
	int toleranceSeconds() {
		return toleranceSeconds;
	}

	/**
	 * Looks a message up in the replay cache without registering it, for diagnostics.
	 *
	 * @param frame the frame
	 * @return the announced message type, or empty
	 */
	static Optional<net.opendonut.common.bridge.MessageType> peekType(byte[] frame) {
		return BridgeCodec.peekType(frame);
	}
}
