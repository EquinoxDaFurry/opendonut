package net.opendonut.common.playerdata;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.opendonut.common.model.PlayerDataSettings;

/**
 * The transfer-sequencing rules of {@link PlayerDataCoordinator}: the lease, the compare-and-set, and the
 * flush ordering, all without a proxy.
 */
class PlayerDataCoordinatorTest {

	private static final long NOW = 1_700_000_000_000L;

	private static PlayerDataSettings enabled() {
		return new PlayerDataSettings(true, "sqlite", "playerdata.db", 60, 60,
				PlayerDataSettings.OnFlushFailure.REFUSE_TRANSFER, PlayerDataSettings.SyncScope.SESSION, 20,
				48 * 1024, 10);
	}

	private static byte[] envelopeOf(byte[] payload) {
		return SnapshotCodec.encode(PlayerDataSnapshot.of(4400, "fp", NOW, payload));
	}

	private static PlayerDataCoordinator coordinator(PlayerDataStore store) {
		return new PlayerDataCoordinator(store, enabled());
	}

	@Test
	@DisplayName("the coordinator refuses to exist when player-data is disabled")
	void disabledRefused() {
		try (InMemoryPlayerDataStore store = new InMemoryPlayerDataStore()) {
			try {
				new PlayerDataCoordinator(store, PlayerDataSettings.DEFAULT);
			} catch (IllegalArgumentException expected) {
				return;
			}
			throw new AssertionError("expected IllegalArgumentException for a disabled setting");
		}
	}

	@Nested
	@DisplayName("saves")
	class Saves {

		@Test
		@DisplayName("an accepted save moves the revision and renews the lease")
		void accepted() {
			try (InMemoryPlayerDataStore store = new InMemoryPlayerDataStore()) {
				PlayerDataCoordinator coordinator = coordinator(store);
				UUID player = UUID.randomUUID();
				byte[] envelope = envelopeOf(new byte[] {1, 2, 3});

				PlayerDataCoordinator.SaveOutcome outcome =
						coordinator.handleSaveRequest(player, "west", 0L, envelope, NOW);
				assertTrue(outcome.accepted(), outcome::detail);
				assertEquals(1L, outcome.revision());

				assertEquals("west", store.lease(player).orElseThrow().ownerServer());
				assertTrue(store.lease(player).orElseThrow().expiresAtMillis() > NOW);
			}
		}

		@Test
		@DisplayName("a stale base revision is refused and changes nothing")
		void staleBase() {
			try (InMemoryPlayerDataStore store = new InMemoryPlayerDataStore()) {
				PlayerDataCoordinator coordinator = coordinator(store);
				UUID player = UUID.randomUUID();
				coordinator.handleSaveRequest(player, "west", 0L, envelopeOf(new byte[] {1}), NOW);

				PlayerDataCoordinator.SaveOutcome outcome =
						coordinator.handleSaveRequest(player, "west", 0L, envelopeOf(new byte[] {2}), NOW + 1);
				assertFalse(outcome.accepted());
				assertEquals(1L, outcome.revision());
				assertArrayEquals(new byte[] {1},
						store.load(player).orElseThrow().snapshot().nbt(), "the stale payload must not be stored");
			}
		}

		@Test
		@DisplayName("a save from a backend that does not hold the lease is refused")
		void notTheLeaseHolder() {
			try (InMemoryPlayerDataStore store = new InMemoryPlayerDataStore()) {
				PlayerDataCoordinator coordinator = coordinator(store);
				UUID player = UUID.randomUUID();
				// "east" holds a live lease.
				assertTrue(store.acquireLease(player, "east", NOW, 60_000L).acquired());

				PlayerDataCoordinator.SaveOutcome outcome =
						coordinator.handleSaveRequest(player, "west", 0L, envelopeOf(new byte[] {1}), NOW + 1);
				assertFalse(outcome.accepted());
				assertTrue(outcome.detail().contains("east"), outcome.detail());
				assertTrue(store.load(player).isEmpty(), "a refused write must not create a snapshot");
			}
		}

		@Test
		@DisplayName("an oversized envelope is refused before it reaches the store")
		void oversized() {
			try (InMemoryPlayerDataStore store = new InMemoryPlayerDataStore()) {
				PlayerDataCoordinator coordinator = coordinator(store);
				UUID player = UUID.randomUUID();
				byte[] huge = new byte[48 * 1024 + 1];
				// Random bytes do not compress, so the envelope itself exceeds the limit.
				new java.util.Random(1).nextBytes(huge);
				byte[] envelope = SnapshotCodec.encode(PlayerDataSnapshot.of(4400, "fp", NOW, huge));

				PlayerDataCoordinator.SaveOutcome outcome =
						coordinator.handleSaveRequest(player, "west", 0L, envelope, NOW);
				assertFalse(outcome.accepted());
				assertTrue(outcome.detail().contains("limit"), outcome.detail());
				assertTrue(store.load(player).isEmpty());
			}
		}

		@Test
		@DisplayName("a garbage envelope is refused before it reaches the store")
		void garbage() {
			try (InMemoryPlayerDataStore store = new InMemoryPlayerDataStore()) {
				PlayerDataCoordinator coordinator = coordinator(store);
				UUID player = UUID.randomUUID();
				PlayerDataCoordinator.SaveOutcome outcome = coordinator.handleSaveRequest(
						player, "west", 0L, new byte[] {1, 2, 3, 4, 5}, NOW);
				assertFalse(outcome.accepted());
				assertTrue(store.load(player).isEmpty());
			}
		}
	}

	@Nested
	@DisplayName("loads")
	class Loads {

		@Test
		@DisplayName("a player with nothing stored gets an empty answer and the lease")
		void empty() {
			try (InMemoryPlayerDataStore store = new InMemoryPlayerDataStore()) {
				PlayerDataCoordinator coordinator = coordinator(store);
				UUID player = UUID.randomUUID();
				PlayerDataCoordinator.LoadOutcome outcome =
						coordinator.handleLoadRequest(player, "east", NOW);
				assertTrue(outcome.leaseAcquired());
				assertFalse(outcome.leaseStolen());
				assertFalse(outcome.present());
				assertEquals(0L, outcome.revision());
				assertEquals(0, outcome.envelope().length);
				assertEquals("east", store.lease(player).orElseThrow().ownerServer());
			}
		}

		@Test
		@DisplayName("a stored snapshot is served intact and moves the lease to the asking backend")
		void stored() {
			try (InMemoryPlayerDataStore store = new InMemoryPlayerDataStore()) {
				PlayerDataCoordinator coordinator = coordinator(store);
				UUID player = UUID.randomUUID();
				byte[] payload = new byte[500];
				new java.util.Random(3).nextBytes(payload);
				byte[] envelope = envelopeOf(payload);
				coordinator.handleSaveRequest(player, "west", 0L, envelope, NOW);

				// The live sequence after a transfer: the proxy moves the lease to the destination
				// (moveLease) and only then does the destination ask for the snapshot on join.
				coordinator.moveLease(player, "west", "east", NOW + 1);
				PlayerDataCoordinator.LoadOutcome outcome =
						coordinator.handleLoadRequest(player, "east", NOW + 2);
				assertTrue(outcome.leaseAcquired());
				assertTrue(outcome.present());
				assertEquals(1L, outcome.revision());
				assertArrayEquals(envelope, outcome.envelope(), "the envelope must be served byte-identical");
				assertEquals("east", store.lease(player).orElseThrow().ownerServer());

				// The old owner can no longer write: the lease moved.
				PlayerDataCoordinator.SaveOutcome stale = coordinator.handleSaveRequest(
						player, "west", 1L, envelopeOf(new byte[] {9}), NOW + 3);
				assertFalse(stale.accepted(), "the previous owner must be refused after the lease moved");
			}
		}

		@Test
		@DisplayName("an expired lease is stolen and reported as a steal")
		void stolenLease() {
			try (InMemoryPlayerDataStore store = new InMemoryPlayerDataStore()) {
				PlayerDataCoordinator coordinator = coordinator(store);
				UUID player = UUID.randomUUID();
				store.acquireLease(player, "west", NOW, 1_000L); // expires at NOW + 1s

				PlayerDataCoordinator.LoadOutcome outcome =
						coordinator.handleLoadRequest(player, "east", NOW + 5_000L);
				assertTrue(outcome.leaseAcquired());
				assertTrue(outcome.leaseStolen());
				assertEquals("east", store.lease(player).orElseThrow().ownerServer());
			}
		}

		@Test
		@DisplayName("a live lease held by another backend refuses the load")
		void leaseRefused() {
			try (InMemoryPlayerDataStore store = new InMemoryPlayerDataStore()) {
				PlayerDataCoordinator coordinator = coordinator(store);
				UUID player = UUID.randomUUID();
				coordinator.handleSaveRequest(player, "west", 0L, envelopeOf(new byte[] {1}), NOW);
				// "west" still holds a live lease.
				PlayerDataCoordinator.LoadOutcome outcome =
						coordinator.handleLoadRequest(player, "east", NOW + 1);
				assertFalse(outcome.leaseAcquired());
				assertFalse(outcome.present(), "a refused load must not serve the payload");
				assertTrue(outcome.detail().contains("west"), outcome.detail());
			}
		}
	}

	@Nested
	@DisplayName("lease moves")
	class Moves {

		@Test
		@DisplayName("moveLease hands the lease to the destination and back")
		void move() {
			try (InMemoryPlayerDataStore store = new InMemoryPlayerDataStore()) {
				PlayerDataCoordinator coordinator = coordinator(store);
				UUID player = UUID.randomUUID();
				coordinator.handleSaveRequest(player, "west", 0L, envelopeOf(new byte[] {1}), NOW);

				String detail = coordinator.moveLease(player, "west", "east", NOW + 1);
				assertTrue(detail.contains("east"), detail);
				assertEquals("east", store.lease(player).orElseThrow().ownerServer());

				coordinator.moveLease(player, "east", "west", NOW + 2);
				assertEquals("west", store.lease(player).orElseThrow().ownerServer());
			}
		}
	}
}
