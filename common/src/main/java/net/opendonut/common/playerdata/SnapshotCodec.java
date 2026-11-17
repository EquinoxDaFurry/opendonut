package net.opendonut.common.playerdata;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Serialises a {@link PlayerDataSnapshot} for storage and for the wire, and compresses it.
 *
 * <h2>The envelope</h2>
 * <pre>
 *   offset  size  field
 *   ------  ----  ---------------------------------------------------------
 *        0     4  magic 'O' 'D' 'P' 'D'
 *        4     2  envelope format version
 *        6     4  Minecraft data version
 *       10     4  payload length, uncompressed
 *       14     8  captured at, epoch millis
 *       22     4  mod fingerprint length
 *       26   N*1  mod fingerprint, UTF-8
 *  26+N      4  payload length, compressed
 *  30+N    M*1  payload, gzip
 * </pre>
 *
 * <h2>Why an envelope rather than "just the bytes"</h2>
 * Because the payload is opaque: whatever writes it (today, the Fabric side; tomorrow, something else) is the
 * only component that understands it, so the reader needs to know which format revision it is looking at, which
 * Minecraft version produced it, and how big it claims to be before it allocates anything. A bare blob would
 * make every one of those a guess.
 *
 * <h2>Compression</h2>
 * gzip, because it is in the JDK and a compression dependency in the proxy would have to be shaded for a few
 * kilobytes of saving. zstd would be a better ratio and is noted in
 * {@code docs/plan-player-data-sync.md} as a later improvement, not a requirement.
 *
 * <p>Decoding is defensive throughout: a truncated, oversized or unknown-version envelope is reported as an
 * {@link DecodeError} rather than throwing, because this runs inside a proxy event handler where an exception
 * would be swallowed into a log line and leave the player in limbo.
 */
public final class SnapshotCodec {

	/** Envelope magic, printable so a hex dump is recognisable. */
	public static final byte[] MAGIC = {'O', 'D', 'P', 'D'};

	/** Fixed portion of the envelope: magic, version, data version, lengths and timestamp. */
	public static final int HEADER_LENGTH = MAGIC.length + 2 + 4 + 4 + 8 + 4;

	/**
	 * Largest uncompressed payload accepted when decoding.
	 *
	 * <p>A guard against a hostile or corrupt length field, not a policy limit: the policy limit is
	 * {@code player-data.max-payload-bytes}, applied to the *compressed* size by the caller.
	 */
	public static final int MAX_UNCOMPRESSED_BYTES = 16 * 1024 * 1024;

	/** Largest mod fingerprint accepted, in bytes. */
	private static final int MAX_FINGERPRINT_BYTES = 256;

	private SnapshotCodec() {
	}

	/**
	 * Why a snapshot could not be decoded.
	 */
	public enum DecodeError {

		/** The bytes are too short to be an envelope. */
		TRUNCATED,

		/** The bytes do not start with the envelope magic. */
		BAD_MAGIC,

		/** The envelope revision is not one this build understands. */
		UNSUPPORTED_FORMAT,

		/** A declared length is negative, absurd, or larger than the data present. */
		INVALID_LENGTH,

		/** The compressed payload could not be decompressed. */
		CORRUPT_PAYLOAD
	}

	/**
	 * Encodes a snapshot, compressed.
	 *
	 * @param snapshot the snapshot
	 * @return the envelope bytes
	 */
	public static byte[] encode(PlayerDataSnapshot snapshot) {
		byte[] payload = snapshot.nbt();
		byte[] fingerprint = snapshot.modFingerprint().getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] compressed = compress(payload);

		try {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream(compressed.length + HEADER_LENGTH + 64);
			DataOutputStream out = new DataOutputStream(buffer);
			out.write(MAGIC);
			out.writeShort(snapshot.formatVersion());
			out.writeInt(snapshot.dataVersion());
			out.writeInt(payload.length);
			out.writeLong(snapshot.takenAtMillis());
			out.writeInt(fingerprint.length);
			out.write(fingerprint);
			out.writeInt(compressed.length);
			out.write(compressed);
			out.flush();
			return buffer.toByteArray();
		} catch (IOException e) {
			// ByteArrayOutputStream does not throw; this cannot happen and is not worth propagating.
			throw new IllegalStateException("failed to encode a snapshot in memory", e);
		}
	}

	/**
	 * Decodes an envelope.
	 *
	 * @param bytes the envelope bytes
	 * @return the snapshot, or the reason it could not be read
	 */
	public static Result decode(byte[] bytes) {
		if (bytes == null || bytes.length < HEADER_LENGTH) {
			return new Result(null, DecodeError.TRUNCATED, "fewer than " + HEADER_LENGTH + " bytes");
		}
		for (int i = 0; i < MAGIC.length; i++) {
			if (bytes[i] != MAGIC[i]) {
				return new Result(null, DecodeError.BAD_MAGIC, "does not start with the snapshot magic");
			}
		}

		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
			in.skipBytes(MAGIC.length);
			int formatVersion = in.readUnsignedShort();
			if (formatVersion != PlayerDataSnapshot.CURRENT_FORMAT_VERSION) {
				return new Result(null, DecodeError.UNSUPPORTED_FORMAT,
						"envelope format " + formatVersion + " is not supported by this build");
			}
			int dataVersion = in.readInt();
			int uncompressedLength = in.readInt();
			long takenAt = in.readLong();
			int fingerprintLength = in.readInt();
			if (uncompressedLength < 0 || uncompressedLength > MAX_UNCOMPRESSED_BYTES) {
				return new Result(null, DecodeError.INVALID_LENGTH,
						"declared payload of " + uncompressedLength + " bytes");
			}
			if (fingerprintLength < 0 || fingerprintLength > MAX_FINGERPRINT_BYTES || fingerprintLength > in.available()) {
				return new Result(null, DecodeError.INVALID_LENGTH,
						"declared fingerprint of " + fingerprintLength + " bytes");
			}
			byte[] fingerprint = new byte[fingerprintLength];
			in.readFully(fingerprint);
			int compressedLength = in.readInt();
			if (compressedLength < 0 || compressedLength > in.available()) {
				return new Result(null, DecodeError.INVALID_LENGTH,
						"declared payload of " + compressedLength + " bytes but only " + in.available()
								+ " present");
			}
			byte[] compressed = new byte[compressedLength];
			in.readFully(compressed);

			byte[] payload = decompress(compressed, uncompressedLength);
			if (payload == null) {
				return new Result(null, DecodeError.CORRUPT_PAYLOAD, "the payload could not be decompressed");
			}
			if (payload.length != uncompressedLength) {
				return new Result(null, DecodeError.INVALID_LENGTH,
						"decompressed to " + payload.length + " bytes but " + uncompressedLength + " were declared");
			}
			return new Result(new PlayerDataSnapshot(formatVersion, dataVersion,
					new String(fingerprint, java.nio.charset.StandardCharsets.UTF_8), takenAt, payload), null, "");
		} catch (IOException | RuntimeException e) {
			return new Result(null, DecodeError.TRUNCATED, e.toString());
		}
	}

	/**
	 * The outcome of a decode: either a snapshot or a reason.
	 *
	 * @param snapshot the decoded snapshot, or {@code null}
	 * @param error the reason, or {@code null} on success
	 * @param detail a human readable explanation
	 */
	public record Result(PlayerDataSnapshot snapshot, DecodeError error, String detail) {

		/**
		 * Tests whether decoding succeeded.
		 *
		 * @return {@code true} when a snapshot is present
		 */
		public boolean isOk() {
			return snapshot != null;
		}

		/**
		 * The snapshot, when decoding succeeded.
		 *
		 * @return the snapshot
		 * @throws IllegalStateException when decoding failed
		 */
		public PlayerDataSnapshot requireSnapshot() {
			if (snapshot == null) {
				throw new IllegalStateException("no snapshot: " + error + " (" + detail + ")");
			}
			return snapshot;
		}

		/**
		 * Renders the outcome for a log line.
		 *
		 * @return a description
		 */
		public String describe() {
			return isOk() ? snapshot.describe() : error + ": " + detail;
		}
	}

	/**
	 * Compresses bytes with gzip.
	 *
	 * @param bytes the bytes
	 * @return the compressed bytes
	 */
	public static byte[] compress(byte[] bytes) {
		try {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(64, bytes.length / 4));
			try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
				gzip.write(bytes);
			}
			return buffer.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("failed to compress in memory", e);
		}
	}

	/**
	 * Decompresses bytes with gzip, bounded by the declared length.
	 *
	 * @param bytes the compressed bytes
	 * @param expectedLength the uncompressed length the envelope declared
	 * @return the decompressed bytes, or {@code null} when they are corrupt or longer than declared
	 */
	private static byte[] decompress(byte[] bytes, int expectedLength) {
		try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(64, expectedLength));
			byte[] chunk = new byte[8192];
			int total = 0;
			int read;
			while ((read = gzip.read(chunk)) > 0) {
				total += read;
				if (total > expectedLength) {
					// A zip bomb, or a lying length field: stop rather than allocate more.
					return null;
				}
				buffer.write(chunk, 0, read);
			}
			return buffer.toByteArray();
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Looks up a decode error that is worth reporting loudly.
	 *
	 * <p>Truncation can happen for innocent reasons — a snapshot that was never written — while corruption and a
	 * version mismatch mean something is genuinely wrong, and the proxy logs them at different levels.
	 *
	 * @param error the error
	 * @return {@code true} for errors that suggest corruption or a version mismatch
	 */
	public static boolean isSuspicious(DecodeError error) {
		return error == DecodeError.CORRUPT_PAYLOAD || error == DecodeError.BAD_MAGIC
				|| error == DecodeError.UNSUPPORTED_FORMAT;
	}

	/**
	 * A convenience for callers that want an optional rather than a result.
	 *
	 * @param bytes the envelope bytes
	 * @return the snapshot, or empty
	 */
	public static Optional<PlayerDataSnapshot> decodeOrEmpty(byte[] bytes) {
		Result result = decode(bytes);
		return result.snapshot() == null ? Optional.empty() : Optional.of(result.snapshot());
	}
}
