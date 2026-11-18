package net.opendonut.fabric;

import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;

import net.opendonut.common.bridge.BridgeMessage;
import net.opendonut.common.model.OpenDonutConfig;

/**
 * The backend's half of cross-server chat: a chat message is relayed to the proxy, which broadcasts it to
 * every other backend; a broadcast received here is shown locally.
 *
 * <h2>What is relayed, and how it is shown</h2>
 * Only the plain text of the signed chat body travels. The proxy never forwards signatures, and the
 * receiving backend shows the line as an unsigned system message ({@code <name> message}), so a relayed line
 * can always be told apart from the sender's own signed chat. A broadcast is never relayed again — the proxy
 * already excluded the source backend — so the message appears exactly once per server.
 *
 * <p>Disabled by default ({@code chat.enabled: false}); a network that upgrades to a build that has this
 * keeps behaving exactly as it did until an administrator turns it on.
 *
 * <p>Everything here runs on the Minecraft server thread, so no locking is needed.
 */
final class ChatSync {

	private final MinecraftServer server;
	private final BackendBridge bridge;
	private final Logger logger;
	private long relaysSent;
	private long broadcastsShown;

	/**
	 * Creates the synchroniser when the configuration enables it.
	 *
	 * @param server the running server
	 * @param config the configuration
	 * @param bridge the bridge, or {@code null} when it could not be set up
	 * @param logger the server logger
	 * @return the synchroniser, or empty when chat relay is off or the bridge is unavailable
	 */
	static Optional<ChatSync> create(
			MinecraftServer server, OpenDonutConfig config, BackendBridge bridge, Logger logger) {
		if (!config.chat().enabled()) {
			return Optional.empty();
		}
		if (bridge == null) {
			logger.error("OpenDonut: chat.enabled is true but the bridge is not up, so chat cannot be relayed "
					+ "across servers. Fix the bridge findings above, or set chat.enabled: false.");
			return Optional.empty();
		}
		ChatSync sync = new ChatSync(server, bridge, logger);
		logger.info("OpenDonut: chat relay active — local chat is broadcast to the other backends as unsigned "
				+ "relays");
		return Optional.of(sync);
	}

	private ChatSync(MinecraftServer server, BackendBridge bridge, Logger logger) {
		this.server = server;
		this.bridge = bridge;
		this.logger = logger;
	}

	/**
	 * A player sent a chat message: relay its plain text to the proxy.
	 *
	 * @param sender the sender
	 * @param message the signed chat message
	 */
	void onChatMessage(ServerPlayer sender, PlayerChatMessage message) {
		String content = message.signedContent();
		if (content == null || content.isBlank()) {
			return;
		}
		if (bridge.send(sender, BackendBridge.chatRelay(sender, content))) {
			relaysSent++;
		}
	}

	/**
	 * The proxy broadcast a chat message from another backend: show it to every local player as an unsigned
	 * relay. It is never relayed again.
	 *
	 * @param broadcast the broadcast
	 */
	void onBroadcast(BridgeMessage.ChatBroadcast broadcast) {
		server.getPlayerList().broadcastSystemMessage(
				Component.literal("<" + broadcast.senderName() + "> " + broadcast.content()), false);
		broadcastsShown++;
		logger.debug("OpenDonut: showed a relayed chat message from '{}' to the local players",
				broadcast.senderName());
	}

	/**
	 * Renders the synchroniser for {@code /opendonut}.
	 *
	 * @return a one-line description
	 */
	String describe() {
		return relaysSent + " relay(s) sent, " + broadcastsShown + " broadcast(s) shown";
	}
}
