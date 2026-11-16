package net.opendonut.common.model;

import java.util.Locale;
import java.util.Optional;

/**
 * What the configuration validator should do when it meets a key it does not recognise.
 *
 * <p>The default is {@link #WARN}. Typos in YAML are otherwise invisible — {@code max-x} written as
 * {@code max_x} silently produces a default value and a region that is not the one the administrator
 * intended. Warning gives the feedback without blocking a staged rollout of a newer schema.
 */
public enum UnknownFieldPolicy {

	/** Silently ignore unknown keys. Useful for forward compatibility with newer configs. */
	IGNORE,

	/** Report unknown keys as warnings. This is the default. */
	WARN,

	/** Treat unknown keys as fatal configuration errors. Recommended for production. */
	ERROR;

	/**
	 * Parses the YAML value of {@code validation.unknown-fields}.
	 *
	 * @param raw the raw text, case-insensitive
	 * @return the matching policy, or empty when the text is not recognised
	 */
	public static Optional<UnknownFieldPolicy> parse(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		return switch (raw.trim().toLowerCase(Locale.ROOT)) {
			case "ignore" -> Optional.of(IGNORE);
			case "warn" -> Optional.of(WARN);
			case "error" -> Optional.of(ERROR);
			default -> Optional.empty();
		};
	}
}
