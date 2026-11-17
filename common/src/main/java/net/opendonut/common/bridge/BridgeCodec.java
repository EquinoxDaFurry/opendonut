package net.opendonut.common.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import net.opendonut.common.model.RegionBounds;

/**
 * Encodes and authenticates OpenDonut bridge frames.
 *
 * <h2>Frame layout</h2>
 * <pre>
 *   offset  size  field
 *   ------  ----  -----------------------------------------------------------
 *        0     4  magic 'O' 'D' 'N' 'T'
 *        4     1  protocol version (1)
 *        5     1  message type id (see MessageType)
 *        6     4  payload length, big endian, excluding magic/header/tag
 *       10   N*1  payload: nonce(16) | timestamp(8) | message fields
 *  10+N   32*1   HMAC-SHA256 over bytes 0 .. 10+N-1
 * </pre>
 *
 * <h2>Why this shape</h2>
 * <ul>
 *   <li><strong>Signature before parsing.</strong> The tag is verified before a single payload byte is
 *       interpreted, so unauthenticated input never reaches a parser. {@link #peekType(byte[])} exists
 *       for logging a rejected frame, and it only reads the two header bytes it announces.</li>
 *   <li><strong>Timestamp plus nonce.</strong> The timestamp bounds how long a captured frame stays
 *       useful; the nonce makes every frame unique, so a duplicate inside that window is caught by
 *       {@link NonceCache}. Either alone is insufficient: a timestamp admits replays inside its
 *       tolerance, and a nonce alone forces the receiver to remember every nonce forever.</li>
 *   <li><strong>Fixed field order, no reflection, no JSON.</strong> The same bytes are produced by every
 *       implementation, the format is trivially diffable and testable, and there is no parser surface for
 *       a hostile payload to attack.</li>
 *   <li><strong>Explicit version.</strong> A backend on an older build is rejected with
 *       {@link DecodeFailure#UNSUPPORTED_VERSION} instead of being misread.</li>
 * </ul>
 *
 * <h2>What this does not protect against</h2>
 * This authenticates the <em>proxy</em> to the <em>backend</em> and vice versa. It does not encrypt, and
 * it does not protect against a compromised proxy or a leaked secret file. The secret is therefore a
 * normal secret: keep it out of version control, out of chat, and out of backups that are shared.
 */
public final class BridgeCodec {

	/** Frame magic. Deliberately four printable characters so a hex dump is recognisable. */
	public static final byte[] MAGIC = {'O', 'D', 'N', 'T'};

	/** The protocol revision this build speaks. */
	public static final int PROTOCOL_VERSION = 1;

	/** Length of the anti-replay nonce. */
	public static final int NONCE_LENGTH = 16;

	/** Length of the HMAC-SHA256 tag. */
	public static final int MAC_LENGTH = 32;

	/** Length of the fixed header: magic, version, type, payload length. */
	public static final int HEADER_LENGTH = MAGIC.length + 1 + 1 + 4;

	/**
	 * Largest payload accepted, in bytes.
	 *
	 * <p>A {@code PlacePlayer} frame is well under 200 bytes, so 64 KiB is generous headroom for later
	 * additions while still refusing an attempt to make the proxy allocate a huge buffer.
	 */
	public static final int MAX_PAYLOAD_BYTES = 64 * 1024;

	/** Shortest secret that is accepted. Shorter secrets are refused rather than silently used. */
	public static final int MIN_SECRET_LENGTH = 16;

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	private static final SecureRandom RANDOM = new SecureRandom();

	private BridgeCodec() {
	}

	/**
	 * Encodes a message with a fresh nonce and the current time.
	 *
	 * @param message the message to encode
	 * @param secret the shared secret
	 * @return the complete frame, ready to hand to a plugin message channel
	 */
	public static byte[] encode(BridgeMessage message, byte[] secret) {
		return encode(message, secret, System.currentTimeMillis(), randomNonce());
	}

	/**
	 * Encodes a message with an explicit nonce and timestamp, for tests and for deterministic tooling.
	 *
	 * @param message the message to encode
	 * @param secret the shared secret
	 * @param timestampMillis the timestamp to stamp into the frame
	 * @param nonce the nonce, must be exactly {@value #NONCE_LENGTH} bytes
	 * @return the complete frame
	 */
	public static byte[] encode(BridgeMessage message, byte[] secret, long timestampMillis, byte[] nonce) {
		requireUsableSecret(secret);
		if (nonce.length != NONCE_LENGTH) {
			throw new IllegalArgumentException("nonce must be " + NONCE_LENGTH + " bytes, got " + nonce.length);
		}

		byte[] payload = encodePayload(message, timestampMillis, nonce);
		if (payload.length > MAX_PAYLOAD_BYTES) {
			throw new IllegalArgumentException(
					"encoded payload of " + payload.length + " bytes exceeds the " + MAX_PAYLOAD_BYTES
							+ " byte limit");
		}

		try {
			ByteArrayOutputStream frame = new ByteArrayOutputStream(HEADER_LENGTH + payload.length + MAC_LENGTH);
			DataOutputStream out = new DataOutputStream(frame);
			out.write(MAGIC);
			out.writeByte(PROTOCOL_VERSION);
			out.writeByte(message.type().wireId());
			out.writeInt(payload.length);
			out.write(payload);
			out.flush();

			byte[] signed = frame.toByteArray();
			byte[] tag = mac(secret, signed);
			frame.write(tag);
			return frame.toByteArray();
		} catch (IOException e) {
			// ByteArrayOutputStream does not throw; this cannot happen and is not worth propagating.
			throw new IllegalStateException("failed to encode a bridge frame in memory", e);
		}
	}

	/**
	 * Decodes and authenticates a frame.
	 *
	 * @param frame the received bytes, or {@code null}
	 * @param secret the shared secret
	 * @param nowMillis the receiver's current time, in epoch milliseconds
	 * @param toleranceSeconds how far a frame timestamp may deviate from {@code nowMillis}
	 * @param nonceCache the replay cache, or {@code null} to skip replay protection (not recommended)
	 * @return the decode result
	 */
	public static BridgeDecodeResult decode(
			byte[] frame,
			byte[] secret,
			long nowMillis,
			int toleranceSeconds,
			NonceCache nonceCache) {

		if (secret == null || secret.length < MIN_SECRET_LENGTH) {
			return BridgeDecodeResult.failed(DecodeFailure.INVALID_SECRET,
					"the shared secret is missing or shorter than " + MIN_SECRET_LENGTH + " bytes");
		}
		if (frame == null || frame.length < HEADER_LENGTH + MAC_LENGTH) {
			return BridgeDecodeResult.failed(DecodeFailure.TRUNCATED,
					"frame is shorter than the minimum " + (HEADER_LENGTH + MAC_LENGTH) + " bytes");
		}
		if (!startsWithMagic(frame)) {
			return BridgeDecodeResult.failed(DecodeFailure.BAD_MAGIC,
					"frame does not start with the OpenDonut magic bytes");
		}

		int version = frame[4] & 0xFF;
		if (version != PROTOCOL_VERSION) {
			return BridgeDecodeResult.failed(DecodeFailure.UNSUPPORTED_VERSION,
					"frame uses protocol version " + version + " but this build speaks " + PROTOCOL_VERSION);
		}

		int typeId = frame[5] & 0xFF;
		Optional<MessageType> maybeType = MessageType.byWireId(typeId);
		if (maybeType.isEmpty()) {
			return BridgeDecodeResult.failed(DecodeFailure.UNKNOWN_MESSAGE_TYPE,
					"frame declares message type " + typeId + ", which this build does not know");
		}
		MessageType type = maybeType.get();

		int payloadLength = readInt(frame, 6);
		if (payloadLength < NONCE_LENGTH + Long.BYTES || payloadLength > MAX_PAYLOAD_BYTES) {
			return BridgeDecodeResult.failed(DecodeFailure.PAYLOAD_TOO_LARGE,
					"frame declares a payload of " + payloadLength + " bytes, outside the accepted range "
							+ (NONCE_LENGTH + Long.BYTES) + ".." + MAX_PAYLOAD_BYTES);
		}

		int expectedLength = HEADER_LENGTH + payloadLength + MAC_LENGTH;
		if (frame.length != expectedLength) {
			return BridgeDecodeResult.failed(DecodeFailure.TRUNCATED,
					"frame is " + frame.length + " bytes but declares " + expectedLength);
		}

		// Authenticate before parsing. Everything past this point is trusted.
		byte[] tag = mac(secret, Arrays.copyOf(frame, HEADER_LENGTH + payloadLength));
		if (!MessageDigest.isEqual(tag, Arrays.copyOfRange(frame, HEADER_LENGTH + payloadLength, frame.length))) {
			return BridgeDecodeResult.failed(DecodeFailure.BAD_SIGNATURE,
					"the frame signature does not verify for message type " + type
							+ "; the secret differs between proxy and backend, or the frame was tampered with");
		}

		byte[] payload = Arrays.copyOfRange(frame, HEADER_LENGTH, HEADER_LENGTH + payloadLength);

		long timestamp;
		byte[] nonce;
		BridgeMessage message;
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
			nonce = new byte[NONCE_LENGTH];
			in.readFully(nonce);
			timestamp = in.readLong();
			message = decodeBody(in, type);
			if (in.available() != 0) {
				return BridgeDecodeResult.failed(DecodeFailure.MALFORMED_PAYLOAD,
						"frame for " + type + " has " + in.available() + " unexpected trailing bytes");
			}
		} catch (EOFException e) {
			return BridgeDecodeResult.failed(DecodeFailure.MALFORMED_PAYLOAD,
					"frame for " + type + " ended before all fields were read");
		} catch (IOException | RuntimeException e) {
			return BridgeDecodeResult.failed(DecodeFailure.MALFORMED_PAYLOAD,
					"frame for " + type + " could not be parsed: " + e);
		}

		long skewMillis = Math.abs(nowMillis - timestamp);
		long toleranceMillis = toleranceSeconds * 1000L;
		if (skewMillis > toleranceMillis) {
			return BridgeDecodeResult.failed(DecodeFailure.STALE_TIMESTAMP,
					"frame timestamp is " + skewMillis + " ms away from the local clock, which exceeds the "
							+ toleranceSeconds + " second tolerance; check that proxy and backends agree on "
							+ "the time");
		}

		if (nonceCache != null && !nonceCache.register(nonce, nowMillis + toleranceMillis)) {
			return BridgeDecodeResult.failed(DecodeFailure.REPLAY,
					"this frame has already been handled (duplicate delivery or replayed capture)");
		}

		return BridgeDecodeResult.ok(new BridgeEnvelope(message, timestamp, nonce));
	}

	/**
	 * Reads the announced message type of a frame without authenticating it.
	 *
	 * <p>Use this only to make a rejection message more informative — "an unauthenticated frame claiming
	 * to be a TRANSFER_REQUEST was refused". Nothing that affects routing may be based on the result.
	 *
	 * @param frame the received bytes, or {@code null}
	 * @return the announced type, or empty when the frame is too short or malformed
	 */
	public static Optional<MessageType> peekType(byte[] frame) {
		if (frame == null || frame.length < HEADER_LENGTH || !startsWithMagic(frame)) {
			return Optional.empty();
		}
		return MessageType.byWireId(frame[5] & 0xFF);
	}

	/**
	 * Generates a fresh nonce.
	 *
	 * @return a new {@value #NONCE_LENGTH} byte nonce
	 */
	public static byte[] randomNonce() {
		byte[] nonce = new byte[NONCE_LENGTH];
		RANDOM.nextBytes(nonce);
		return nonce;
	}

	/**
	 * Checks that a secret is usable, throwing a message intended for the log when it is not.
	 *
	 * @param secret the candidate secret
	 */
	private static void requireUsableSecret(byte[] secret) {
		if (secret == null) {
			throw new IllegalArgumentException("the shared secret must not be null");
		}
		if (secret.length < MIN_SECRET_LENGTH) {
			throw new IllegalArgumentException(
					"the shared secret must be at least " + MIN_SECRET_LENGTH + " bytes, got " + secret.length);
		}
	}

	/**
	 * Tests the frame magic without allocating.
	 *
	 * @param frame the frame
	 * @return {@code true} if the frame starts with {@link #MAGIC}
	 */
	private static boolean startsWithMagic(byte[] frame) {
		for (int i = 0; i < MAGIC.length; i++) {
			if (frame[i] != MAGIC[i]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Reads a big-endian int from a byte array.
	 *
	 * @param source the array
	 * @param offset the offset
	 * @return the decoded value
	 */
	private static int readInt(byte[] source, int offset) {
		return ((source[offset] & 0xFF) << 24)
				| ((source[offset + 1] & 0xFF) << 16)
				| ((source[offset + 2] & 0xFF) << 8)
				| (source[offset + 3] & 0xFF);
	}

	/**
	 * Computes the authentication tag.
	 *
	 * @param secret the shared secret
	 * @param signed the bytes to sign
	 * @return the tag
	 */
	private static byte[] mac(byte[] secret, byte[] signed) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
			return mac.doFinal(signed);
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			// HmacSHA256 is mandated by the Java platform; an invalid key is already prevented above.
			throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
		}
	}

	/**
	 * Serialises a message body, including the nonce and timestamp preamble.
	 *
	 * @param message the message
	 * @param timestampMillis the timestamp
	 * @param nonce the nonce
	 * @return the payload bytes
	 */
	private static byte[] encodePayload(BridgeMessage message, long timestampMillis, byte[] nonce) {
		try {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
			DataOutputStream out = new DataOutputStream(buffer);
			out.write(nonce);
			out.writeLong(timestampMillis);

			switch (message) {
				case BridgeMessage.Hello hello -> {
					out.writeUTF(hello.serverId());
					out.writeUTF(hello.worldName());
					out.writeLong(hello.seed());
					writeBounds(out, hello.bounds());
					out.writeUTF(hello.minecraftVersion());
					out.writeUTF(hello.openDonutVersion());
					out.writeUTF(hello.advertisedAddress());
				}
				case BridgeMessage.HelloAck ack -> {
					out.writeUTF(ack.serverId());
					out.writeBoolean(ack.accepted());
					out.writeUTF(ack.detail());
					out.writeLong(ack.resolvedSeed());
				}
				case BridgeMessage.TransferRequest request -> {
					writeUuid(out, request.playerId());
					out.writeUTF(request.playerName());
					out.writeUTF(request.fromServerId());
					out.writeInt(request.globalX());
					out.writeInt(request.globalY());
					out.writeInt(request.globalZ());
					out.writeFloat(request.yaw());
					out.writeFloat(request.pitch());
					out.writeUTF(request.requestedServerId());
					out.writeUTF(request.reason());
				}
				case BridgeMessage.TransferResult result -> {
					writeUuid(out, result.playerId());
					out.writeUTF(result.fromServerId());
					out.writeUTF(result.toServerId());
					out.writeBoolean(result.success());
					out.writeUTF(result.statusMessage());
					out.writeInt(result.globalX());
					out.writeInt(result.globalY());
					out.writeInt(result.globalZ());
				}
				case BridgeMessage.PlacePlayer place -> {
					writeUuid(out, place.playerId());
					out.writeUTF(place.fromServerId());
					out.writeUTF(place.toServerId());
					out.writeInt(place.globalX());
					out.writeInt(place.globalY());
					out.writeInt(place.globalZ());
					out.writeFloat(place.yaw());
					out.writeFloat(place.pitch());
					out.writeLong(place.issuedAtMillis());
				}
				case BridgeMessage.PlayerDataLoadRequest request -> {
					writeUuid(out, request.playerId());
					out.writeUTF(request.serverId());
				}
				case BridgeMessage.PlayerDataLoadResponse response -> {
					writeUuid(out, response.playerId());
					out.writeBoolean(response.present());
					out.writeLong(response.revision());
					writeByteArray(out, response.envelope());
					out.writeUTF(response.detail());
				}
				case BridgeMessage.PlayerDataSaveRequest request -> {
					writeUuid(out, request.playerId());
					out.writeUTF(request.serverId());
					out.writeLong(request.baseRevision());
					writeByteArray(out, request.envelope());
				}
				case BridgeMessage.PlayerDataSaveResult result -> {
					writeUuid(out, result.playerId());
					out.writeBoolean(result.accepted());
					out.writeLong(result.revision());
					out.writeUTF(result.detail());
				}
				case BridgeMessage.PlayerDataFlushRequest request -> {
					writeUuid(out, request.playerId());
					out.writeUTF(request.fromServerId());
					out.writeUTF(request.toServerId());
				}
			}
			out.flush();
			return buffer.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("failed to encode a bridge payload in memory", e);
		}
	}

	/**
	 * Deserialises a message body whose nonce and timestamp have already been read.
	 *
	 * @param in the stream positioned at the first message field
	 * @param type the message type announced by the frame
	 * @return the decoded message
	 * @throws IOException if the payload runs out early
	 */
	private static BridgeMessage decodeBody(DataInputStream in, MessageType type) throws IOException {
		return switch (type) {
			case HELLO -> new BridgeMessage.Hello(
					in.readUTF(), in.readUTF(), in.readLong(), readBounds(in), in.readUTF(), in.readUTF(),
					in.readUTF());
			case HELLO_ACK -> new BridgeMessage.HelloAck(
					in.readUTF(), in.readBoolean(), in.readUTF(), in.readLong());
			case TRANSFER_REQUEST -> new BridgeMessage.TransferRequest(
					readUuid(in), in.readUTF(), in.readUTF(), in.readInt(), in.readInt(), in.readInt(),
					in.readFloat(), in.readFloat(), in.readUTF(), in.readUTF());
			case TRANSFER_RESULT -> new BridgeMessage.TransferResult(
					readUuid(in), in.readUTF(), in.readUTF(), in.readBoolean(), in.readUTF(),
					in.readInt(), in.readInt(), in.readInt());
			case PLACE_PLAYER -> new BridgeMessage.PlacePlayer(
					readUuid(in), in.readUTF(), in.readUTF(), in.readInt(), in.readInt(), in.readInt(),
					in.readFloat(), in.readFloat(), in.readLong());
			case PLAYER_DATA_LOAD_REQUEST -> new BridgeMessage.PlayerDataLoadRequest(
					readUuid(in), in.readUTF());
			case PLAYER_DATA_LOAD_RESPONSE -> new BridgeMessage.PlayerDataLoadResponse(
					readUuid(in), in.readBoolean(), in.readLong(), readByteArray(in), in.readUTF());
			case PLAYER_DATA_SAVE_REQUEST -> new BridgeMessage.PlayerDataSaveRequest(
					readUuid(in), in.readUTF(), in.readLong(), readByteArray(in));
			case PLAYER_DATA_SAVE_RESULT -> new BridgeMessage.PlayerDataSaveResult(
					readUuid(in), in.readBoolean(), in.readLong(), in.readUTF());
			case PLAYER_DATA_FLUSH_REQUEST -> new BridgeMessage.PlayerDataFlushRequest(
					readUuid(in), in.readUTF(), in.readUTF());
		};
	}

	/**
	 * Writes region bounds.
	 *
	 * @param out the stream
	 * @param bounds the bounds
	 * @throws IOException if the stream fails
	 */
	private static void writeBounds(DataOutputStream out, RegionBounds bounds) throws IOException {
		out.writeInt(bounds.minX());
		out.writeInt(bounds.maxX());
		out.writeInt(bounds.minZ());
		out.writeInt(bounds.maxZ());
	}

	/**
	 * Reads region bounds.
	 *
	 * @param in the stream
	 * @return the bounds
	 * @throws IOException if the stream fails or the bounds are inverted
	 */
	private static RegionBounds readBounds(DataInputStream in) throws IOException {
		int minX = in.readInt();
		int maxX = in.readInt();
		int minZ = in.readInt();
		int maxZ = in.readInt();
		try {
			return new RegionBounds(minX, maxX, minZ, maxZ);
		} catch (IllegalArgumentException e) {
			throw new IOException("frame carries inverted region bounds: " + e.getMessage(), e);
		}
	}

	/**
	 * Writes a UUID as two longs.
	 *
	 * @param out the stream
	 * @param uuid the uuid
	 * @throws IOException if the stream fails
	 */
	private static void writeUuid(DataOutputStream out, UUID uuid) throws IOException {
		out.writeLong(uuid.getMostSignificantBits());
		out.writeLong(uuid.getLeastSignificantBits());
	}

	/**
	 * Reads a UUID written by {@link #writeUuid}.
	 *
	 * @param in the stream
	 * @return the uuid
	 * @throws IOException if the stream fails
	 */
	private static UUID readUuid(DataInputStream in) throws IOException {
		return new UUID(in.readLong(), in.readLong());
	}

	/**
	 * Writes a length-prefixed byte array <em>inside</em> an authenticated frame payload.
	 *
	 * <p>This is not the framing mistake the {@code BridgePayload} documentation warns about: that contract
	 * is about the outermost Minecraft payload field, which carries the frame verbatim. Inside the frame,
	 * every field is delimited by the frame's own payload length, and a length prefix here is what lets the
	 * reader refuse an oversized array before allocating it.
	 *
	 * @param out the stream
	 * @param bytes the bytes
	 * @throws IOException if the stream fails
	 */
	private static void writeByteArray(DataOutputStream out, byte[] bytes) throws IOException {
		out.writeInt(bytes.length);
		out.write(bytes);
	}

	/**
	 * Reads a byte array written by {@link #writeByteArray}, refusing to allocate more than the payload
	 * actually holds.
	 *
	 * @param in the stream positioned at the length
	 * @return the bytes
	 * @throws IOException if the declared length is negative or larger than the remaining payload
	 */
	private static byte[] readByteArray(DataInputStream in) throws IOException {
		int length = in.readInt();
		if (length < 0 || length > in.available()) {
			throw new IOException("frame declares a byte array of " + length + " bytes but only "
					+ in.available() + " payload bytes remain");
		}
		byte[] bytes = new byte[length];
		in.readFully(bytes);
		return bytes;
	}

	/**
	 * Renders a frame's header for logs, without decoding the body.
	 *
	 * <h2>Why this is fussy about unprintable bytes</h2>
	 * This method exists to make a transport failure diagnosable from one log line, so it must never render a
	 * payload that is <em>not</em> a frame as if it were one. An earlier revision printed the first four bytes
	 * as ASCII unconditionally, so a payload that had been wrapped in a length prefix appeared in the log as
	 * {@code ���OD v78 type#84} — which is technically the truth and completely unreadable. Non-magic frames are
	 * now shown as hex, and a payload that carries a valid frame behind a VarInt length prefix is called out
	 * explicitly, because that exact mistake has been made here once already.
	 *
	 * @param frame the frame, may be {@code null}
	 * @return a short description, such as {@code ODNT v1 TRANSFER_REQUEST 148 bytes} or
	 *         {@code not an OpenDonut frame: first bytes 86 01 4F 44 …}
	 */
	public static String describeFrame(byte[] frame) {
		if (frame == null) {
			return "(null frame)";
		}
		if (frame.length < MAGIC.length) {
			return frame.length + " byte(s), shorter than the " + MAGIC.length + "-byte magic";
		}

		if (!startsWithMagic(frame)) {
			return "not an OpenDonut frame: first bytes " + toHex(frame, Math.min(8, frame.length))
					+ lengthPrefixHint(frame);
		}

		if (frame.length < HEADER_LENGTH) {
			return "ODNT followed by " + (frame.length - MAGIC.length)
					+ " byte(s), too few for a header";
		}

		String type = MessageType.byWireId(frame[5] & 0xFF)
				.map(MessageType::name)
				.orElse("type#" + (frame[5] & 0xFF));
		return "ODNT v" + (frame[4] & 0xFF) + " " + type + " " + frame.length + " bytes";
	}

	/**
	 * Renders bytes as space separated hex.
	 *
	 * @param bytes the bytes
	 * @param length how many to render
	 * @return the hex text
	 */
	private static String toHex(byte[] bytes, int length) {
		StringBuilder out = new StringBuilder(length * 3);
		for (int i = 0; i < length; i++) {
			if (i > 0) {
				out.append(' ');
			}
			out.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16))
					.append(Character.forDigit(bytes[i] & 0xF, 16));
		}
		if (bytes.length > length) {
			out.append(" …");
		}
		return out.toString();
	}

	/**
	 * Recognises a frame that has been wrapped in a VarInt length prefix, and explains the mistake.
	 *
	 * <p>The check is: read a VarInt at offset 0, then look for the magic immediately after it. A peer that
	 * does that is adding its own length to a field that the Minecraft packet already delimits, which puts
	 * every frame out of alignment and produces exactly one symptom — {@link DecodeFailure#BAD_MAGIC} — no
	 * matter how correct the secret and the channel are.
	 *
	 * @param frame a frame that failed the magic check
	 * @return an explanatory suffix, or an empty string when the payload does not look length-prefixed
	 */
	private static String lengthPrefixHint(byte[] frame) {
		int offset = 0;
		long declaredLength = 0L;
		int shift = 0;
		while (offset < frame.length && offset < 5) {
			int value = frame[offset] & 0xFF;
			declaredLength |= (long) (value & 0x7F) << shift;
			offset++;
			if ((value & 0x80) == 0) {
				break;
			}
			shift += 7;
		}
		if (offset == 0 || offset > 5 || frame.length - offset < MAGIC.length
				|| !startsWithMagicAt(frame, offset)) {
			return "";
		}
		return "; it looks like a VarInt length prefix (" + declaredLength + " byte(s)) in front of a valid "
				+ "frame. The Minecraft payload field already delimits the frame, so it must be written "
				+ "verbatim — see the framing contract in the bridge payload codec.";
	}

	/**
	 * Tests for the magic bytes at an offset.
	 *
	 * @param frame the payload
	 * @param offset where to look
	 * @return {@code true} if the magic is there
	 */
	private static boolean startsWithMagicAt(byte[] frame, int offset) {
		for (int i = 0; i < MAGIC.length; i++) {
			if (frame[offset + i] != MAGIC[i]) {
				return false;
			}
		}
		return true;
	}
}
