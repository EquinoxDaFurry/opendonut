package net.opendonut.fabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;

import net.opendonut.common.bridge.BridgeMessage;
import net.opendonut.common.model.OpenDonutConfig;
import net.opendonut.common.model.PlayerDataSettings;

/**
 * The backend's half of advancement synchronisation: advancements live in a per-player JSON file in each
 * backend's world folder ({@code world/advancements/<uuid>.json}), not in the player data, so they travel
 * as their own payload through the proxy's store.
 *
 * <h2>Flow</h2>
 * <ul>
 *   <li><strong>Load:</strong> on join, right after the player-data load request on the same connection, the
 *       backend asks the proxy for the stored payload. The answer's raw bytes are written to the local
 *       advancements file, {@code PlayerAdvancements.reload} re-reads it, and a full advancements packet is
 *       sent so the client's advancement screen is correct immediately, not just at the next login;</li>
 *   <li><strong>Save:</strong> on the periodic tick, on disconnect, and on the proxy's flush before a
 *       transfer, the local file (flushed to disk first) is read and sent with the current revision as its
 *       base — the store applies it compare-and-set, exactly like player data, so a stale backend cannot
 *       overwrite fresher progress.</li>
 * </ul>
 *
 * <p>Gated on {@code player-data.enabled} and {@code player-data.sync-advancements} and a working bridge;
 * when any is off the local files simply stay local, which is the behaviour of older builds.
 *
 * <p>Everything here runs on the Minecraft server thread, so the plain map is safe.
 */
final class AdvancementSync {

	/** What is stored for a player who has never earned anything: an empty advancements object. */
	private static final byte[] EMPTY_JSON = "{}".getBytes(StandardCharsets.UTF_8);

	private final MinecraftServer server;
	private final PlayerDataSettings settings;
	private final BackendBridge bridge;
	private final String serverId;
	private final Logger logger;

	private final Map<UUID, Long> revisions = new HashMap<>();
	private long lastPeriodicSaveMillis;
	private long savesSent;
	private long savesRefused;
	private long loadsApplied;

	/**
	 * Creates the synchroniser when the configuration enables it.
	 *
	 * @param server the running server
	 * @param config the configuration
	 * @param bridge the bridge, or {@code null} when it could not be set up
	 * @param serverId this backend's id
	 * @param logger the server logger
	 * @return the synchroniser, or empty when advancement sync is off or the bridge is unavailable
	 */
	static Optional<AdvancementSync> create(
			MinecraftServer server, OpenDonutConfig config, BackendBridge bridge, String serverId, Logger logger) {
		if (!config.playerData().enabled() || !config.playerData().syncAdvancements()) {
			return Optional.empty();
		}
		if (bridge == null) {
			logger.error("OpenDonut: player-data.sync-advancements is on but the bridge is not up, so "
					+ "advancements cannot be synchronised. Fix the bridge findings above, or set "
					+ "player-data.sync-advancements: false.");
			return Optional.empty();
		}
		AdvancementSync sync = new AdvancementSync(server, config.playerData(), bridge, serverId, logger);
		logger.info("OpenDonut: advancement sync ready on this backend — saves on transfer, disconnect and "
				+ "every {} s", sync.settings.syncIntervalSeconds());
		return Optional.of(sync);
	}

	private AdvancementSync(
			MinecraftServer server, PlayerDataSettings settings, BackendBridge bridge, String serverId,
			Logger logger) {
		this.server = server;
		this.settings = settings;
		this.bridge = bridge;
		this.serverId = serverId;
		this.logger = logger;
	}

	/**
	 * A player joined: ask the proxy for their stored advancement progress.
	 *
	 * @param player the player
	 */
	void onPlayerJoin(ServerPlayer player) {
		revisions.put(player.getUUID(), 0L);
		bridge.send(player, BackendBridge.advancementsLoadRequest(player, serverId));
	}

	/**
	 * A player disconnected: save their advancements one last time, then forget the revision.
	 *
	 * @param player the player
	 */
	void onPlayerDisconnect(ServerPlayer player) {
		saveNow(player, "disconnect");
		revisions.remove(player.getUUID());
	}

	/**
	 * Periodic save for every online player, at {@code player-data.sync-interval-seconds}.
	 *
	 * @param server the running server
	 */
	void tick(MinecraftServer server) {
		int intervalSeconds = settings.syncIntervalSeconds();
		if (intervalSeconds <= 0) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastPeriodicSaveMillis < settings.syncIntervalMillis()) {
			return;
		}
		lastPeriodicSaveMillis = now;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			saveNow(player, "periodic");
		}
	}

	/**
	 * The proxy demanded a save before moving the player.
	 *
	 * @param request the flush request
	 */
	void onFlushRequest(BridgeMessage.PlayerDataFlushRequest request) {
		if (!request.fromServerId().equals(serverId)) {
			logger.warn("OpenDonut: a flush request for {} says it is from '{}' but this server is '{}'; "
					+ "ignoring it", request.playerId(), request.fromServerId(), serverId);
			return;
		}
		ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
		if (player == null) {
			logger.warn("OpenDonut: cannot flush the advancements of {} — they are not on this server any "
					+ "more", request.playerId());
			return;
		}
		saveNow(player, "flush requested by the proxy before the move to '" + request.toServerId() + "'");
	}

	/**
	 * The proxy answered a load request: write the payload to the local advancements file, reload from it,
	 * and resync the client.
	 *
	 * @param response the response
	 */
	void onLoadResponse(BridgeMessage.AdvancementsLoadResponse response) {
		revisions.put(response.playerId(), response.revision());
		if (!response.present()) {
			logger.info("OpenDonut: the proxy has no stored advancements for {} yet ({}); keeping the local "
					+ "file", response.playerId(), response.detail());
			return;
		}
		Path file = advancementsFile(response.playerId());
		try {
			Files.createDirectories(file.getParent());
			Files.write(file, response.payload());
		} catch (IOException | RuntimeException e) {
			logger.error("OpenDonut: could not write the stored advancements of {} to {}: {}",
					response.playerId(), file, e.toString(), e);
			return;
		}
		ServerPlayer player = server.getPlayerList().getPlayer(response.playerId());
		if (player == null) {
			// The file is written, so the next join on this server reads it; only the live resync is lost.
			logger.info("OpenDonut: stored the advancements of {} ({} bytes, revision {}) to disk, but they "
					+ "are not online to resync", response.playerId(), response.payload().length,
					response.revision());
			return;
		}
		try {
			// reload re-reads the file we just wrote, so the server-side progress matches the store.
			player.getAdvancements().reload(server.getAdvancements());
			resyncClient(player);
			loadsApplied++;
			logger.info("OpenDonut: applied the stored advancements of {} ({} bytes, revision {})",
					player.getName().getString(), response.payload().length, response.revision());
		} catch (RuntimeException e) {
			logger.error("OpenDonut: could not apply the stored advancements of {}: {}",
					player.getName().getString(), e.toString(), e);
		}
	}

	/**
	 * The proxy reported the outcome of a save.
	 *
	 * @param result the result
	 */
	void onSaveResult(BridgeMessage.AdvancementsSaveResult result) {
		if (result.accepted()) {
			revisions.put(result.playerId(), result.revision());
			logger.debug("OpenDonut: the proxy stored the advancements of {} as revision {}",
					result.playerId(), result.revision());
		} else {
			savesRefused++;
			logger.warn("OpenDonut: the proxy refused the advancements of {} (revision {}): {}",
					result.playerId(), result.revision(), result.detail());
		}
	}

	/**
	 * Saves a player's advancements: flush the in-memory progress to the local file, read the file, and send
	 * it to the proxy with the current revision as the compare-and-set base.
	 *
	 * @param player the player
	 * @param why why the save happens, for the log
	 */
	private void saveNow(ServerPlayer player, String why) {
		try {
			// Make sure the file on disk is the current progress before it is read and sent.
			player.getAdvancements().save();
		} catch (RuntimeException e) {
			logger.warn("OpenDonut: could not flush the advancements of {} to disk first: {}",
					player.getName().getString(), e.toString());
		}
		Path file = advancementsFile(player.getUUID());
		byte[] payload;
		try {
			payload = Files.exists(file) ? Files.readAllBytes(file) : EMPTY_JSON;
		} catch (IOException | RuntimeException e) {
			logger.error("OpenDonut: could not read the advancements file of {} for {}: {}",
					player.getName().getString(), why, e.toString(), e);
			return;
		}
		if (payload.length > settings.maxPayloadBytes()) {
			logger.error("OpenDonut: the advancements of {} are {} bytes, over the configured limit of {}; "
					+ "they were NOT saved ({})", player.getName().getString(), payload.length,
					settings.maxPayloadBytes(), why);
			return;
		}
		long baseRevision = revisions.getOrDefault(player.getUUID(), 0L);
		if (bridge.send(player,
				BackendBridge.advancementsSaveRequest(player.getUUID(), serverId, baseRevision, payload))) {
			savesSent++;
			logger.debug("OpenDonut: sent the advancements of {} to the proxy ({} bytes, base revision {}, "
					+ "{})", player.getName().getString(), payload.length, baseRevision, why);
		}
	}

	/**
	 * Sends the player a full advancements packet (reset, every advancement, all progress), so the client's
	 * advancement screen matches the freshly reloaded server state immediately.
	 *
	 * <p>{@code PlayerAdvancements.flushDirty} cannot be used for this: it only sends the advancements marked
	 * dirty since the last packet, and {@code reload} does not mark anything, so after a mid-session reload it
	 * would send nothing.
	 *
	 * @param player the player
	 */
	private void resyncClient(ServerPlayer player) {
		Map<Identifier, AdvancementProgress> progress = new HashMap<>();
		for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
			AdvancementProgress entry = player.getAdvancements().getOrStartProgress(holder);
			if (entry != null && entry.hasProgress()) {
				progress.put(holder.id(), entry);
			}
		}
		List<ClientboundUpdateAdvancementsPacket.PositionedAdvancement> added = new ArrayList<>();
		for (AdvancementNode node : server.getAdvancements().tree().nodes()) {
			added.add(ClientboundUpdateAdvancementsPacket.PositionedAdvancement.fromNode(node));
		}
		player.connection.send(new ClientboundUpdateAdvancementsPacket(true, added, Set.of(), progress, true));
	}

	/**
	 * The local advancements file of a player.
	 *
	 * @param playerId the player
	 * @return {@code <world>/players/advancements/<uuid>.json} — the same path vanilla uses
	 */
	private Path advancementsFile(UUID playerId) {
		return server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(playerId + ".json");
	}

	/**
	 * Renders the synchroniser for {@code /opendonut}.
	 *
	 * @return a one-line description
	 */
	String describe() {
		return savesSent + " save(s) sent, " + savesRefused + " refused, " + loadsApplied
				+ " load(s) applied, " + revisions.size() + " tracked player(s)";
	}
}
