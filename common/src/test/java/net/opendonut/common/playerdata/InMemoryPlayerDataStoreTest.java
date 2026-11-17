package net.opendonut.common.playerdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The in-memory store against the shared contract, plus a concurrency check: many threads racing to save
 * must produce exactly one accepted writer per revision, never two.
 */
class InMemoryPlayerDataStoreTest {

	@Test
	@DisplayName("satisfies the store contract")
	void contract() {
		try (InMemoryPlayerDataStore store = new InMemoryPlayerDataStore()) {
			PlayerDataStoreContract.run(store);
		}
	}

	@Test
	@DisplayName("concurrent saves from the same base: exactly one wins per round")
	void concurrentSaves() throws InterruptedException {
		try (InMemoryPlayerDataStore store = new InMemoryPlayerDataStore()) {
			UUID player = UUID.randomUUID();
			int threads = 16;
			ExecutorService pool = Executors.newFixedThreadPool(threads);
			CountDownLatch start = new CountDownLatch(1);
			AtomicInteger accepted = new AtomicInteger();
			for (int i = 0; i < threads; i++) {
				final int index = i;
				pool.submit(() -> {
					try {
						start.await();
						PlayerDataStore.SaveOutcome outcome = store.save(player, 0L,
								PlayerDataSnapshot.of(4400, "fp", index, new byte[] {(byte) index}), "west",
								System.currentTimeMillis(), 20);
						if (outcome.accepted()) {
							accepted.incrementAndGet();
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				});
			}
			start.countDown();
			pool.shutdown();
			assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
			assertEquals(1, accepted.get(), "exactly one of the racing writers may win revision 1");
			assertEquals(1L, store.load(player).orElseThrow().revision());
		}
	}

	@Test
	@DisplayName("concurrent lease acquisition: exactly one winner")
	void concurrentLeases() throws InterruptedException {
		try (InMemoryPlayerDataStore store = new InMemoryPlayerDataStore()) {
			UUID player = UUID.randomUUID();
			int threads = 16;
			ExecutorService pool = Executors.newFixedThreadPool(threads);
			CountDownLatch start = new CountDownLatch(1);
			AtomicInteger acquired = new AtomicInteger();
			for (int i = 0; i < threads; i++) {
				final String server = "server-" + i;
				pool.submit(() -> {
					try {
						start.await();
						if (store.acquireLease(player, server, System.currentTimeMillis(), 60_000L).acquired()) {
							acquired.incrementAndGet();
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				});
			}
			start.countDown();
			pool.shutdown();
			assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
			assertEquals(1, acquired.get(), "exactly one server may be granted the lease");
		}
	}
}
