package net.opendonut.fabric;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;

import net.opendonut.common.model.ContainmentSettings;
import net.opendonut.common.model.OpenDonutConfig;
import net.opendonut.common.model.RegionBounds;
import net.opendonut.common.model.ServerDefinition;

/**
 * Hard containment: nothing outside this backend's own rectangle becomes real.
 *
 * <h2>What is enforced</h2>
 * <ul>
 *   <li><strong>Mobs and other entities cannot spawn or load outside the region</strong> —
 *       {@code ServerEntityEvents.ALLOW_LOAD} refuses them, and a periodic sweep plus
 *       {@code ENTITY_LOAD} rubber-bands dropped items back in and discards anything else that slipped
 *       through;</li>
 *   <li><strong>blocks cannot be broken, attacked, used or placed outside the region</strong> — the Fabric
 *       interaction callbacks fail the interaction when the affected block (or, for block items, the block
 *       that would be placed) is outside;</li>
 *   <li><strong>a player can never be more than {@code max-overshoot-blocks} past the border</strong>, even
 *       under lag — every tick a player further out is teleported back to the clamp line and their motion is
 *       zeroed, so a lost connection cannot strand them deep in another server's territory;</li>
 *   <li><strong>players near the border get a shrunk chunk-cache radius</strong>, so their client does not
 *       keep the world outside the region loaded.</li>
 * </ul>
 *
 * <p>Containment is a safety invariant of the region model — this server must never write chunks another
 * server also claims — so it is on by default ({@code containment.enabled}) and runs even when transfers
 * are disabled.
 *
 * <p>The pure geometry helpers ({@link #isOutside}, {@link #clampBlockX}, {@link #clampBlockZ},
 * {@link #borderDistance}) are static and free of Minecraft types beyond {@link RegionBounds} so the tests
 * can pin the exact clamp semantics.
 *
 * <p>Everything here runs on the Minecraft server thread: the tick and every Fabric event used do, so the
 * plain maps are safe.
 */
final class RegionContainment {

	/** How often a denied interaction is logged, so a player standing at the border cannot flood the log. */
	private static final long DENY_LOG_INTERVAL_MILLIS = 5_000L;

	private final MinecraftServer server;
	private final ContainmentSettings settings;
	private final RegionBounds bounds;
	private final Logger logger;

	/** The chunk-cache radius last sent to each player, so the packet is only sent when it changes. */
	private final Map<UUID, Integer> sentChunkRadius = new HashMap<>();

	private long lastDenyLogMillis;
	private long playersClamped;
	private long entitiesContained;
	private long loadsBlocked;

	/**
	 * Creates the containment guard when the configuration enables it.
	 *
	 * @param server the running server
	 * @param config the configuration
	 * @param self this backend's own server definition
	 * @param logger the server logger
	 * @return the guard, or empty when {@code containment.enabled} is false
	 */
	static Optional<RegionContainment> create(
			MinecraftServer server, OpenDonutConfig config, ServerDefinition self, Logger logger) {
		if (!config.containment().enabled()) {
			return Optional.empty();
		}
		RegionContainment containment =
				new RegionContainment(server, config.containment(), self.bounds(), logger);
		logger.info("OpenDonut: region containment active for {} — {}", self.id(), config.containment().describe());
		return Optional.of(containment);
	}

	private RegionContainment(
			MinecraftServer server, ContainmentSettings settings, RegionBounds bounds, Logger logger) {
		this.server = server;
		this.settings = settings;
		this.bounds = bounds;
		this.logger = logger;
	}

	// ------------------------------------------------------------------
	// Pure geometry — the part the tests pin down
	// ------------------------------------------------------------------

	/**
	 * Whether a block coordinate lies outside the region.
	 *
	 * @param bounds the region
	 * @param blockX the block X
	 * @param blockZ the block Z
	 * @return {@code true} when the coordinate is outside {@code [minX, maxX) x [minZ, maxZ)}
	 */
	static boolean isOutside(RegionBounds bounds, int blockX, int blockZ) {
		return !bounds.contains(blockX, blockZ);
	}

	/**
	 * Clamps a block X into the region, allowing at most {@code maxOvershoot} blocks past the east/west
	 * borders.
	 *
	 * @param bounds the region
	 * @param blockX the block X to clamp
	 * @param maxOvershoot how far past the border the result may be
	 * @return the clamped block X, within {@code [minX - maxOvershoot, maxX - 1 + maxOvershoot]}
	 */
	static int clampBlockX(RegionBounds bounds, int blockX, int maxOvershoot) {
		return Math.min(Math.max(blockX, bounds.minX() - maxOvershoot), bounds.maxX() - 1 + maxOvershoot);
	}

	/**
	 * Clamps a block Z into the region, allowing at most {@code maxOvershoot} blocks past the north/south
	 * borders.
	 *
	 * @param bounds the region
	 * @param blockZ the block Z to clamp
	 * @param maxOvershoot how far past the border the result may be
	 * @return the clamped block Z, within {@code [minZ - maxOvershoot, maxZ - 1 + maxOvershoot]}
	 */
	static int clampBlockZ(RegionBounds bounds, int blockZ, int maxOvershoot) {
		return Math.min(Math.max(blockZ, bounds.minZ() - maxOvershoot), bounds.maxZ() - 1 + maxOvershoot);
	}

	/**
	 * How many blocks a coordinate inside the region is from crossing a border, Chebyshev style.
	 *
	 * @param bounds the region
	 * @param blockX the block X
	 * @param blockZ the block Z
	 * @return the distance to the nearest border, or {@code 0} when the coordinate is outside
	 */
	static int borderDistance(RegionBounds bounds, int blockX, int blockZ) {
		if (isOutside(bounds, blockX, blockZ)) {
			return 0;
		}
		return Math.min(
				Math.min(blockX - bounds.minX() + 1, bounds.maxX() - blockX),
				Math.min(blockZ - bounds.minZ() + 1, bounds.maxZ() - blockZ));
	}

	// ------------------------------------------------------------------
	// Per-tick work: player clamp + chunk radius, periodic entity sweep
	// ------------------------------------------------------------------

	/**
	 * Runs one containment pass: clamps every player, shrinks the chunk radius of players near the border,
	 * and sweeps the shell outside the region for entities on the configured interval.
	 *
	 * @param server the running server
	 */
	void tick(MinecraftServer server) {
		int maxOvershoot = settings.maxOvershootBlocks();
		int defaultRadius = server.getPlayerList().getViewDistance();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			try {
				tickPlayer(player, maxOvershoot, defaultRadius);
			} catch (RuntimeException e) {
				// One player's problem must never stop the containment of everybody else.
				logger.error("OpenDonut: containment failed for {}: {}",
						player.getName().getString(), e.toString(), e);
			}
		}
		int interval = settings.entitySweepIntervalTicks();
		if (interval > 0 && server.getTickCount() % interval == 0) {
			sweep(server);
		}
	}

	private void tickPlayer(ServerPlayer player, int maxOvershoot, int defaultRadius) {
		BlockPos pos = player.blockPosition();
		int blockX = pos.getX();
		int blockZ = pos.getZ();

		// Hard clamp: no matter how the player got there (lag, teleport, exploit), they are back inside the
		// overshoot limit within one tick.
		int outside = OpenDonutBackend.distanceOutside(bounds, blockX, blockZ);
		if (outside > maxOvershoot) {
			int clampedX = clampBlockX(bounds, blockX, maxOvershoot);
			int clampedZ = clampBlockZ(bounds, blockZ, maxOvershoot);
			player.connection.teleport(
					clampedX + 0.5d, player.getY(), clampedZ + 0.5d, player.getYRot(), player.getXRot());
			player.setDeltaMovement(Vec3.ZERO);
			playersClamped++;
			long now = System.currentTimeMillis();
			if (now - lastDenyLogMillis >= DENY_LOG_INTERVAL_MILLIS) {
				lastDenyLogMillis = now;
				logger.warn("OpenDonut: containment clamped {} back from {} block(s) outside {} (limit {})",
						player.getName().getString(), outside, bounds.describe(), maxOvershoot);
			}
		}

		// Chunk radius: near the border the client keeps only a small radius, so it does not load and keep
		// the world outside the region. Far from the border (or inside) the server default applies.
		int desiredRadius = borderDistance(bounds, blockX, blockZ) <= settings.borderProximityBlocks()
				? settings.nearBorderChunkRadius()
				: defaultRadius;
		UUID playerId = player.getUUID();
		Integer lastSent = sentChunkRadius.get(playerId);
		if (lastSent == null || lastSent != desiredRadius) {
			player.connection.send(new ClientboundSetChunkCacheRadiusPacket(desiredRadius));
			sentChunkRadius.put(playerId, desiredRadius);
		}
	}

	/**
	 * Sweeps the shell just outside the region in every level for non-player entities: dropped items are
	 * rubber-banded back inside, everything else is discarded.
	 *
	 * @param server the running server
	 */
	private void sweep(MinecraftServer server) {
		int shell = settings.entitySweepShellBlocks();
		for (ServerLevel level : server.getAllLevels()) {
			AABB box = new AABB(
					bounds.minX() - shell, level.getMinY(), bounds.minZ() - shell,
					bounds.maxX() + shell, level.getMaxY(), bounds.maxZ() + shell);
			List<Entity> found = level.getEntities((Entity) null, box, entity ->
					!(entity instanceof Player)
							&& !entity.isRemoved()
							&& isOutside(bounds,
									entity.blockPosition().getX(), entity.blockPosition().getZ()));
			for (Entity entity : found) {
				contain(entity);
			}
		}
	}

	/**
	 * Deals with one non-player entity that is outside the region: items are moved back inside, everything
	 * else (mobs, projectiles, vehicles, ...) is discarded along with its passengers.
	 *
	 * @param entity the entity
	 */
	private void contain(Entity entity) {
		if (entity instanceof ItemEntity) {
			BlockPos pos = entity.blockPosition();
			int clampedX = clampBlockX(bounds, pos.getX(), 0);
			int clampedZ = clampBlockZ(bounds, pos.getZ(), 0);
			entity.setPos(clampedX + 0.5d, entity.getY(), clampedZ + 0.5d);
			entity.setDeltaMovement(Vec3.ZERO);
		} else {
			for (Entity passenger : entity.getPassengers()) {
				passenger.discard();
			}
			entity.discard();
		}
		entitiesContained++;
	}

	// ------------------------------------------------------------------
	// Interaction and spawn events
	// ------------------------------------------------------------------

	/**
	 * Whether a player may break the block at the given position.
	 *
	 * @param level the level
	 * @param player the player
	 * @param pos the block position
	 * @return {@code true} to allow the break, {@code false} to cancel it
	 */
	boolean allowBlockBreak(Level level, Player player, BlockPos pos) {
		if (level.isClientSide() || bounds.contains(pos.getX(), pos.getZ())) {
			return true;
		}
		deny(player, "break a block at " + pos.toShortString() + " outside " + bounds.describe());
		return false;
	}

	/**
	 * Handles a right-click on a block: using a block outside the region fails, and so does placing a block
	 * item whose placement would land outside (clicking an inside block that faces the border).
	 *
	 * @param player the player
	 * @param level the level
	 * @param hand the hand used
	 * @param hit the block hit
	 * @return the interaction result — {@code FAIL} to cancel, {@code PASS} to let vanilla continue
	 */
	InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (level.isClientSide()) {
			return InteractionResult.PASS;
		}
		BlockPos clicked = hit.getBlockPos();
		if (isOutside(bounds, clicked.getX(), clicked.getZ())) {
			deny(player, "use a block at " + clicked.toShortString() + " outside " + bounds.describe());
			return InteractionResult.FAIL;
		}
		ItemStack stack = player.getItemInHand(hand);
		if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
			BlockPos placement = clicked.relative(hit.getDirection());
			if (isOutside(bounds, placement.getX(), placement.getZ())) {
				deny(player, "place a block at " + placement.toShortString() + " outside " + bounds.describe());
				return InteractionResult.FAIL;
			}
		}
		return InteractionResult.PASS;
	}

	/**
	 * Handles a left-click on a block: attacking a block outside the region fails.
	 *
	 * @param player the player
	 * @param level the level
	 * @param hand the hand used
	 * @param pos the block position
	 * @param direction the clicked face
	 * @return the interaction result — {@code FAIL} to cancel, {@code PASS} to let vanilla continue
	 */
	InteractionResult onAttackBlock(
			Player player, Level level, InteractionHand hand, BlockPos pos, Direction direction) {
		if (level.isClientSide()) {
			return InteractionResult.PASS;
		}
		if (isOutside(bounds, pos.getX(), pos.getZ())) {
			deny(player, "attack a block at " + pos.toShortString() + " outside " + bounds.describe());
			return InteractionResult.FAIL;
		}
		return InteractionResult.PASS;
	}

	/**
	 * Whether an entity may load into the world at its current position. Players always may; every other
	 * entity may not when it is outside the region — this is what stops mobs spawning past the border.
	 *
	 * @param entity the entity
	 * @param level the level it is loading into
	 * @return {@code true} to allow the load
	 */
	boolean allowEntityLoad(Entity entity, ServerLevel level) {
		if (entity instanceof Player) {
			return true;
		}
		if (isOutside(bounds, entity.blockPosition().getX(), entity.blockPosition().getZ())) {
			loadsBlocked++;
			return false;
		}
		return true;
	}

	/**
	 * An entity finished loading: if it is a non-player outside the region (something that slipped past
	 * {@link #allowEntityLoad}, for example a command-spawned mob), contain it now.
	 *
	 * @param entity the entity
	 * @param level the level
	 */
	void onEntityLoad(Entity entity, ServerLevel level) {
		if (entity instanceof Player) {
			return;
		}
		if (isOutside(bounds, entity.blockPosition().getX(), entity.blockPosition().getZ())) {
			contain(entity);
		}
	}

	/**
	 * A player joined: forget any stale chunk-radius state so the first tick sends the right one.
	 *
	 * @param player the player
	 */
	void onPlayerJoin(ServerPlayer player) {
		sentChunkRadius.remove(player.getUUID());
	}

	/**
	 * A player disconnected: drop their chunk-radius state.
	 *
	 * @param player the player
	 */
	void onPlayerDisconnect(ServerPlayer player) {
		sentChunkRadius.remove(player.getUUID());
	}

	/**
	 * Logs a denied interaction, at most once per {@link #DENY_LOG_INTERVAL_MILLIS}.
	 *
	 * @param player the player
	 * @param what what was denied
	 */
	private void deny(Player player, String what) {
		long now = System.currentTimeMillis();
		if (now - lastDenyLogMillis >= DENY_LOG_INTERVAL_MILLIS) {
			lastDenyLogMillis = now;
			logger.warn("OpenDonut: containment denied {} for {}: {}", what,
					player.getName().getString(), bounds.describe());
		}
	}

	/**
	 * Renders the guard for {@code /opendonut}.
	 *
	 * @return a one-line description
	 */
	String describe() {
		return settings.describe() + "; " + playersClamped + " clamp(s), " + entitiesContained
				+ " entit(y/ies) contained, " + loadsBlocked + " load(s) blocked";
	}
}
