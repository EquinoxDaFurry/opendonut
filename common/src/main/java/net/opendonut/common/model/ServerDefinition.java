package net.opendonut.common.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One backend server of the OpenDonut world, together with the rectangular area of the global
 * coordinate space that it owns.
 *
 * @param id unique, stable identifier used as the Velocity server name and as the routing key in
 *           the bridge protocol, for example {@code west} or {@code survival-3}
 * @param address the socket the backend listens on
 * @param bounds the region this backend owns
 */
public record ServerDefinition(String id, ServerAddress address, RegionBounds bounds) {

	/**
	 * Server identifiers must be safe in three places at once: as a Velocity server name (used in
	 * {@code /server <name>} and in the proxy configuration), as a JSON key, and as a log token.
	 * Restricting the character set avoids a whole category of quoting problems.
	 */
	private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

	/**
	 * Validates the identifier.
	 *
	 * @throws IllegalArgumentException if the id is blank or contains unsupported characters
	 */
	public ServerDefinition {
		Objects.requireNonNull(id, "id");
		if (!ID_PATTERN.matcher(id).matches()) {
			throw new IllegalArgumentException("invalid server id '" + id
					+ "': use 1-64 characters from A-Z a-z 0-9 _ . - and start with a letter or digit");
		}
		Objects.requireNonNull(address, "address");
		Objects.requireNonNull(bounds, "bounds");
	}

	/**
	 * Reports whether an identifier is acceptable, without throwing.
	 *
	 * @param id the candidate identifier, may be {@code null}
	 * @return {@code true} if the id matches the identifier pattern
	 */
	public static boolean isValidId(String id) {
		return id != null && ID_PATTERN.matcher(id).matches();
	}

	/**
	 * Tests whether this server owns a block coordinate.
	 *
	 * @param x global block X
	 * @param z global block Z
	 * @return {@code true} if the coordinate lies inside this server's region
	 */
	public boolean owns(int x, int z) {
		return bounds.contains(x, z);
	}

	/**
	 * Human readable one-line summary used in logs and CLI reports.
	 *
	 * @return for example {@code west (127.0.0.1:25566) X=[-100000,0) Z=[-100000,100000)}
	 */
	public String describe() {
		return id + " (" + address + ") " + bounds.describe();
	}

	@Override
	public String toString() {
		return describe();
	}
}
