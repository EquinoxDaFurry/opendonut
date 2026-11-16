package net.opendonut.common.bridge;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A bounded, self-expiring set of recently seen message nonces.
 *
 * <h2>What it is for</h2>
 * The timestamp in a bridge frame only narrows the window in which a captured frame is accepted; inside
 * that window the same bytes could be delivered twice — by a retransmitting attacker, or by an honest
 * duplicate. Remembering the nonce turns "unlikely" into "impossible", so a
 * {@link BridgeMessage.TransferRequest} cannot be replayed to make a player bounce between two servers.
 *
 * <h2>Bounded memory</h2>
 * Entries expire after the timestamp tolerance (at most a few minutes), so in normal operation the set
 * holds only the traffic of the last few seconds. It is capped anyway: a flood of signed frames — which
 * implies the secret already leaked — must not be able to grow the proxy's heap. Once the cap is reached
 * the oldest entries are evicted, which is the right trade: the alternative is refusing all traffic.
 *
 * <h2>Thread safety</h2>
 * Safe for concurrent use, and wait-free on the hot path: registration is a single
 * {@link ConcurrentHashMap#putIfAbsent(Object, Object)}.
 */
public final class NonceCache {

	/** The default cap on remembered nonces. */
	public static final int DEFAULT_MAX_ENTRIES = 8192;

	private final int maxEntries;
	private final ConcurrentHashMap<ByteBuffer, Long> seenNonces = new ConcurrentHashMap<>();

	/**
	 * Creates a cache with the default cap.
	 */
	public NonceCache() {
		this(DEFAULT_MAX_ENTRIES);
	}

	/**
	 * Creates a cache with an explicit cap.
	 *
	 * @param maxEntries the maximum number of nonces to remember; must be positive
	 */
	public NonceCache(int maxEntries) {
		if (maxEntries < 1) {
			throw new IllegalArgumentException("maxEntries must be at least 1, got " + maxEntries);
		}
		this.maxEntries = maxEntries;
	}

	/**
	 * Records a nonce.
	 *
	 * @param nonce the nonce bytes
	 * @param expiresAtMillis the epoch millisecond after which the entry may be forgotten
	 * @return {@code true} if the nonce had not been seen before (the frame may be handled); {@code false}
	 *         if it is a replay or a duplicate delivery
	 */
	public boolean register(byte[] nonce, long expiresAtMillis) {
		if (nonce == null || nonce.length == 0) {
			return false;
		}
		// ByteBuffer has value-based equals/hashCode, which is exactly the key semantics needed here.
		ByteBuffer key = ByteBuffer.wrap(nonce.clone());
		Long previous = seenNonces.putIfAbsent(key, expiresAtMillis);
		if (previous == null) {
			if (seenNonces.size() > maxEntries) {
				evictOldest();
			}
			return true;
		}
		return false;
	}

	/**
	 * Removes entries that have expired.
	 *
	 * <p>Called automatically when the cache grows past its cap, and available for a periodic housekeeping
	 * task.
	 *
	 * @param nowMillis the current time in epoch milliseconds
	 * @return the number of entries removed
	 */
	public int prune(long nowMillis) {
		int removed = 0;
		for (Iterator<Map.Entry<ByteBuffer, Long>> it = seenNonces.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<ByteBuffer, Long> entry = it.next();
			if (entry.getValue() <= nowMillis) {
				it.remove();
				removed++;
			}
		}
		return removed;
	}

	/**
	 * Frees room by removing the entries that expire first.
	 *
	 * <p>Only reached when the cap is hit, which needs either an enormous amount of traffic inside one
	 * tolerance window or a hostile sender that already holds the secret.
	 */
	private void evictOldest() {
		int excess = seenNonces.size() - maxEntries;
		if (excess <= 0) {
			return;
		}
		seenNonces.entrySet().stream()
				.sorted(Map.Entry.comparingByValue())
				.limit(excess)
				.forEach(entry -> seenNonces.remove(entry.getKey(), entry.getValue()));
	}

	/**
	 * The number of remembered nonces.
	 *
	 * @return the current size
	 */
	public int size() {
		return seenNonces.size();
	}

	/** @return the configured cap */
	public int capacity() {
		return maxEntries;
	}

	/**
	 * Removes every remembered nonce.
	 *
	 * <p>Used when the secret is reloaded: nonces minted under the old secret must not shadow the new one.
	 */
	public void clear() {
		seenNonces.clear();
	}
}
