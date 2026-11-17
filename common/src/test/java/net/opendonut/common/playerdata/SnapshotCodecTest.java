package net.opendonut.common.playerdata;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The snapshot envelope: round trips at every size that matters, and every corrupt shape is refused with the
 * right reason rather than throwing into a proxy event handler.
 */
class SnapshotCodecTest {

	private static PlayerDataSnapshot snapshot(byte[] payload) {
		return PlayerDataSnapshot.of(4400, "abc123def456", 1_700_000_000_000L, payload);
	}

	@Nested
	@DisplayName("round trips")
	class RoundTrips {

		@Test
		@DisplayName("a one-byte payload survives")
		void oneByte() {
			PlayerDataSnapshot sent = snapshot(new byte[] {42});
			SnapshotCodec.Result result = SnapshotCodec.decode(SnapshotCodec.encode(sent));
			assertTrue(result.isOk(), () -> "expected ok but got " + result.describe());
			assertEquals(sent, result.requireSnapshot());
		}

		@Test
		@DisplayName("a payload near the frame limit survives")
		void largePayload() {
			byte[] payload = new byte[48 * 1024];
			new Random(42).nextBytes(payload);
			PlayerDataSnapshot sent = snapshot(payload);
			byte[] envelope = SnapshotCodec.encode(sent);
			assertTrue(envelope.length < 64 * 1024, "a 48 KiB payload should compress or at least fit one frame");
			SnapshotCodec.Result result = SnapshotCodec.decode(envelope);
			assertTrue(result.isOk());
			assertArrayEquals(payload, result.requireSnapshot().nbt());
		}

		@Test
		@DisplayName("an incompressible 1 MiB payload round trips (over the frame limit, for the codec alone)")
		void oneMebibyte() {
			byte[] payload = new byte[1024 * 1024];
			new Random(7).nextBytes(payload);
			PlayerDataSnapshot sent = snapshot(payload);
			SnapshotCodec.Result result = SnapshotCodec.decode(SnapshotCodec.encode(sent));
			assertTrue(result.isOk());
			assertArrayEquals(payload, result.requireSnapshot().nbt());
		}

		@Test
		@DisplayName("highly compressible data shrinks a lot")
		void compresses() {
			byte[] payload = new byte[256 * 1024];
			Arrays.fill(payload, (byte) 'x');
			byte[] envelope = SnapshotCodec.encode(snapshot(payload));
			assertTrue(envelope.length < payload.length / 10,
					"expected real compression, got " + envelope.length + " bytes for " + payload.length);
		}

		@Test
		@DisplayName("the envelope carries the metadata the store needs")
		void metadata() {
			PlayerDataSnapshot sent = snapshot(new byte[] {1, 2, 3});
			PlayerDataSnapshot received = SnapshotCodec.decode(SnapshotCodec.encode(sent)).requireSnapshot();
			assertEquals(4400, received.dataVersion());
			assertEquals("abc123def456", received.modFingerprint());
			assertEquals(1_700_000_000_000L, received.takenAtMillis());
			assertEquals(PlayerDataSnapshot.CURRENT_FORMAT_VERSION, received.formatVersion());
		}
	}

	@Nested
	@DisplayName("corrupt input")
	class Corrupt {

		@Test
		@DisplayName("null and empty are TRUNCATED")
		void empty() {
			assertEquals(SnapshotCodec.DecodeError.TRUNCATED, SnapshotCodec.decode(null).error());
			assertEquals(SnapshotCodec.DecodeError.TRUNCATED, SnapshotCodec.decode(new byte[0]).error());
			assertEquals(SnapshotCodec.DecodeError.TRUNCATED,
					SnapshotCodec.decode(new byte[SnapshotCodec.HEADER_LENGTH - 1]).error());
		}

		@Test
		@DisplayName("wrong magic is BAD_MAGIC")
		void badMagic() {
			byte[] envelope = SnapshotCodec.encode(snapshot(new byte[] {1}));
			envelope[0] = 'X';
			SnapshotCodec.Result result = SnapshotCodec.decode(envelope);
			assertEquals(SnapshotCodec.DecodeError.BAD_MAGIC, result.error());
			assertTrue(SnapshotCodec.isSuspicious(result.error()));
		}

		@Test
		@DisplayName("an unknown envelope format is UNSUPPORTED_FORMAT")
		void unsupportedFormat() {
			byte[] envelope = SnapshotCodec.encode(snapshot(new byte[] {1}));
			envelope[4] = 99;
			envelope[5] = 0;
			SnapshotCodec.Result result = SnapshotCodec.decode(envelope);
			assertEquals(SnapshotCodec.DecodeError.UNSUPPORTED_FORMAT, result.error());
			assertTrue(SnapshotCodec.isSuspicious(result.error()));
		}

		@Test
		@DisplayName("truncating the payload is INVALID_LENGTH")
		void truncatedPayload() {
			byte[] envelope = SnapshotCodec.encode(snapshot(new byte[100]));
			byte[] cut = Arrays.copyOf(envelope, envelope.length - 10);
			SnapshotCodec.Result result = SnapshotCodec.decode(cut);
			assertFalse(result.isOk());
			assertNotNull(result.error());
		}

		@Test
		@DisplayName("corrupting the compressed payload is CORRUPT_PAYLOAD or INVALID_LENGTH, never a crash")
		void corruptPayload() {
			byte[] envelope = SnapshotCodec.encode(snapshot(new byte[2000]));
			for (int i = envelope.length - 5; i < envelope.length; i++) {
				envelope[i] ^= 0x5A;
			}
			SnapshotCodec.Result result = SnapshotCodec.decode(envelope);
			assertFalse(result.isOk());
			assertTrue(result.error() == SnapshotCodec.DecodeError.CORRUPT_PAYLOAD
					|| result.error() == SnapshotCodec.DecodeError.INVALID_LENGTH,
					"unexpected error " + result.error());
		}

		@Test
		@DisplayName("a lying uncompressed length is refused")
		void lyingLength() {
			byte[] envelope = SnapshotCodec.encode(snapshot(new byte[50]));
			// Layout: magic(4) + format(2) + dataVersion(4) = the uncompressed length sits at offset 10,
			// four bytes big endian.
			envelope[10] = 0x7F;
			envelope[11] = (byte) 0xFF;
			envelope[12] = (byte) 0xFF;
			envelope[13] = (byte) 0xFF;
			SnapshotCodec.Result result = SnapshotCodec.decode(envelope);
			assertFalse(result.isOk());
			assertEquals(SnapshotCodec.DecodeError.INVALID_LENGTH, result.error());
		}

		@Test
		@DisplayName("decodeOrEmpty mirrors decode")
		void optionalVariant() {
			assertTrue(SnapshotCodec.decodeOrEmpty(null).isEmpty());
			PlayerDataSnapshot sent = snapshot(new byte[] {9});
			assertEquals(sent, SnapshotCodec.decodeOrEmpty(SnapshotCodec.encode(sent)).orElseThrow());
		}

		@Test
		@DisplayName("a failed decode has a null snapshot")
		void failedHasNoSnapshot() {
			SnapshotCodec.Result result = SnapshotCodec.decode(new byte[] {1, 2, 3});
			assertNull(result.snapshot());
			assertFalse(result.isOk());
		}
	}
}
