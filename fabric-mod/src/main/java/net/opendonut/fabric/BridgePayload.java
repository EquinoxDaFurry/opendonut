package net.opendonut.fabric;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.opendonut.common.bridge.BridgeCodec;
import net.opendonut.common.model.BridgeSettings;

/**
 * The Minecraft-facing wrapper for an OpenDonut bridge frame.
 *
 * <h2>What this class is, and what it is not</h2>
 * This is deliberately the <em>only</em> place where the bridge meets Minecraft. A Minecraft custom payload
 * carries an opaque byte array; everything about what those bytes mean — authentication, versioning, replay
 * protection — lives in {@link BridgeCodec}, which knows nothing about Minecraft and is therefore testable
 * without a server.
 *
 * <h2>The framing contract, and why it is written down here</h2>
 * <strong>The payload field carries the frame verbatim: byte 0 of the payload is byte 0 of the frame.</strong>
 *
 * <p>This is not a stylistic choice, it is the contract the other end enforces:
 *
 * <ul>
 *   <li>Velocity hands a backend's payload to a proxy plugin exactly as the field arrived —
 *       {@code PluginMessagePacket.decode} is {@code readRetainedSlice(readableBytes())} and its
 *       {@code encode} is {@code writeBytes(...)} — and it puts the bytes a proxy plugin passes to
 *       {@code ServerConnection.sendPluginMessage} straight back into the field.</li>
 *   <li>So the proxy's decoder sees the frame at offset 0, and the mod's decoder sees the frame at offset 0.</li>
 * </ul>
 *
 * <p>An earlier revision wrapped the frame in {@link FriendlyByteBuf#writeByteArray} — a VarInt length prefix —
 * on the sending side only. Every frame then arrived as {@code [len][ODNT…]}, the proxy's magic check read the
 * length byte instead of 'O', and every transfer was refused with {@code BAD_MAGIC}. The payload field is
 * <em>already</em> length-delimited by the packet that contains it, so a second length is both wrong and
 * redundant. {@link #MAX_FRAME_BYTES} is what bounds the read instead.
 *
 * <h2>Direction</h2>
 * <ul>
 *   <li>The backend <em>receives</em> placements and transfer results, so the type is registered as a
 *       serverbound play payload — the proxy delivers its messages through the player's connection, so they
 *       arrive as if the client had sent them.</li>
 *   <li>The backend <em>sends</em> HELLO and transfer requests, so the same type is also registered as a
 *       clientbound play payload.</li>
 * </ul>
 *
 * <p>The client itself needs no mod: the proxy intercepts this channel and never forwards it.
 */
public record BridgePayload(byte[] frame) implements CustomPacketPayload {

	/**
	 * Largest frame accepted off the wire.
	 *
	 * <p>A little more than the codec's own payload limit, so an oversized frame is refused by
	 * {@link BridgeCodec} with a precise reason rather than by a buffer reader.
	 */
	public static final int MAX_FRAME_BYTES =
			BridgeCodec.MAX_PAYLOAD_BYTES + BridgeCodec.HEADER_LENGTH + BridgeCodec.MAC_LENGTH;

	/**
	 * The channel the payload type was registered on.
	 *
	 * <p>Set once by {@link #register(String)} during mod initialisation. Fabric requires payload types to be
	 * registered before any player can connect, which is why the channel is read from the configuration at
	 * initialisation rather than when the server starts. {@link OpenDonutBackend} compares it against the
	 * configuration again at startup and refuses to transfer if they differ, because a mismatch means the proxy
	 * is listening on a channel this backend no longer speaks on.
	 */
	private static volatile Identifier channel;

	/** The payload type, created once by {@link #register(String)}. */
	private static volatile CustomPacketPayload.Type<BridgePayload> type;

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
		return requireType();
	}

	/**
	 * Registers the payload type on a channel, in both directions.
	 *
	 * <p>Must run during mod initialisation, before any player can connect.
	 *
	 * @param channelName the channel in {@code namespace:path} form, from {@code bridge.channel}
	 * @throws IllegalArgumentException if the channel is not in {@code namespace:path} form
	 * @throws IllegalStateException if it is called twice, which would mean two payload types are racing for
	 *         the same registration
	 */
	public static synchronized void register(String channelName) {
		if (type != null) {
			throw new IllegalStateException("the OpenDonut payload type is already registered on "
					+ channel.getNamespace() + ":" + channel.getPath());
		}
		Identifier identifier = parseChannel(channelName);
		CustomPacketPayload.Type<BridgePayload> created = new CustomPacketPayload.Type<>(identifier);
		PayloadTypeRegistry.serverboundPlay().register(created, CODEC);
		PayloadTypeRegistry.clientboundPlay().register(created, CODEC);
		channel = identifier;
		type = created;
	}

	/**
	 * The registered payload type.
	 *
	 * <p>Named {@code payloadType} rather than {@code type} because the interface already declares an
	 * <em>instance</em> method {@code type()}, and a static method cannot share its signature.
	 *
	 * @return the type
	 * @throws IllegalStateException if the mod was not initialised properly
	 */
	public static CustomPacketPayload.Type<BridgePayload> payloadType() {
		return requireType();
	}

	/**
	 * The channel the payload type is registered on, in {@code namespace:path} form.
	 *
	 * @return the channel name
	 */
	public static String channelName() {
		Identifier current = channel;
		return current == null ? "" : current.getNamespace() + ":" + current.getPath();
	}

	/**
	 * Tests whether the payload type has been registered yet.
	 *
	 * @return {@code true} once {@link #register(String)} has run
	 */
	public static boolean isRegistered() {
		return type != null;
	}

	/**
	 * Parses a {@code namespace:path} channel.
	 *
	 * @param channelName the channel name
	 * @return the identifier
	 * @throws IllegalArgumentException if the name is not in the expected form
	 */
	private static Identifier parseChannel(String channelName) {
		if (channelName == null || channelName.indexOf(':') <= 0 || channelName.endsWith(":")) {
			throw new IllegalArgumentException("bridge channel must be in namespace:path form, got '"
					+ channelName + "'; the default is '" + BridgeSettings.DEFAULT_CHANNEL + "'");
		}
		return Identifier.fromNamespaceAndPath(
				channelName.substring(0, channelName.indexOf(':')),
				channelName.substring(channelName.indexOf(':') + 1));
	}

	/**
	 * Fails loudly if a payload is used before initialisation finished.
	 *
	 * @return the registered type
	 */
	private static CustomPacketPayload.Type<BridgePayload> requireType() {
		CustomPacketPayload.Type<BridgePayload> current = type;
		if (current == null) {
			throw new IllegalStateException("the OpenDonut payload type has not been registered yet");
		}
		return current;
	}

	/**
	 * The wire codec: the frame bytes, verbatim, and nothing else.
	 *
	 * <p>See the class documentation for why there is no length prefix here. The read is bounded so that a
	 * hostile or buggy peer cannot make the server allocate an arbitrary buffer: an oversized payload is
	 * refused by throwing, which is the Fabric convention for a codec error and results in the offending
	 * connection being closed rather than the server being exhausted.
	 */
	public static final StreamCodec<RegistryFriendlyByteBuf, BridgePayload> CODEC = StreamCodec.ofMember(
			(payload, buffer) -> buffer.writeBytes(payload.frame()),
			BridgePayload::readFrame);

	/**
	 * Reads a complete frame from the payload field.
	 *
	 * @param buffer the buffer, positioned at the first frame byte
	 * @return the frame
	 * @throws DecoderException if the payload is longer than a frame can be
	 */
	private static BridgePayload readFrame(FriendlyByteBuf buffer) {
		int available = buffer.readableBytes();
		if (available > MAX_FRAME_BYTES) {
			throw new DecoderException("an OpenDonut payload of " + available + " bytes exceeds the maximum "
					+ MAX_FRAME_BYTES + "; either the peer is not speaking this protocol or the frame is corrupt");
		}
		byte[] frame = new byte[available];
		buffer.readBytes(frame);
		return new BridgePayload(frame);
	}

	/**
	 * Describes the payload for a log line without dumping the whole frame.
	 *
	 * @return a short description
	 */
	public String describe() {
		return BridgeCodec.describeFrame(frame);
	}
}
