package net.opendonut.common.playerdata;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/**
 * One player's synchronised data, as it travels between a backend and the store.
 *
 * <h2>What it carries beyond the data</h2>
 * The player's bytes are not enough to apply them safely, so every snapshot also carries:
 *
 * <ul>
 *   <li><strong>{@code dataVersion}</strong> — Minecraft's own data version of the server that wrote it. A
 *       destination refuses a snapshot from a newer version rather than guessing at a format it does not know;
 *       that is the difference between a refused transfer and a shredded inventory.</li>
 *   <li><strong>{@code modFingerprint}</strong> — a hash of the writing server's mod set. Two backends of one
 *       network are supposed to be identical, and when they are not, a component the destination has never
 *       heard of is the most likely reason a snapshot will not apply cleanly. Reported, not enforced.</li>
 *   <li><strong>{@code takenAtMillis}</strong> — when the snapshot was captured, so a stale one can be spotted
 *       in a log even when nothing is technically wrong.</li>
 * </ul>
 *
 * <p>The NBT bytes are kept exactly as the Minecraft side produced them: this class never interprets them, which
 * is what keeps the format decisions on the side that understands the format.
 */
public record PlayerDataSnapshot(
		int formatVersion,
		int dataVersion,
		String modFingerprint,
		long takenAtMillis,
		byte[] nbt) {

	/** The envelope revision this build writes and reads. */
	public static final int CURRENT_FORMAT_VERSION = 1;

	/**
	 * Validates the record and copies the payload.
	 *
	 * @throws IllegalArgumentException if a component is missing or out of range
	 */
	public PlayerDataSnapshot {
		Objects.requireNonNull(modFingerprint, "modFingerprint");
		Objects.requireNonNull(nbt, "nbt");
		if (formatVersion < 1) {
			throw new IllegalArgumentException("snapshot format version must be at least 1, got " + formatVersion);
		}
		if (dataVersion < 0) {
			throw new IllegalArgumentException("snapshot data version must not be negative, got " + dataVersion);
		}
		nbt = nbt.clone();
	}

	/**
	 * Creates a snapshot in the current format.
	 *
	 * @param dataVersion the writing server's Minecraft data version
	 * @param modFingerprint the writing server's mod fingerprint
	 * @param takenAtMillis when the snapshot was taken
	 * @param nbt the serialised player data
	 * @return the snapshot
	 */
	public static PlayerDataSnapshot of(int dataVersion, String modFingerprint, long takenAtMillis, byte[] nbt) {
		return new PlayerDataSnapshot(CURRENT_FORMAT_VERSION, dataVersion, modFingerprint, takenAtMillis, nbt);
	}

	@Override
	public byte[] nbt() {
		return nbt.clone();
	}

	/**
	 * The payload size in bytes, before compression.
	 *
	 * @return the uncompressed size
	 */
	public int uncompressedBytes() {
		return nbt.length;
	}

	/**
	 * SHA-256 of the payload, used to verify a snapshot after it has travelled.
	 *
	 * @return the digest
	 */
	public byte[] sha256() {
		try {
			return MessageDigest.getInstance("SHA-256").digest(nbt);
		} catch (NoSuchAlgorithmException e) {
			// Mandated by the Java platform.
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Verifies a payload against this snapshot's digest.
	 *
	 * @param candidate the payload to check
	 * @return {@code true} when the bytes match
	 */
	public boolean matches(byte[] candidate) {
		return candidate != null && MessageDigest.isEqual(sha256(), sha256Of(candidate));
	}

	/**
	 * SHA-256 of arbitrary bytes.
	 *
	 * @param bytes the bytes
	 * @return the digest
	 */
	static byte[] sha256Of(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Tests whether this snapshot came from a Minecraft version newer than the given one.
	 *
	 * @param currentDataVersion the reading server's data version
	 * @return {@code true} when the snapshot is from a newer version and must not be applied
	 */
	public boolean isFromNewerVersionThan(int currentDataVersion) {
		return dataVersion > currentDataVersion;
	}

	/**
	 * Renders the snapshot for a log line.
	 *
	 * @return a one-line description
	 */
	public String describe() {
		return "snapshot v" + formatVersion + " dataVersion " + dataVersion + ", " + nbt.length + " bytes, "
				+ "mods " + modFingerprint + ", taken " + takenAtMillis;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		return other instanceof PlayerDataSnapshot snapshot
				&& formatVersion == snapshot.formatVersion
				&& dataVersion == snapshot.dataVersion
				&& takenAtMillis == snapshot.takenAtMillis
				&& modFingerprint.equals(snapshot.modFingerprint)
				&& Arrays.equals(nbt, snapshot.nbt);
	}

	@Override
	public int hashCode() {
		return Objects.hash(formatVersion, dataVersion, modFingerprint, takenAtMillis, Arrays.hashCode(nbt));
	}

	@Override
	public String toString() {
		return describe();
	}
}
