package net.opendonut.common.model;

import java.util.Objects;

/**
 * The {@code world.seed} field: either an explicit numeric seed or the literal {@code random}.
 *
 * <p>Keeping the raw text as well as the parsed value lets the CLI and the logs echo exactly what
 * the administrator wrote, which matters when a seed fails to parse.
 *
 * @param explicit whether a numeric seed was configured
 * @param value the numeric seed, only meaningful when {@link #explicit()} is {@code true}
 * @param raw the literal text from the configuration file
 */
public record SeedSpec(boolean explicit, long value, String raw) {

	/** The literal that requests a generated, persisted seed. */
	public static final String RANDOM_LITERAL = "random";

	private static final SeedSpec RANDOM = new SeedSpec(false, 0L, RANDOM_LITERAL);

	/**
	 * Validates the consistency of the record.
	 *
	 * @throws IllegalArgumentException if the raw text is missing
	 */
	public SeedSpec {
		Objects.requireNonNull(raw, "raw");
	}

	/**
	 * The {@code seed: random} specification.
	 *
	 * @return the shared random-seed specification
	 */
	public static SeedSpec random() {
		return RANDOM;
	}

	/**
	 * An explicit numeric seed.
	 *
	 * @param seed the seed value, may be negative
	 * @return the specification
	 */
	public static SeedSpec of(long seed) {
		return new SeedSpec(true, seed, Long.toString(seed));
	}

	/**
	 * Parses a YAML scalar into a seed specification.
	 *
	 * <p>Accepts {@code random} (case-insensitive), a decimal integer, and a hexadecimal literal
	 * prefixed with {@code 0x}. Quoted digits are accepted, which matters because a large
	 * administrator-supplied seed may be quoted to stop YAML from turning it into something else.
	 *
	 * @param scalar the raw text, already converted to a string by the YAML layer
	 * @return the parsed specification
	 * @throws IllegalArgumentException if the text is neither {@code random} nor an integer
	 */
	public static SeedSpec parse(String scalar) {
		if (scalar == null || scalar.isBlank()) {
			throw new IllegalArgumentException("seed must not be empty; use a number or 'random'");
		}
		String trimmed = scalar.trim();
		if (trimmed.equalsIgnoreCase(RANDOM_LITERAL)) {
			return random();
		}
		String numeric = trimmed;
		int radix = 10;
		boolean negative = false;
		if (numeric.startsWith("-")) {
			negative = true;
			numeric = numeric.substring(1);
		} else if (numeric.startsWith("+")) {
			numeric = numeric.substring(1);
		}
		if (numeric.startsWith("0x") || numeric.startsWith("0X")) {
			radix = 16;
			numeric = numeric.substring(2);
		}
		if (numeric.isEmpty()) {
			throw new IllegalArgumentException("seed '" + scalar + "' is not a number");
		}
		try {
			long parsed = Long.parseLong(numeric, radix);
			return of(negative ? -parsed : parsed);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("seed '" + scalar
					+ "' is not a valid 64-bit integer and is not the literal 'random'");
		}
	}

	/**
	 * Renders the specification as it should appear back in a configuration file.
	 *
	 * @return {@code random} or the decimal seed
	 */
	public String describe() {
		return explicit ? Long.toString(value) : RANDOM_LITERAL;
	}

	@Override
	public String toString() {
		return describe();
	}
}
