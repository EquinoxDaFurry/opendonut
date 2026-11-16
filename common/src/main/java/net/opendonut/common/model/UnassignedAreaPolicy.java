package net.opendonut.common.model;

import java.util.Locale;
import java.util.Optional;

/**
 * What a backend does with a player who walks into a coordinate that no server owns.
 *
 * <p>Unassigned areas are legal whenever {@code validation.require-full-coverage} is {@code false}.
 * The default, {@link #ALLOW}, keeps players where they are and logs a debug message: the region is
 * nobody's, so nobody can route them, and forcibly moving players would be surprising during staged
 * rollouts. {@link #RETURN} pushes the player back inside the owning server's region, which is the
 * stricter choice for a production network that must never let a player stand in a gap.
 */
public enum UnassignedAreaPolicy {

	/** Leave the player where they are and log the situation. */
	ALLOW,

	/** Teleport the player back inside the region of the server they are currently on. */
	RETURN;

	/**
	 * Parses the YAML value of {@code transfer.unassigned-area-policy}.
	 *
	 * @param raw the raw text, case-insensitive
	 * @return the matching policy, or empty when the text is not recognised
	 */
	public static Optional<UnassignedAreaPolicy> parse(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		return switch (raw.trim().toLowerCase(Locale.ROOT)) {
			case "allow", "ignore", "none" -> Optional.of(ALLOW);
			case "return", "push-back", "revert" -> Optional.of(RETURN);
			default -> Optional.empty();
		};
	}
}
