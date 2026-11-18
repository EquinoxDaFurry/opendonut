package net.opendonut.common.model;

/**
 * Proxy-side behaviour, ignored by the Fabric backends.
 *
 * <h2>How a backend becomes trusted</h2>
 * A backend announces itself with a {@code HELLO}, and the only channel it can announce itself through is a
 * player's connection — Velocity has no server-to-server plugin message path. That has a consequence worth
 * stating plainly, because it shapes the rules below:
 *
 * <blockquote>
 * A backend that has never had a player <em>cannot</em> have announced itself.
 * </blockquote>
 *
 * <p>Requiring a verified {@code HELLO} before allowing a transfer would therefore make the first transfer to
 * any backend impossible: the transfer is the very thing that would give it its first player. The three
 * admission states in the proxy exist to avoid that trap while keeping the check meaningful:
 *
 * <ul>
 *   <li><strong>verified</strong> — admit, and place the player immediately;</li>
 *   <li><strong>unknown</strong> — admit <em>provisionally</em>: the player is moved, and the arrival itself
 *       triggers the backend's announcement. The proxy withholds the placement until the backend has verified,
 *       and returns the player if it does not (see {@code arrival-verification-timeout-seconds});</li>
 *   <li><strong>rejected</strong> — refuse, because this backend has told the proxy that it is generating a
 *       different world or owns a different region. A player is never sent to it.</li>
 * </ul>
 *
 * @param autoRegisterBackends when {@code true} the Velocity plugin registers every backend from the
 *                             {@code servers} list with the proxy itself, so adding a server is a matter of
 *                             editing one YAML file instead of also editing {@code velocity.toml}. The names
 *                             must not collide with servers that {@code velocity.toml} already defines.
 * @param requireBackendHello when {@code true} (the default) the proxy verifies every backend against the
 *                            central configuration: a backend that has announced itself must match, and one
 *                            that has not is verified while a player arrives rather than being refused
 *                            outright. Set to {@code false} only when you accept that players may be placed on
 *                            a backend whose world, seed or region does not match.
 * @param disconnectUnroutedPlayers when {@code true}, a player who is routed into a coordinate that no server
 *                                  owns is disconnected with an explanatory message instead of being left where
 *                                  they are.
 * @param arrivalVerificationTimeoutSeconds how long the proxy may hold a player on a backend that has not yet
 *                                  verified itself. The player is placed once the backend verifies, and is
 *                                  returned to the server they came from if the timeout passes first. Must be
 *                                  shorter than the backends' {@code transfer.max-pending-seconds} (15 by
 *                                  default), otherwise the backend gives up on the transfer before the proxy
 *                                  has finished deciding.
 * @param rememberLastServer when {@code true} (the default), the proxy remembers which backend each player
 *                           was last connected to and sends them straight there on their next login, instead
 *                           of letting the default initial server pick load chunks on a server they are
 *                           immediately moved away from. The memory is in-process: after a proxy restart the
 *                           first login falls back to the default behaviour.
 */
public record ProxySettings(
		boolean autoRegisterBackends,
		boolean requireBackendHello,
		boolean disconnectUnroutedPlayers,
		int arrivalVerificationTimeoutSeconds,
		boolean rememberLastServer) {

	/** How long the proxy waits for an arriving player's backend to announce itself, by default. */
	public static final int DEFAULT_ARRIVAL_VERIFICATION_SECONDS = 5;

	/** The documented defaults, used when the {@code proxy} section is absent. */
	public static final ProxySettings DEFAULT =
			new ProxySettings(true, true, false, DEFAULT_ARRIVAL_VERIFICATION_SECONDS, true);

	/**
	 * Validates the settings.
	 *
	 * @throws IllegalArgumentException if the timeout is outside the supported range
	 */
	public ProxySettings {
		if (arrivalVerificationTimeoutSeconds < 1 || arrivalVerificationTimeoutSeconds > 30) {
			throw new IllegalArgumentException(
					"proxy.arrival-verification-timeout-seconds must be between 1 and 30, got "
							+ arrivalVerificationTimeoutSeconds);
		}
	}

	/**
	 * The arrival timeout in milliseconds.
	 *
	 * @return the timeout
	 */
	public long arrivalVerificationTimeoutMillis() {
		return arrivalVerificationTimeoutSeconds * 1000L;
	}

	/**
	 * Returns a copy with a different full-verification requirement.
	 *
	 * @param value the new value
	 * @return a copy of these settings
	 */
	public ProxySettings withRequireBackendHello(boolean value) {
		return new ProxySettings(autoRegisterBackends, value, disconnectUnroutedPlayers,
				arrivalVerificationTimeoutSeconds, rememberLastServer);
	}
}
