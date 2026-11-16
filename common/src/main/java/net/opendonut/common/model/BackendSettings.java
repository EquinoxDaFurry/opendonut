package net.opendonut.common.model;

import java.util.Optional;

/**
 * The {@code backend} section, which describes the instance reading the file rather than the network.
 *
 * <p>Every backend loads the same central configuration, so the file also has to say which of the
 * {@code servers} entries is <em>this</em> machine. The recommended setup leaves {@code server-id}
 * unset: the backend then finds itself by comparing its own listening port with the configured
 * addresses, which means adding or renaming a server never requires editing a per-machine file. The
 * explicit field exists for the cases auto-detection cannot solve — two backends behind one port via a
 * container runtime, an address written as a public hostname while the server binds loopback, or a
 * backend deliberately run as a spare.
 *
 * @param serverId the explicitly configured server id, or empty for auto-detection
 * @param enabled whether this backend should participate in transfers at all
 */
public record BackendSettings(Optional<String> serverId, boolean enabled) {

	/** The documented defaults: auto-detect, transfers enabled. */
	public static final BackendSettings DEFAULT = new BackendSettings(Optional.empty(), true);

	/**
	 * Validates the identifier when one is present.
	 *
	 * @throws IllegalArgumentException if an explicit id is present but not a valid identifier
	 */
	public BackendSettings {
		if (serverId == null) {
			serverId = Optional.empty();
		}
		serverId.ifPresent(id -> {
			if (!ServerDefinition.isValidId(id)) {
				throw new IllegalArgumentException("invalid backend.server-id '" + id
						+ "': use 1-64 characters from A-Z a-z 0-9 _ . - and start with a letter or digit");
			}
		});
	}

	/**
	 * Creates settings with an explicit server id.
	 *
	 * @param id the server id
	 * @return the settings
	 */
	public static BackendSettings withServerId(String id) {
		return new BackendSettings(Optional.of(id), true);
	}

	/** @return {@code true} when the backend must discover its own entry from the central config */
	public boolean autoDetect() {
		return serverId.isEmpty();
	}
}
