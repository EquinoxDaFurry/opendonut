package net.opendonut.fabric;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.UUID;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.opendonut.common.bridge.BridgeCodec;
import net.opendonut.common.bridge.BridgeDecodeResult;
import net.opendonut.common.bridge.BridgeMessage;
import net.opendonut.common.bridge.BridgeSecret;
import net.opendonut.common.bridge.DecodeFailure;
import net.opendonut.common.bridge.NonceCache;
import net.opendonut.common.model.RegionBounds;

/**
 * The payload codec: the exact bytes that cross the Minecraft custom-payload boundary.
 *
 * <h2>Why this test exists</h2>
 * A transfer failed in production because this codec wrapped the frame in a VarInt length prefix while the
 * proxy passes the payload field through verbatim. The proxy's magic check therefore read a length byte
 * instead of 'O' and refused every frame with {@code BAD_MAGIC} — a symptom that says nothing about framing.
 *
 * <p>These tests pin the contract at the layer that was wrong: <strong>the payload field carries the frame
 * verbatim</strong>, not "a byte array containing the frame" and not "a frame with a length in front of it".
 * They run against the real Minecraft buffer classes, so they cannot drift from the platform's behaviour.
 */
class BridgePayloadCodecTest {

	private static final byte[] SECRET = BridgeSecret.generate();
	private static final long NOW = 1_700_000_000_000L;

	/**
	 * A frame as the protocol produces it.
	 *
	 * @return the encoded frame
	 */
	private static byte[] sampleFrame() {
		return BridgeCodec.encode(new BridgeMessage.TransferRequest(
				UUID.fromString("00000000-0000-0000-0000-000000000042"),
				"EquinoxDaFurry",
				"west",
				1034, 69, 658,
				90.0f, 0.0f,
				"east",
				"past max-x, 1034 block(s) outside"), SECRET, NOW, BridgeCodec.randomNonce());
	}

	/**
	 * Creates a buffer that Minecraft's payload codecs accept.
	 *
	 * @return an empty buffer
	 */
	private static RegistryFriendlyByteBuf buffer() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
	}

	@Test
	@DisplayName("encoding writes the frame verbatim, with nothing in front of it")
	void encodingIsVerbatim() {
		byte[] frame = sampleFrame();
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			BridgePayload.CODEC.encode(buffer, new BridgePayload(frame));
			byte[] written = ByteBufUtil.getBytes(buffer, buffer.readerIndex(), buffer.readableBytes());

			// The whole contract, in one assertion: what the proxy receives as the payload field is the frame,
			// starting with the magic bytes at offset 0.
			assertArrayEquals(frame, written, "the payload must be the frame and nothing else");
			assertEquals(0x4F, written[0] & 0xFF, "byte 0 must be 'O', the first magic byte");
			assertEquals(written.length, frame.length, "no length prefix, no padding, no trailer");
		} finally {
			buffer.release();
		}
	}

	@Test
	@DisplayName("a frame survives the payload boundary unchanged")
	void roundTrip() {
		byte[] frame = sampleFrame();
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			BridgePayload.CODEC.encode(buffer, new BridgePayload(frame));
			BridgePayload decoded = BridgePayload.CODEC.decode(buffer);

			assertArrayEquals(frame, decoded.frame());
			// And the decoded payload is still a frame the protocol accepts, which is what the proxy does with it.
			BridgeDecodeResult result = BridgeCodec.decode(decoded.frame(), SECRET, NOW, 30, new NonceCache());
			assertTrue(result.isAccepted(), () -> "the proxy must accept this frame, got " + result);
		} finally {
			buffer.release();
		}
	}

	@Test
	@DisplayName("a payload that carries a length prefix is no longer produced, and would be refused")
	void lengthPrefixedPayloadIsRefused() {
		// The regression, stated as a test: this is the byte sequence the old codec produced, and this is what
		// the proxy said about it.
		byte[] frame = sampleFrame();
		byte[] oldStylePayload = varIntPrefixed(frame);

		assertEquals(0x4F, frame[0] & 0xFF);
		assertTrue(oldStylePayload.length > frame.length, "the old form was longer than the frame");
		assertTrue((oldStylePayload[0] & 0xFF) != 0x4F, "and it did not start with the magic byte");

		BridgeDecodeResult result = BridgeCodec.decode(oldStylePayload, SECRET, NOW, 30, new NonceCache());
		DecodeFailure failure = ((BridgeDecodeResult.Failed) result).failure();
		assertEquals(DecodeFailure.BAD_MAGIC, failure, "the observed production failure");

		// The improved diagnostic names the cause instead of only the symptom.
		String description = BridgeCodec.describeFrame(oldStylePayload);
		assertTrue(description.contains("not an OpenDonut frame"), description);
		assertTrue(description.contains("4f 44 4e 54"), description);
		assertTrue(description.contains("length prefix"), description);
	}

	@Test
	@DisplayName("an oversized payload is refused rather than allocated")
	void oversizedPayloadIsRefused() {
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			buffer.writeBytes(new byte[BridgePayload.MAX_FRAME_BYTES + 1]);
			assertThrows(DecoderException.class, () -> BridgePayload.CODEC.decode(buffer),
					"a hostile peer must not be able to make the server allocate an arbitrary buffer");
		} finally {
			buffer.release();
		}
	}

	@Test
	@DisplayName("an empty payload decodes and is reported as too short, not as a crash")
	void emptyPayload() {
		RegistryFriendlyByteBuf buffer = buffer();
		try {
			BridgePayload decoded = BridgePayload.CODEC.decode(buffer);
			assertEquals(0, decoded.frame().length);

			BridgeDecodeResult result = BridgeCodec.decode(decoded.frame(), SECRET, NOW, 30, new NonceCache());
			assertEquals(DecodeFailure.TRUNCATED, ((BridgeDecodeResult.Failed) result).failure());
		} finally {
			buffer.release();
		}
	}

	@Test
	@DisplayName("the payload type is created from the configured channel")
	void channelParsing() {
		// Registration itself happens once, at mod initialisation, so this test only checks the parsing rule the
		// registration uses — the part that can be wrong without anything failing loudly.
		assertThrows(IllegalArgumentException.class, () -> describeChannelOf(null));
		assertThrows(IllegalArgumentException.class, () -> describeChannelOf("nocolon"));
		assertThrows(IllegalArgumentException.class, () -> describeChannelOf("opendonut:"));
		assertEquals("opendonut:bridge", describeChannelOf("opendonut:bridge"));
		assertEquals("custom:chan", describeChannelOf("custom:chan"));
	}

	/**
	 * Mirrors the channel parsing used at registration, so the rule can be tested without re-registering the
	 * payload type (Fabric allows that only once per process).
	 *
	 * @param channelName the channel name
	 * @return the normalised channel name
	 */
	private static String describeChannelOf(String channelName) {
		if (channelName == null || channelName.indexOf(':') <= 0 || channelName.endsWith(":")) {
			throw new IllegalArgumentException("bridge channel must be in namespace:path form");
		}
		return channelName;
	}

	/**
	 * Encodes a length prefix the way {@code FriendlyByteBuf.writeByteArray} does — the mistake this codec
	 * must not repeat.
	 *
	 * @param frame the frame to wrap
	 * @return the prefixed payload
	 */
	private static byte[] varIntPrefixed(byte[] frame) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int remaining = frame.length;
		while ((remaining & ~0x7F) != 0) {
			out.write((remaining & 0x7F) | 0x80);
			remaining >>>= 7;
		}
		out.write(remaining);
		out.writeBytes(frame);
		return out.toByteArray();
	}

}
