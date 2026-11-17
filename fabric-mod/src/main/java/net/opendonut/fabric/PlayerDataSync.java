package net.opendonut.fabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;

import net.fabricmc.loader.api.FabricLoader;

import net.opendonut.common.bridge.BridgeMessage;
import net.opendonut.common.model.OpenDonutConfig;
import net.opendonut.common.model.PlayerDataSettings;
import net.opendonut.common.playerdata.PlayerDataSnapshot;
import net.opendonut.common.playerdata.SnapshotCodec;

/**
 * The backend's half of player-data synchronisation: capture the local player, hand it to the proxy, and
 * apply what the proxy sends back on arrival.
 *
 * <h2>When a save happens</h2>
 * <ul>
 *   <li>when the proxy demands one before a transfer ({@code PLAYER_DATA_FLUSH_REQUEST}) — the player is
 *       still on this server and still playing, so this captures their exact current state;</li>
 *   <li>on disconnect, so a player who leaves the network entirely still has their data in the store;</li>
 *   <li>periodically while they are online ({@code player-data.sync-interval-seconds}), which bounds what a
 *       crash can lose.</li>
 * </ul>
 *
 * <h2>When a load happens</h2>
 * Once, on join: the backend asks the proxy for the stored snapshot and applies it if there is one. This is
 * what makes the store authoritative rather than the destination's own {@code world/players/<uuid>.dat} —
 * a player who has ever been stored gets the stored copy on every backend they join, which is also what
 * prevents duplication: no backend's local file is ever the authority once the store has the player.
 *
 * <h2>Revision tracking</h2>
 * Every accepted save and every load response carries a revision; the backend remembers the latest one per
 * player and quotes it as the base of its next save. The proxy's compare-and-set refuses the save when the
 * base is stale, which is the mechanism that stops two backends from writing over each other.
 *
 * <p>Everything here runs on the Minecraft server thread: join/disconnect events, the tick, and the bridge's
 * message handler all do, so the plain map is safe.
 */
final class PlayerDataSync {

	private final MinecraftServer server;
	private final PlayerDataSettings settings;
	private final BackendBridge bridge;
	private final String serverId;
	private final Logger logger;
	private final String modFingerprint;
	private final int dataVersion;

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
	 * @return the synchroniser, or empty when player-data is off or the bridge is unavailable
	 */
	static Optional<PlayerDataSync> create(
			MinecraftServer server, OpenDonutConfig config, BackendBridge bridge, String serverId, Logger logger) {
		if (!config.playerData().enabled()) {
			return Optional.empty();
		}
		if (bridge == null) {
			logger.error("OpenDonut: player-data.enabled is true but the bridge is not up, so player data "
					+ "cannot be synchronised. Fix the bridge findings above, or set player-data.enabled: "
					+ "false.");
			return Optional.empty();
		}
		PlayerDataSync sync = new PlayerDataSync(server, config.playerData(), bridge, serverId, logger);
		logger.info("OpenDonut: player data sync ready on this backend — {}; saves on transfer, disconnect"
				+ " and {}, data version {}, mod fingerprint {}", sync.settings.describe(),
				sync.settings.syncIntervalSeconds() == 0 ? "never (interval 0)" : "every " + sync.settings.syncIntervalSeconds() + " s",
				sync.dataVersion, sync.modFingerprint);
		return Optional.of(sync);
	}

	private PlayerDataSync(
			MinecraftServer server, PlayerDataSettings settings, BackendBridge bridge, String serverId, Logger logger) {
		this.server = server;
		this.settings = settings;
		this.bridge = bridge;
		this.serverId = serverId;
		this.logger = logger;
		this.dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
		this.modFingerprint = computeModFingerprint();
	}

	/**
	 * A player joined: ask the proxy for their stored snapshot.
	 *
	 * @param player the player
	 */
	void onPlayerJoin(ServerPlayer player) {
		revisions.put(player.getUUID(), 0L);
		bridge.send(player, new BridgeMessage.PlayerDataLoadRequest(player.getUUID(), serverId));
	}

	/**
	 * A player disconnected: save their state one last time, then forget the revision.
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
			logger.warn("OpenDonut: a flush request for {} says it is from '{}' but this server is '{}'; ignoring "
					+ "it", request.playerId(), request.fromServerId(), serverId);
			return;
		}
		ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
		if (player == null) {
			logger.warn("OpenDonut: cannot flush {} — they are not on this server any more", request.playerId());
			return;
		}
		saveNow(player, "flush requested by the proxy before the move to '" + request.toServerId() + "'");
	}

	/**
	 * The proxy answered a load request.
	 *
	 * @param response the response
	 */
	void onLoadResponse(BridgeMessage.PlayerDataLoadResponse response) {
		revisions.put(response.playerId(), response.revision());
		if (!response.present()) {
			logger.info("OpenDonut: the proxy has no stored data for {} yet ({}); keeping the local player file",
					response.playerId(), response.detail());
			return;
		}
		SnapshotCodec.Result decoded = SnapshotCodec.decode(response.envelope());
		if (!decoded.isOk()) {
			logger.error("OpenDonut: the stored snapshot for {} did not decode ({}: {}); keeping the local "
					+ "player file", response.playerId(), decoded.error(), decoded.detail());
			return;
		}
		PlayerDataSnapshot snapshot = decoded.requireSnapshot();
		if (snapshot.isFromNewerVersionThan(dataVersion)) {
			logger.error("OpenDonut: refusing to apply the stored data of {} — it was written by a newer "
					+ "Minecraft (data version {}) than this server ({})", response.playerId(),
					snapshot.dataVersion(), dataVersion);
			return;
		}
		ServerPlayer player = server.getPlayerList().getPlayer(response.playerId());
		if (player == null) {
			logger.warn("OpenDonut: the stored data for {} arrived but they are not on this server any more",
					response.playerId());
			return;
		}
		try {
			PlayerDataCapture.apply(player, snapshot.nbt(), server.registryAccess());
			loadsApplied++;
			logger.info("OpenDonut: applied the stored data of {} ({}, {} bytes of NBT, mod fingerprint {})",
					player.getName().getString(), snapshot.describe(), snapshot.uncompressedBytes(),
					snapshot.modFingerprint());
		} catch (IOException | RuntimeException e) {
			logger.error("OpenDonut: could not apply the stored data of {}: {}",
					player.getName().getString(), e.toString(), e);
		}
	}

	/**
	 * The proxy reported the outcome of a save.
	 *
	 * @param result the result
	 */
	void onSaveResult(BridgeMessage.PlayerDataSaveResult result) {
		if (result.accepted()) {
			revisions.put(result.playerId(), result.revision());
			logger.debug("OpenDonut: the proxy stored the data of {} as revision {}", result.playerId(),
					result.revision());
		} else {
			savesRefused++;
			logger.warn("OpenDonut: the proxy refused the data of {} (revision {}): {}", result.playerId(),
					result.revision(), result.detail());
		}
	}

	/**
	 * Captures a player and sends it to the proxy as a save request.
	 *
	 * @param player the player
	 * @param why why the save happens, for the log
	 */
	private void saveNow(ServerPlayer player, String why) {
		try {
			CompoundTag tag = PlayerDataCapture.capture(player, server.registryAccess());
			byte[] nbt = PlayerDataCapture.encodeNbt(tag);
			PlayerDataSnapshot snapshot =
					PlayerDataSnapshot.of(dataVersion, modFingerprint, System.currentTimeMillis(), nbt);
			byte[] envelope = SnapshotCodec.encode(snapshot);
			if (envelope.length > settings.maxPayloadBytes()) {
				logger.error("OpenDonut: the snapshot of {} is {} bytes, over the configured limit of {}; it was "
						+ "NOT saved ({}). Raise player-data.max-payload-bytes only with chunking in mind — "
						+ "above {} bytes it will not fit one bridge frame.",
						player.getName().getString(), envelope.length, settings.maxPayloadBytes(), why,
						PlayerDataSettings.MAX_PAYLOAD_BYTES_LIMIT);
				return;
			}
			long baseRevision = revisions.getOrDefault(player.getUUID(), 0L);
			if (bridge.send(player, new BridgeMessage.PlayerDataSaveRequest(player.getUUID(), serverId,
					baseRevision, envelope))) {
				savesSent++;
				logger.debug("OpenDonut: sent the data of {} to the proxy ({} bytes, base revision {}, {})",
						player.getName().getString(), envelope.length, baseRevision, why);
			}
		} catch (IOException | RuntimeException e) {
			logger.error("OpenDonut: could not capture the data of {} for {}: {}",
					player.getName().getString(), why, e.toString(), e);
		}
	}

	/**
	 * Renders the synchroniser for {@code /opendonut playerdata}.
	 *
	 * @return a one-line description
	 */
	String describe() {
		return settings.describe() + "; " + savesSent + " save(s) sent, " + savesRefused + " refused, "
				+ loadsApplied + " load(s) applied, " + revisions.size() + " tracked player(s)";
	}

	/** @return this backend's mod fingerprint, computed once */
	String modFingerprint() {
		return modFingerprint;
	}

	/**
	 * Computes a short fingerprint of the installed mod set, so a snapshot's origin can be compared with the
	 * destination's without sending the mod list itself.
	 *
	 * @return the first 16 hex characters of the SHA-256 over {@code id:version} of every mod
	 */
	private static String computeModFingerprint() {
		StringBuilder mods = new StringBuilder();
		FabricLoader.getInstance().getAllMods().stream()
				.map(mod -> mod.getMetadata().getId() + ":" + mod.getMetadata().getVersion().getFriendlyString())
				.sorted()
				.forEach(joined -> mods.append(joined).append(';'));
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(mods.toString().getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(16);
			for (int i = 0; i < 8; i++) {
				hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16))
						.append(Character.forDigit(digest[i] & 0xF, 16));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			// Mandated by the Java platform.
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}
}
