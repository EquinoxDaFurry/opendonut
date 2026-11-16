package net.opendonut.common.model;

import java.util.Objects;

/**
 * Knobs for the configuration validator.
 *
 * <p>The defaults are deliberately strict about things that are always wrong (overlaps, inverted
 * ranges) and permissive about things that are a matter of intended design (leaving part of the
 * world unassigned while building out a network).
 *
 * @param requireFullCoverage when {@code true}, any part of the world border not owned by a server is
 *                            a fatal error; when {@code false} (the default) it is a warning.
 *                            Administrators who deliberately run unassigned wilderness, or who are
 *                            migrating a network one region at a time, keep this at {@code false}.
 * @param strictChunkAlignment when {@code true} (the default) region bounds and the world border must
 *                             line up with 16-block chunk boundaries. Misalignment is then fatal
 *                             because it makes two servers responsible for the same chunk.
 * @param unknownFields what to do with unrecognised configuration keys
 */
public record ValidationOptions(
		boolean requireFullCoverage,
		boolean strictChunkAlignment,
		UnknownFieldPolicy unknownFields) {

	/** The documented defaults, used whenever the {@code validation} section is absent. */
	public static final ValidationOptions DEFAULT =
			new ValidationOptions(false, true, UnknownFieldPolicy.WARN);

	/**
	 * Validates the record.
	 *
	 * @throws IllegalArgumentException if a component is missing
	 */
	public ValidationOptions {
		Objects.requireNonNull(unknownFields, "unknownFields");
	}

	/**
	 * Returns a copy with a different full-coverage requirement.
	 *
	 * @param value the new value
	 * @return a copy of this options object
	 */
	public ValidationOptions withRequireFullCoverage(boolean value) {
		return new ValidationOptions(value, strictChunkAlignment, unknownFields);
	}

	/**
	 * Returns a copy with a different alignment requirement.
	 *
	 * @param value the new value
	 * @return a copy of this options object
	 */
	public ValidationOptions withStrictChunkAlignment(boolean value) {
		return new ValidationOptions(requireFullCoverage, value, unknownFields);
	}

	/**
	 * Returns a copy with a different unknown-field policy.
	 *
	 * @param value the new policy
	 * @return a copy of this options object
	 */
	public ValidationOptions withUnknownFields(UnknownFieldPolicy value) {
		return new ValidationOptions(requireFullCoverage, strictChunkAlignment, value);
	}
}
