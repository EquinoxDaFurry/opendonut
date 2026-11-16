package net.opendonut.common.model;

import java.util.Objects;

/**
 * Settings of the authenticated backend &lt;-&gt; proxy channel.
 *
 * <h2>Why a shared secret</h2>
 * Velocity delivers plugin messages from a backend to a proxy plugin by pretending to be the client.
 * From the backend's point of view those packets are indistinguishable from packets a player's own
 * client could send, and a modified client can send anything it likes on any channel. A client that
 * forged a "transfer me to the richest region" message would otherwise be routed by the proxy.
 *
 * <p>Every bridge message therefore carries an HMAC-SHA256 tag over a shared secret plus a nonce and
 * a timestamp. The proxy verifies the tag before acting on anything, and rejects stale or replayed
 * messages. The secret lives in its own file (never in the YAML, never in Git) and must be identical
 * on the proxy and on every backend.
 *
 * @param enabled whether this instance participates in the bridge at all
 * @param channel the plugin message channel, {@code namespace:path}
 * @param secretFile path of the shared-secret file, resolved relative to the directory containing the
 *                   configuration file when not absolute
 * @param timestampToleranceSeconds how far a message timestamp may deviate from the local clock
 *                                  before the message is rejected
 */
public record BridgeSettings(
		boolean enabled,
		String channel,
		String secretFile,
		int timestampToleranceSeconds) {

	/** The channel OpenDonut uses by default. */
	public static final String DEFAULT_CHANNEL = "opendonut:bridge";

	/** The default secret file location, relative to the configuration directory. */
	public static final String DEFAULT_SECRET_FILE = "secrets/bridge-secret.txt";

	/** Documented defaults, used when the {@code bridge} section is absent. */
	public static final BridgeSettings DEFAULT =
			new BridgeSettings(true, DEFAULT_CHANNEL, DEFAULT_SECRET_FILE, 30);

	/**
	 * Validates the settings.
	 *
	 * @throws IllegalArgumentException if the channel is not {@code namespace:path} or the tolerance
	 *                                  is out of a sane range
	 */
	public BridgeSettings {
		Objects.requireNonNull(channel, "channel");
		Objects.requireNonNull(secretFile, "secretFile");
		if (channel.isBlank() || channel.indexOf(':') <= 0 || channel.endsWith(":")) {
			throw new IllegalArgumentException(
					"bridge channel must be in namespace:path form, for example '" + DEFAULT_CHANNEL + "'");
		}
		if (timestampToleranceSeconds < 1 || timestampToleranceSeconds > 600) {
			throw new IllegalArgumentException(
					"bridge timestamp tolerance must be between 1 and 600 seconds, got " + timestampToleranceSeconds);
		}
	}

	/** @return the channel namespace, the part before the colon */
	public String channelNamespace() {
		return channel.substring(0, channel.indexOf(':'));
	}

	/** @return the channel path, the part after the colon */
	public String channelPath() {
		return channel.substring(channel.indexOf(':') + 1);
	}

	/**
	 * Returns a copy with the bridge disabled.
	 *
	 * @return a disabled copy of these settings
	 */
	public BridgeSettings disabled() {
		return new BridgeSettings(false, channel, secretFile, timestampToleranceSeconds);
	}
}
