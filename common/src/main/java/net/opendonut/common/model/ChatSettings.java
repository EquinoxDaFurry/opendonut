package net.opendonut.common.model;

/**
 * The {@code chat} section: whether chat messages are relayed across backends through the proxy.
 *
 * <p>Each backend shows its local chat to its local players as usual; with this enabled, a message is also
 * relayed to every other backend, which displays it as an unsigned relay ({@code <name> message}) and never
 * re-relays it. Signatures are never forwarded, so a relayed line can always be told apart from the sender's
 * own signed chat.
 *
 * <p>Disabled by default, like the other cross-server features: a network that upgrades to this build keeps
 * behaving exactly as it did until an administrator turns it on.
 *
 * @param enabled whether chat is relayed across servers
 */
public record ChatSettings(boolean enabled) {

	/** Disabled by default. */
	public static final ChatSettings DEFAULT = new ChatSettings(false);

	/**
	 * Renders the settings for a log line or a validation report.
	 *
	 * @return a one-line description
	 */
	public String describe() {
		return enabled ? "relay across backends" : "disabled";
	}
}
