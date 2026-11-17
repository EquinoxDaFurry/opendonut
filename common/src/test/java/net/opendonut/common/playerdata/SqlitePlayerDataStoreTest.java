package net.opendonut.common.playerdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SQLite store against the shared contract, plus the property that makes it the production store: data
 * survives closing and reopening the file.
 */
class SqlitePlayerDataStoreTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("satisfies the store contract")
	void contract() throws Exception {
		Path file = tempDir.resolve("playerdata.db");
		try (SqlitePlayerDataStore store = SqlitePlayerDataStore.open(file)) {
			PlayerDataStoreContract.run(store);
		}
	}

	@Test
	@DisplayName("snapshots and leases survive a close and reopen")
	void persistence() throws Exception {
		Path file = tempDir.resolve("persist.db");
		UUID player = UUID.randomUUID();
		long now = 1_700_000_000_000L;
		PlayerDataSnapshot snapshot = PlayerDataSnapshot.of(4400, "fp", now, new byte[] {10, 20, 30});

		try (SqlitePlayerDataStore store = SqlitePlayerDataStore.open(file)) {
			assertTrue(store.save(player, 0L, snapshot, "west", now, 20).accepted());
			assertTrue(store.acquireLease(player, "west", now, 60_000L).acquired());
		}

		try (SqlitePlayerDataStore reopened = SqlitePlayerDataStore.open(file)) {
			assertEquals(snapshot, reopened.load(player).orElseThrow().snapshot());
			assertEquals(1L, reopened.load(player).orElseThrow().revision());
			assertEquals("west", reopened.lease(player).orElseThrow().ownerServer());
			// The lease survives the restart too, so a proxy restart does not hand out the player twice.
			assertEquals(1, reopened.history(player, 10).size());
			assertTrue(reopened.describe().contains("sqlite"), reopened.describe());
		}
	}

	@Test
	@DisplayName("opening creates the parent directories")
	void createsDirectories() throws Exception {
		Path file = tempDir.resolve("nested/deeper/playerdata.db");
		try (SqlitePlayerDataStore store = SqlitePlayerDataStore.open(file)) {
			assertTrue(Files.exists(file));
			assertTrue(store.describe().contains("nested"));
		}
	}
}
