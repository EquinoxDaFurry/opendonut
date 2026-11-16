package net.opendonut.fabric;

import java.nio.file.Path;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.opendonut.common.config.ConfigLoader;
import net.opendonut.common.model.BridgeSettings;

/**
 * The Fabric entrypoint: wiring, and nothing else.
 *
 * <p>All of OpenDonut's behaviour lives in {@link OpenDonutBackend} and in the Minecraft-free
 * {@code common} module. This class exists to attach that behaviour to the server's lifecycle:
 *
 * <ul>
 *   <li>payload types are registered during initialisation, before any player can connect;</li>
 *   <li>the configuration is loaded when the server starts — not at class load — because the identity check
 *       needs the listening port and the loaded world;</li>
 *   <li>boundary checks run at the end of every server tick;</li>
 *   <li>the bridge is fed from the play-phase packet handler and from the join event.</li>
 * </ul>
 *
 * <h2>Failure behaviour</h2>
 * If the configuration cannot be used, the mod stays loaded and inert, having logged the whole validation
 * report. It deliberately does <em>not</em> crash the server: a broken routing file must not take a
 * Minecraft server down, and an inert OpenDonut is obvious in the log and in {@code /opendonut status}.
 */
public final class OpenDonutMod implements ModInitializer {

	/** The mod id, used by Fabric and by the logger name. */
	public static final String MOD_ID = "opendonut";

	private static final Logger LOGGER = LoggerFactory.getLogger("OpenDonut");

	private OpenDonutBackend backend;

	@Override
	public void onInitialize() {
		// The payload type must be registered before any player can connect, so the channel is read from the
		// configuration here rather than when the server starts. A configuration that cannot be read at this
		// point is not fatal: the default channel is used, and the normal startup path reports the real problem.
		BridgePayload.register(configuredChannel());

		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			Path configDirectory = FabricLoader.getInstance().getConfigDir();
			if (!OpenDonutBackend.configurationExists(configDirectory)
					&& System.getProperty("opendonut.config") == null) {
				LOGGER.warn("OpenDonut is installed but {} does not exist, so it will do nothing. Copy "
								+ "config/opendonut.example.yml there, or point -Dopendonut.config at it.",
						configDirectory.resolve("opendonut.yml"));
				return;
			}
			backend = OpenDonutBackend.create(server, configDirectory, LOGGER).orElse(null);
		});

		// Once the world exists, finish the checks that need it: level name, world seed, accepts-transfers. The
		// configuration and the bridge are already up by this point.
		//
		// A second creation attempt is included for the case where the first pass could not identify the
		// backend, because the port a server reports while it is starting is not always the port it ends up
		// listening on.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Path configDirectory = FabricLoader.getInstance().getConfigDir();
			if (backend == null) {
				backend = OpenDonutBackend.create(server, configDirectory, LOGGER, false).orElse(null);
			}
			if (backend != null) {
				backend.completeStartup(server, configDirectory);
			}
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			if (backend != null) {
				LOGGER.info("OpenDonut: stopping after {} transfer request(s), {} refused by the proxy",
						backend.transfersRequested(), backend.transfersRejected());
				backend = null;
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (backend != null) {
				backend.tick(server);
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (backend != null) {
				backend.onPlayerJoin(handler.getPlayer());
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (backend != null) {
				backend.onPlayerDisconnect(handler.getPlayer());
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(BridgePayload.payloadType(), (payload, context) -> {
			if (backend == null) {
				return;
			}
			// Runs on the server thread, which is where the tracker expects to be touched.
			backend.bridge().ifPresent(bridge -> bridge.receive(context.player(), payload.frame()));
		});

		// The tree is built once but reads the backend at execution time, so it also answers sensibly — and
		// says why — when the configuration was unusable and OpenDonut stayed inert.
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(OpenDonutCommand.build(() -> backend)));

		LOGGER.info("OpenDonut {} loaded; waiting for the server to start", version());
	}

	/**
	 * The bridge channel from the configuration, or the documented default.
	 *
	 * <p>Read at mod initialisation because Fabric requires the payload type to be registered before players
	 * can connect. Only the channel is needed here; the full configuration is loaded and validated when the
	 * server starts, and a broken file is reported there with its findings.
	 *
	 * @return the channel from {@code bridge.channel}, or {@code opendonut:bridge}
	 */
	private static String configuredChannel() {
		// The same path rule the backend uses, deliberately: two implementations of "where is the
		// configuration" is how a component ends up reading a different file from the one an administrator
		// edited.
		Path configPath = OpenDonutBackend.configPathFor(FabricLoader.getInstance().getConfigDir());
		// ConfigLoader never throws for a bad file: it reports findings and yields an empty model, which is
		// exactly the case where the default channel is the right fallback.
		return ConfigLoader.load(configPath)
				.config()
				.map(config -> config.bridge().channel())
				.orElse(BridgeSettings.DEFAULT_CHANNEL);
	}

	/**
	 * The running OpenDonut version, from the mod metadata.
	 *
	 * @return the version string, or {@code (development build)} when unavailable
	 */
	public static String version() {
		return FabricLoader.getInstance()
				.getModContainer(MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("(development build)");
	}
}
