package net.opendonut.common.model;

/**
 * Proxy-side behaviour, ignored by the Fabric backends.
 *
 * @param autoRegisterBackends when {@code true} the Velocity plugin registers every backend from the
 *                             {@code servers} list with the proxy itself, so adding a server is a
 *                             matter of editing one YAML file instead of also editing
 *                             {@code velocity.toml}. The names must not collide with servers that
 *                             {@code velocity.toml} already defines.
 * @param requireBackendHello when {@code true} (the default) the proxy only routes players to a
 *                            backend that has sent a valid {@code HELLO} and whose world name, seed
 *                            and region bounds match the central configuration. Set to
 *                            {@code false} only while bringing a new backend online.
 * @param disconnectUnroutedPlayers when {@code true}, a player who is routed into a coordinate that
 *                                  no server owns is disconnected with an explanatory message
 *                                  instead of being left where they are.
 */
public record ProxySettings(
		boolean autoRegisterBackends,
		boolean requireBackendHello,
		boolean disconnectUnroutedPlayers) {

	/** The documented defaults, used when the {@code proxy} section is absent. */
	public static final ProxySettings DEFAULT = new ProxySettings(true, true, false);
}
