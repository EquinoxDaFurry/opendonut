package net.opendonut.common.playerdata;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The snapshot record: defensive copying, the digest, and the version guard.
 */
class PlayerDataSnapshotTest {

	@Test
	@DisplayName("the payload is copied on the way in and out")
	void defensiveCopies() {
		byte[] payload = {1, 2, 3};
		PlayerDataSnapshot snapshot = PlayerDataSnapshot.of(4400, "fp", 1000L, payload);
		payload[0] = 99;
		assertArrayEquals(new byte[] {1, 2, 3}, snapshot.nbt());
		byte[] leaked = snapshot.nbt();
		leaked[0] = 99;
		assertArrayEquals(new byte[] {1, 2, 3}, snapshot.nbt());
	}

	@Test
	@DisplayName("invalid components are refused")
	void validation() {
		assertThrows(IllegalArgumentException.class,
				() -> new PlayerDataSnapshot(0, 1, "fp", 0L, new byte[1]));
		assertThrows(IllegalArgumentException.class,
				() -> new PlayerDataSnapshot(1, -1, "fp", 0L, new byte[1]));
		assertThrows(NullPointerException.class, () -> PlayerDataSnapshot.of(1, null, 0L, new byte[1]));
		assertThrows(NullPointerException.class, () -> PlayerDataSnapshot.of(1, "fp", 0L, null));
	}

	@Test
	@DisplayName("sha256 is stable and matches only identical payloads")
	void digest() {
		PlayerDataSnapshot a = PlayerDataSnapshot.of(4400, "fp", 1L, new byte[] {1, 2, 3});
		PlayerDataSnapshot b = PlayerDataSnapshot.of(4400, "fp", 1L, new byte[] {1, 2, 3});
		PlayerDataSnapshot c = PlayerDataSnapshot.of(4400, "fp", 1L, new byte[] {1, 2, 4});
		assertArrayEquals(a.sha256(), b.sha256());
		assertTrue(a.matches(b.nbt()));
		assertFalse(a.matches(c.nbt()));
		assertFalse(a.matches(null));
		assertNotEquals(java.util.Arrays.toString(a.sha256()), java.util.Arrays.toString(c.sha256()));
	}

	@Test
	@DisplayName("a snapshot from a newer Minecraft is detected")
	void newerVersion() {
		PlayerDataSnapshot snapshot = PlayerDataSnapshot.of(9999, "fp", 1L, new byte[] {1});
		assertTrue(snapshot.isFromNewerVersionThan(4400));
		assertFalse(snapshot.isFromNewerVersionThan(9999));
		assertFalse(snapshot.isFromNewerVersionThan(10000));
	}

	@Test
	@DisplayName("equality covers the payload")
	void equality() {
		PlayerDataSnapshot a = PlayerDataSnapshot.of(4400, "fp", 1L, new byte[] {1});
		PlayerDataSnapshot b = PlayerDataSnapshot.of(4400, "fp", 1L, new byte[] {1});
		PlayerDataSnapshot c = PlayerDataSnapshot.of(4400, "fp", 2L, new byte[] {1});
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a, c);
	}

	@Test
	@DisplayName("describe names the version and size")
	void describe() {
		PlayerDataSnapshot snapshot = PlayerDataSnapshot.of(4400, "fp", 1L, new byte[10]);
		String text = snapshot.describe();
		assertTrue(text.contains("4400"), text);
		assertTrue(text.contains("10 bytes"), text);
		assertEquals(10, snapshot.uncompressedBytes());
	}
}
