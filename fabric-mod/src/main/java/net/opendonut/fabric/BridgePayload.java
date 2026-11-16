package net.opendonut.fabric;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.opendonut.common.bridge.BridgeCodec;

/**
 * The Minecraft-facing wrapper for an OpenDonut bridge frame.
 *
 * <h2>What this class is, and what it is not</h2>
 * This is deliberately the <em>only</em> place where the bridge meets Minecraft. A Minecraft custom
 * payload carries an opaque byte array; everything about what those bytes mean — authentication,
 * versioning, replay protection — lives in {@link BridgeCodec}, which knows nothing about Minecraft and
 * is therefore testable without a server. Keeping the boundary this thin is what allows the protocol to
 * be unit tested, and what keeps a Minecraft update from being able to corrupt the protocol's semantics.
 *
 * <h2>Why the bytes are wrapped rather than parsed</h2>
 * The frame is verified (HMAC, timestamp, nonce) before any field is interpreted. If the payload were
 * decoded straight into Minecraft-typed fields, the parser would run on unauthenticated input.
 *
 * <h2>Direction</h2>
 * <ul>
 *   <li>The backend <em>receives</em> placements and transfer results, so the type is registered as a
 *       serverbound play payload.</li>
 *   <li>The backend <em>sends</em> HELLO and transfer requests, so the same type is also registered as a
 *       clientbound play payload.</li>
 * </ul>
 *
 * <p>Note that a plugin message from the backend is addressed to the <em>player</em>. The proxy intercepts
 * it on the channel a Velocity plugin has registered and never forwards it to the client, which is why
 * the client needs no mod and why an unmodified client can play on the network.
 *
 * @param frame the complete, already authenticated bridge frame
 */
public record BridgePayload(byte[] frame) implements CustomPacketPayload {

	/** The channel OpenDonut uses. Must match {@code bridge.channel} in the configuration. */
	public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("opendonut", "bridge");

	/** The payload type registered with Fabric's networking API. */
	public static final CustomPacketPayload.Type<BridgePayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);

	/**
	 * Largest frame accepted off the wire.
	 *
	 * <p>A little more than the codec's own payload limit, so that an oversized frame is refused by the
	 * codec (with a precise reason) rather than by the buffer reader.
	 */
	private static final int MAX_FRAME_BYTES =
			BridgeCodec.MAX_PAYLOAD_BYTES + BridgeCodec.HEADER_LENGTH + BridgeCodec.MAC_LENGTH;

	/**
	 * Copies the frame so the record cannot be mutated from outside.
	 *
	 * @param frame the frame bytes
	 */
	public BridgePayload {
		frame = frame.clone();
	}

	@Override
	public byte[] frame() {
		return frame.clone();
	}

	@Override
	public CustomPacketPayload.Type<BridgePayload> type() {
		return TYPE;
	}

	/**
	 * Registers the payload type in both directions.
	 *
	 * <p>Must run during mod initialisation, before any player can connect.
	 */
	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
	}

	/**
	 * The wire codec: a length-prefixed byte array, nothing more.
	 *
	 * <p>The length is bounded so that a hostile or buggy peer cannot make the server allocate an
	 * arbitrary buffer. The bound is enforced again inside the protocol codec, which is the version that
	 * matters for security; this one only keeps the buffer reader honest.
	 */
	public static final StreamCodec<RegistryFriendlyByteBuf, BridgePayload> CODEC = StreamCodec.ofMember(
			(payload, buffer) -> buffer.writeByteArray(payload.frame()),
			buffer -> new BridgePayload(buffer.readByteArray(MAX_FRAME_BYTES)));

	/**
	 * Describes the payload for a log line without dumping the whole frame.
	 *
	 * @return a short description
	 */
	public String describe() {
		return BridgeCodec.describeFrame(frame);
	}
}
