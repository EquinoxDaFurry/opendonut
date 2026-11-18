package net.opendonut.common.playerdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The contract every {@link PlayerDataStore} implementation must satisfy, run against each of them by its own
 * test class.
 *
 * <p>The scenarios are the invariants the transfer ordering depends on: compare-and-set saves, a lease that
 * only one server holds and that can be taken over only after it expires, and a bounded history. If an
 * implementation fails one of these, the failure mode on a live server is duplicated or lost items, which is
 * why these are tests and not comments.
 */
final class PlayerDataStoreContract {

	private PlayerDataStoreContract() {
	}

	/**
	 * Runs every contract scenario against a store.
	 *
	 * @param store the store, freshly opened
	 */
	static void run(PlayerDataStore store) {
		UUID player = UUID.randomUUID();
		long now = 1_700_000_000_000L;

		// --- load on a fresh player is empty ---
		assertTrue(store.load(player).isEmpty(), "a fresh player must have no snapshot");

		// --- save with base 0 creates revision 1; the same base again is refused (compare-and-set) ---
		PlayerDataSnapshot first = PlayerDataSnapshot.of(4400, "fp", now, new byte[] {1, 2, 3});
		PlayerDataStore.SaveOutcome saved = store.save(player, 0L, first, "west", now, 20);
		assertTrue(saved.accepted(), saved::detail);
		assertEquals(1L, saved.newRevision());

		Optional<PlayerDataStore.StoredSnapshot> loaded = store.load(player);
		assertTrue(loaded.isPresent());
		assertEquals(1L, loaded.get().revision());
		assertEquals(first, loaded.get().snapshot());
		assertEquals("west", loaded.get().updatedBy());

		PlayerDataStore.SaveOutcome stale = store.save(player, 0L, first, "west", now, 20);
		assertFalse(stale.accepted(), "a save based on revision 0 must be refused once revision 1 exists");
		assertEquals(1L, stale.newRevision());

		// --- a save based on the current revision is accepted and moves the revision ---
		PlayerDataSnapshot second = PlayerDataSnapshot.of(4400, "fp", now + 1, new byte[] {4, 5});
		PlayerDataStore.SaveOutcome next = store.save(player, 1L, second, "east", now + 1, 20);
		assertTrue(next.accepted(), next::detail);
		assertEquals(2L, next.newRevision());
		assertEquals(second, store.load(player).orElseThrow().snapshot());

		// --- history is bounded and newest first ---
		UUID historian = UUID.randomUUID();
		for (int i = 0; i < 5; i++) {
			PlayerDataStore.SaveOutcome outcome = store.save(historian, i, // base = current revision
					PlayerDataSnapshot.of(4400, "fp", now + i, new byte[] {(byte) i}), "west", now + i, 2);
			assertTrue(outcome.accepted(), outcome::detail);
		}
		List<PlayerDataStore.StoredSnapshot> history = store.history(historian, 10);
		assertEquals(2, history.size(), "keep-history 2 must keep exactly 2");
		assertEquals(5L, history.get(0).revision(), "newest first");
		assertEquals(4L, history.get(1).revision());
		assertTrue(store.history(historian, 0).isEmpty(), "a limit of 0 returns nothing");

		// --- lease: grant, refuse a second holder, renew by the holder, release, steal after expiry ---
		UUID leased = UUID.randomUUID();
		PlayerDataStore.LeaseOutcome granted = store.acquireLease(leased, "west", now, 60_000L);
		assertTrue(granted.acquired());
		assertFalse(granted.stolen());
		assertEquals("west", granted.ownerServer());

		PlayerDataStore.LeaseOutcome refused = store.acquireLease(leased, "east", now + 1_000L, 60_000L);
		assertFalse(refused.acquired(), "a live lease must not be taken");
		assertEquals("west", refused.ownerServer());

		PlayerDataStore.LeaseOutcome renewed = store.acquireLease(leased, "west", now + 2_000L, 60_000L);
		assertTrue(renewed.acquired());
		assertFalse(renewed.stolen(), "the holder renewing is not a steal");
		assertTrue(store.lease(leased).orElseThrow().expiresAtMillis() > now + 2_000L);

		assertFalse(store.releaseLease(leased, "east"), "only the holder may release");
		assertTrue(store.releaseLease(leased, "west"));
		assertTrue(store.lease(leased).isEmpty());
		assertTrue(store.acquireLease(leased, "east", now + 3_000L, 60_000L).acquired());

		// The lease from "east" expires at now + 63_000; the state must report that exactly.
		PlayerDataStore.LeaseState before = store.lease(leased).orElseThrow();
		assertTrue(before.isExpired(now + 63_000L), "expiresAt <= now means expired");
		assertFalse(before.isExpired(now + 62_000L));

		PlayerDataStore.LeaseOutcome stolen = store.acquireLease(leased, "north", now + 3_000L + 61_000L, 60_000L);
		assertTrue(stolen.acquired(), "an expired lease must be takeable");
		assertTrue(stolen.stolen(), "and the takeover must be reported as a steal");
		assertEquals("north", stolen.ownerServer());

		// --- the stolen lease is a fresh one, and reports expiry correctly itself ---
		PlayerDataStore.LeaseState state = store.lease(leased).orElseThrow();
		assertFalse(state.isExpired(now + 3_000L + 61_000L), "a just-taken lease is not expired");
		assertTrue(state.isExpired(now + 3_000L + 61_000L + 60_000L));

		// --- advancements: empty on a fresh player, compare-and-set save, content survives ---
		UUID advanced = UUID.randomUUID();
		assertTrue(store.loadAdvancements(advanced).isEmpty(), "a fresh player must have no advancements");

		byte[] payload = "{\"minecraft:story/root\":{\"done\":true}}".getBytes();
		PlayerDataStore.SaveOutcome advancementSaved = store.saveAdvancements(advanced, 0L, payload, "west", now);
		assertTrue(advancementSaved.accepted(), advancementSaved::detail);
		assertEquals(1L, advancementSaved.newRevision());

		Optional<PlayerDataStore.StoredAdvancements> storedAdvancements = store.loadAdvancements(advanced);
		assertTrue(storedAdvancements.isPresent());
		assertEquals(1L, storedAdvancements.get().revision());
		org.junit.jupiter.api.Assertions.assertArrayEquals(payload, storedAdvancements.get().payload());
		assertEquals("west", storedAdvancements.get().updatedBy());

		PlayerDataStore.SaveOutcome staleAdvancements =
				store.saveAdvancements(advanced, 0L, new byte[] {9}, "west", now + 1);
		assertFalse(staleAdvancements.accepted(), "a stale base revision must be refused for advancements too");
		assertEquals(1L, staleAdvancements.newRevision());
		org.junit.jupiter.api.Assertions.assertArrayEquals(payload,
				store.loadAdvancements(advanced).orElseThrow().payload(),
				"the stale payload must not overwrite the stored one");

		PlayerDataStore.SaveOutcome nextAdvancements =
				store.saveAdvancements(advanced, 1L, new byte[] {7, 7}, "east", now + 2);
		assertTrue(nextAdvancements.accepted(), nextAdvancements::detail);
		assertEquals(2L, nextAdvancements.newRevision());

		// --- describe is informative ---
		assertNotNull(store.describe());
		assertTrue(store.describe().length() > 10);
	}
}
