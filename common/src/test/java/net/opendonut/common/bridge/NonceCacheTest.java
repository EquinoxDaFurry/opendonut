package net.opendonut.common.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The replay cache: it must remember a nonce within the tolerance window, forget it afterwards, and never
 * grow without bound.
 */
class NonceCacheTest {

	private static final long NOW = 1_700_000_000_000L;

	@Test
	@DisplayName("a nonce is accepted once and refused afterwards")
	void remembersNonces() {
		NonceCache cache = new NonceCache();
		byte[] nonce = BridgeCodec.randomNonce();
		assertTrue(cache.register(nonce, NOW + 30_000L));
		assertFalse(cache.register(nonce, NOW + 30_000L), "the same nonce must not be accepted twice");
		assertEquals(1, cache.size());
	}

	@Test
	@DisplayName("different nonces are independent")
	void distinctNonces() {
		NonceCache cache = new NonceCache();
		for (int i = 0; i < 100; i++) {
			assertTrue(cache.register(BridgeCodec.randomNonce(), NOW + 30_000L));
		}
		assertEquals(100, cache.size());
	}

	@Test
	@DisplayName("pruning removes expired entries and keeps live ones")
	void pruning() {
		NonceCache cache = new NonceCache();
		byte[] expired = BridgeCodec.randomNonce();
		byte[] live = BridgeCodec.randomNonce();
		cache.register(expired, NOW - 1L);
		cache.register(live, NOW + 60_000L);

		assertEquals(1, cache.prune(NOW), "the expired entry is dropped");
		assertEquals(1, cache.size());
		assertFalse(cache.register(live, NOW), "a live nonce is still remembered and refused as a replay");
		assertTrue(cache.register(expired, NOW), "an expired nonce may legitimately be seen again");
	}

	@Test
	@DisplayName("the cache stays bounded when a flood arrives")
	void bounded() {
		NonceCache cache = new NonceCache(64);
		for (int i = 0; i < 10_000; i++) {
			// Strictly increasing expiry times, so eviction has a well defined order.
			cache.register(BridgeCodec.randomNonce(), NOW + i);
		}
		assertTrue(cache.size() <= 65, "the cache must not exceed its cap, was " + cache.size());
		assertEquals(64, cache.capacity());
	}

	@Test
	@DisplayName("a null or empty nonce is refused")
	void refusesEmptyNonces() {
		NonceCache cache = new NonceCache();
		assertFalse(cache.register(null, NOW));
		assertFalse(cache.register(new byte[0], NOW));
	}

	@Test
	@DisplayName("clearing forgets everything, which is what a secret change requires")
	void clearing() {
		NonceCache cache = new NonceCache();
		cache.register(BridgeCodec.randomNonce(), NOW + 1000L);
		cache.clear();
		assertEquals(0, cache.size());
	}

	@Test
	@DisplayName("a cap below one is refused")
	void invalidCapacity() {
		assertThrows(IllegalArgumentException.class, () -> new NonceCache(0));
		assertThrows(IllegalArgumentException.class, () -> new NonceCache(-5));
	}
}
