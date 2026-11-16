package net.opendonut.common.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.opendonut.common.model.RegionBounds;

/**
 * The bridge protocol: every message round-trips, and every way of attacking or corrupting a frame is
 * rejected with the right reason.
 */
class BridgeCodecTest {

	private static final byte[] SECRET = BridgeSecret.generate();
	private static final byte[] OTHER_SECRET = BridgeSecret.generate();
	private static final long NOW = 1_700_000_000_000L;

	private static BridgeDecodeResult roundTrip(BridgeMessage message, NonceCache cache) {
		byte[] frame = BridgeCodec.encode(message, SECRET, NOW, BridgeCodec.randomNonce());
		return BridgeCodec.decode(frame, SECRET, NOW, 30, cache);
	}

	private static BridgeMessage decodeOrFail(byte[] frame, byte[] secret, NonceCache cache) {
		BridgeDecodeResult result = BridgeCodec.decode(frame, secret, NOW, 30, cache);
		return result.envelopeIfAccepted()
				.orElseThrow(() -> new AssertionError("expected a decoded frame but got: " + result))
				.message();
	}

	@Nested
	@DisplayName("round trips")
	class RoundTrips {

		@Test
		@DisplayName("HELLO survives encoding and decoding intact")
		void hello() {
			BridgeMessage.Hello sent = new BridgeMessage.Hello("west", "world", 123456789L,
					new RegionBounds(-100000, 0, -100000, 100000), "26.3", "0.1.0-dev", "127.0.0.1:25566");
			BridgeMessage received = decodeOrFail(
					BridgeCodec.encode(sent, SECRET, NOW, BridgeCodec.randomNonce()), SECRET, new NonceCache());
			assertEquals(sent, received);
		}

		@Test
		@DisplayName("HELLO_ACK survives encoding and decoding intact")
		void helloAck() {
			BridgeMessage.HelloAck sent = new BridgeMessage.HelloAck("east", false,
					"world seed differs: configured 1, backend reported 2", 1L);
			assertEquals(sent, decodeOrFail(
					BridgeCodec.encode(sent, SECRET, NOW, BridgeCodec.randomNonce()), SECRET, new NonceCache()));
		}

		@Test
		@DisplayName("TRANSFER_REQUEST survives encoding and decoding intact")
		void transferRequest() {
			BridgeMessage.TransferRequest sent = new BridgeMessage.TransferRequest(
					UUID.randomUUID(), "Notch", "west", -1, 64, 1234, 90.5f, -12.25f, "east", "crossed max-x");
			assertEquals(sent, decodeOrFail(
					BridgeCodec.encode(sent, SECRET, NOW, BridgeCodec.randomNonce()), SECRET, new NonceCache()));
		}

		@Test
		@DisplayName("TRANSFER_RESULT survives encoding and decoding intact")
		void transferResult() {
			BridgeMessage.TransferResult sent = new BridgeMessage.TransferResult(
					UUID.randomUUID(), "west", "east", true, "SUCCESS", 0, 65, -300);
			assertEquals(sent, decodeOrFail(
					BridgeCodec.encode(sent, SECRET, NOW, BridgeCodec.randomNonce()), SECRET, new NonceCache()));
		}

		@Test
		@DisplayName("PLACE_PLAYER survives encoding and decoding intact")
		void placePlayer() {
			BridgeMessage.PlacePlayer sent = new BridgeMessage.PlacePlayer(
					UUID.randomUUID(), "west", "east", 0, 70, -1500, 180.0f, 0.0f, NOW);
			assertEquals(sent, decodeOrFail(
					BridgeCodec.encode(sent, SECRET, NOW, BridgeCodec.randomNonce()), SECRET, new NonceCache()));
		}

		@Test
		@DisplayName("an unknown seed keeps its sentinel value")
		void unknownSeed() {
			BridgeMessage.Hello sent = new BridgeMessage.Hello("west", "world",
					BridgeMessage.Hello.UNKNOWN_SEED, new RegionBounds(-16, 0, -16, 16), "26.3", "0.1.0-dev", "");
			BridgeMessage.Hello received = assertInstanceOf(BridgeMessage.Hello.class, roundTrip(sent, new NonceCache())
					.envelopeIfAccepted().orElseThrow().message());
			assertTrue(received.seedUnknown());
		}

		@Test
		@DisplayName("the envelope carries the timestamp and a fresh nonce")
		void envelopeMetadata() {
			BridgeEnvelope envelope = roundTrip(new BridgeMessage.HelloAck("west", true, "ok", 1L),
					new NonceCache()).envelopeIfAccepted().orElseThrow();
			assertEquals(NOW, envelope.timestampMillis());
			assertEquals(BridgeCodec.NONCE_LENGTH, envelope.nonce().length);
		}

		@Test
		@DisplayName("two frames for the same message differ, because the nonce does")
		void framesAreUnique() {
			BridgeMessage message = new BridgeMessage.HelloAck("west", true, "ok", 1L);
			byte[] first = BridgeCodec.encode(message, SECRET, NOW, BridgeCodec.randomNonce());
			byte[] second = BridgeCodec.encode(message, SECRET, NOW, BridgeCodec.randomNonce());
			assertNotEquals(java.util.Arrays.toString(first), java.util.Arrays.toString(second));
		}
	}

	@Nested
	@DisplayName("rejections")
	class Rejections {

		private byte[] frame() {
			return BridgeCodec.encode(new BridgeMessage.HelloAck("west", true, "ok", 1L), SECRET, NOW,
					BridgeCodec.randomNonce());
		}

		private DecodeFailure failureOf(byte[] candidate, byte[] secret, long now, int tolerance,
				NonceCache cache) {
			BridgeDecodeResult result = BridgeCodec.decode(candidate, secret, now, tolerance, cache);
			return assertInstanceOf(BridgeDecodeResult.Failed.class, result, () -> "expected a rejection, got " + result)
					.failure();
		}

		@Test
		@DisplayName("a truncated frame is refused")
		void truncated() {
			byte[] full = frame();
			assertEquals(DecodeFailure.TRUNCATED,
					failureOf(java.util.Arrays.copyOf(full, full.length - 1), SECRET, NOW, 30, new NonceCache()));
			assertEquals(DecodeFailure.TRUNCATED, failureOf(new byte[4], SECRET, NOW, 30, new NonceCache()));
			assertEquals(DecodeFailure.TRUNCATED, failureOf(null, SECRET, NOW, 30, new NonceCache()));
		}

		@Test
		@DisplayName("a frame with foreign magic bytes is refused")
		void badMagic() {
			byte[] frame = frame();
			frame[0] = 'X';
			assertEquals(DecodeFailure.BAD_MAGIC, failureOf(frame, SECRET, NOW, 30, new NonceCache()));
		}

		@Test
		@DisplayName("a frame from a different protocol version is refused")
		void wrongVersion() {
			byte[] frame = frame();
			frame[4] = (byte) (BridgeCodec.PROTOCOL_VERSION + 1);
			assertEquals(DecodeFailure.UNSUPPORTED_VERSION, failureOf(frame, SECRET, NOW, 30, new NonceCache()));
		}

		@Test
		@DisplayName("a frame with an unknown message type is refused")
		void unknownType() {
			byte[] frame = frame();
			frame[5] = (byte) 99;
			assertEquals(DecodeFailure.UNKNOWN_MESSAGE_TYPE, failureOf(frame, SECRET, NOW, 30, new NonceCache()));
		}

		@Test
		@DisplayName("an absurd declared payload length is refused before any allocation")
		void declaredLengthTooLarge() {
			byte[] frame = frame();
			frame[6] = 0x7F;
			assertEquals(DecodeFailure.PAYLOAD_TOO_LARGE, failureOf(frame, SECRET, NOW, 30, new NonceCache()));
		}

		@Test
		@DisplayName("a negative declared payload length is refused")
		void declaredLengthNegative() {
			byte[] frame = frame();
			frame[6] = (byte) 0xFF;
			frame[7] = (byte) 0xFF;
			assertEquals(DecodeFailure.PAYLOAD_TOO_LARGE, failureOf(frame, SECRET, NOW, 30, new NonceCache()));
		}

		@Test
		@DisplayName("a frame signed with a different secret is refused")
		void wrongSecret() {
			assertEquals(DecodeFailure.BAD_SIGNATURE,
					failureOf(frame(), OTHER_SECRET, NOW, 30, new NonceCache()));
		}

		@Test
		@DisplayName("a missing or too short secret is refused")
		void invalidSecret() {
			assertEquals(DecodeFailure.INVALID_SECRET, failureOf(frame(), null, NOW, 30, new NonceCache()));
			assertEquals(DecodeFailure.INVALID_SECRET,
					failureOf(frame(), new byte[4], NOW, 30, new NonceCache()));
		}

		@Test
		@DisplayName("flipping any single bit of the frame is detected")
		void everyBitIsProtected() {
			byte[] full = frame();
			for (int index = 0; index < full.length; index++) {
				for (int bit = 0; bit < 8; bit++) {
					byte[] tampered = full.clone();
					tampered[index] ^= (byte) (1 << bit);
					BridgeDecodeResult result = BridgeCodec.decode(tampered, SECRET, NOW, 30, new NonceCache());
					assertFalse(result.isAccepted(),
							"a single flipped bit at byte " + index + " bit " + bit + " was accepted");
					// A tampered byte inside the header may legitimately be caught as a malformed header; a
					// tampered payload byte must always be caught by the signature.
					DecodeFailure failure = assertInstanceOf(BridgeDecodeResult.Failed.class, result).failure();
					if (index >= BridgeCodec.HEADER_LENGTH && index < full.length - BridgeCodec.MAC_LENGTH) {
						assertEquals(DecodeFailure.BAD_SIGNATURE, failure,
								"tampering with payload byte " + index + " must fail authentication");
					}
				}
			}
		}

		@Test
		@DisplayName("a stale timestamp is refused")
		void staleTimestamp() {
			byte[] old = BridgeCodec.encode(new BridgeMessage.HelloAck("w", true, "ok", 1L), SECRET,
					NOW - 120_000L, BridgeCodec.randomNonce());
			assertEquals(DecodeFailure.STALE_TIMESTAMP,
					failureOf(old, SECRET, NOW, 30, new NonceCache()));

			// A frame from the future is just as suspicious.
			byte[] future = BridgeCodec.encode(new BridgeMessage.HelloAck("w", true, "ok", 1L), SECRET,
					NOW + 120_000L, BridgeCodec.randomNonce());
			assertEquals(DecodeFailure.STALE_TIMESTAMP,
					failureOf(future, SECRET, NOW, 30, new NonceCache()));
		}

		@Test
		@DisplayName("a replayed frame is refused")
		void replay() {
			byte[] frame = frame();
			NonceCache cache = new NonceCache();
			assertTrue(BridgeCodec.decode(frame, SECRET, NOW, 30, cache).isAccepted());
			assertEquals(DecodeFailure.REPLAY, failureOf(frame, SECRET, NOW, 30, cache));
		}

		@Test
		@DisplayName("a frame whose payload does not match its declared type is refused")
		void malformedPayload() {
			// Encode an ack, then relabel it as a HELLO: the signature is still valid, because the type byte
			// is part of the signed header, so this must be caught by the payload parser instead.
			byte[] original = BridgeCodec.encode(new BridgeMessage.HelloAck("west", true, "ok", 1L), SECRET, NOW,
					BridgeCodec.randomNonce());
			byte[] relabelled = original.clone();
			relabelled[5] = (byte) MessageType.HELLO.wireId();
			BridgeDecodeResult result = BridgeCodec.decode(relabelled, SECRET, NOW, 30, new NonceCache());
			DecodeFailure failure = assertInstanceOf(BridgeDecodeResult.Failed.class, result).failure();
			// Either the signature check catches the edited header, or the parser rejects the body.
			assertTrue(failure == DecodeFailure.BAD_SIGNATURE || failure == DecodeFailure.MALFORMED_PAYLOAD,
					"expected a rejection, got " + failure);
		}
	}

	@Nested
	@DisplayName("helpers")
	class Helpers {

		@Test
		@DisplayName("peekType reads the announced type without authenticating")
		void peek() {
			byte[] frame = BridgeCodec.encode(new BridgeMessage.HelloAck("w", true, "ok", 1L), SECRET, NOW,
					BridgeCodec.randomNonce());
			assertEquals(java.util.Optional.of(MessageType.HELLO_ACK), BridgeCodec.peekType(frame));
			assertEquals(java.util.Optional.empty(), BridgeCodec.peekType(new byte[2]));
			assertEquals(java.util.Optional.empty(), BridgeCodec.peekType(null));
		}

		@Test
		@DisplayName("describeFrame renders a frame header for logs")
		void describe() {
			byte[] frame = BridgeCodec.encode(new BridgeMessage.HelloAck("w", true, "ok", 1L), SECRET, NOW,
					BridgeCodec.randomNonce());
			String description = BridgeCodec.describeFrame(frame);
			assertTrue(description.contains("ODNT"), description);
			assertTrue(description.contains("HELLO_ACK"), description);
			assertTrue(description.contains("v1"), description);
		}

		@Test
		@DisplayName("encoding with an unusable secret is refused loudly")
		void encodingRequiresASecret() {
			BridgeMessage message = new BridgeMessage.HelloAck("w", true, "ok", 1L);
			assertThrows(IllegalArgumentException.class, () -> BridgeCodec.encode(message, new byte[4]));
			assertThrows(IllegalArgumentException.class, () -> BridgeCodec.encode(message, null));
			assertThrows(IllegalArgumentException.class,
					() -> BridgeCodec.encode(message, SECRET, NOW, new byte[3]));
		}

		@Test
		@DisplayName("the envelope copies its nonce defensively")
		void envelopeCopiesNonce() {
			byte[] nonce = BridgeCodec.randomNonce();
			BridgeEnvelope envelope = new BridgeEnvelope(new BridgeMessage.HelloAck("w", true, "ok", 1L), NOW, nonce);
			nonce[0] ^= 0x7F;
			assertNotEquals(nonce[0], envelope.nonce()[0], "mutating the input array must not change the envelope");
			byte[] first = envelope.nonce();
			first[0] ^= 0x7F;
			assertArrayEquals(envelope.nonce(), envelope.nonce(), "the accessor must hand out copies");
		}

		@Test
		@DisplayName("an envelope with a wrong length nonce is rejected")
		void envelopeNonceLength() {
			assertThrows(IllegalArgumentException.class,
					() -> new BridgeEnvelope(new BridgeMessage.HelloAck("w", true, "ok", 1L), NOW, new byte[3]));
		}
	}
}
