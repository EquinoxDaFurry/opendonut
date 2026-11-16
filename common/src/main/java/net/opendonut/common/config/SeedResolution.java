package net.opendonut.common.config;

import java.util.List;
import java.util.Objects;

/**
 * The resolved world seed and where it came from.
 *
 * @param seed the numeric seed every backend must generate with
 * @param origin how the value was obtained
 * @param detail a human readable explanation, suitable for a log line
 * @param issues findings produced while resolving, reported through the normal configuration channel
 */
public record SeedResolution(long seed, Origin origin, String detail, List<ValidationIssue> issues) {

	/**
	 * Where a resolved seed came from.
	 */
	public enum Origin {

		/** {@code world.seed} held a number; the configuration is authoritative. */
		CONFIGURED,

		/** {@code world.seed} was {@code random} and a seed had already been persisted. */
		STATE_FILE,

		/** {@code world.seed} was {@code random} and a new seed was generated and persisted. */
		GENERATED;

		/**
		 * Tests whether the value was produced by OpenDonut rather than by the administrator.
		 *
		 * @return {@code true} for {@link #GENERATED}
		 */
		public boolean isGenerated() {
			return this == GENERATED;
		}
	}

	/**
	 * Validates the record.
	 *
	 * @throws IllegalArgumentException if a component is missing
	 */
	public SeedResolution {
		Objects.requireNonNull(origin, "origin");
		Objects.requireNonNull(detail, "detail");
		issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
	}

	/** @return {@code true} if the seed was newly generated and persisted by this call */
	public boolean wasGenerated() {
		return origin.isGenerated();
	}

	/** @return {@code true} if at least one finding is fatal */
	public boolean hasErrors() {
		for (ValidationIssue issue : issues) {
			if (issue.isError()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Renders the resolution as a single log line.
	 *
	 * @return for example {@code seed 1234567890 (generated and persisted to seed.properties)}
	 */
	public String describe() {
		return "seed " + seed + " (" + detail + ")";
	}

	@Override
	public String toString() {
		return describe();
	}
}
