package net.opendonut.common.model;

import java.util.Locale;
import java.util.Optional;

/**
 * What a backend does when a transfer it requested did not happen.
 *
 * <p>A failed transfer leaves the player standing in a region owned by somebody else. That is the
 * dangerous state: two servers would start generating and writing the same chunks. {@link #REVERT}
 * is therefore the default — the player is moved back to a safe spot inside their own region and the
 * transfer is retried after the cooldown. {@link #LOG_ONLY} is useful for diagnosing a network where
 * backends are frequently offline and losing player progress to a teleport is worse than the block
 * conflict.
 */
public enum TransferFailurePolicy {

	/** Push the player back inside their own region and log the failure. */
	REVERT,

	/** Leave the player where they are, log the failure, and retry after the cooldown. */
	LOG_ONLY;

	/**
	 * Parses the YAML value of {@code transfer.on-transfer-failure}.
	 *
	 * @param raw the raw text, case-insensitive
	 * @return the matching policy, or empty when the text is not recognised
	 */
	public static Optional<TransferFailurePolicy> parse(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		return switch (raw.trim().toLowerCase(Locale.ROOT)) {
			case "revert", "push-back", "teleport-back" -> Optional.of(REVERT);
			case "log", "log-only", "none" -> Optional.of(LOG_ONLY);
			default -> Optional.empty();
		};
	}
}
