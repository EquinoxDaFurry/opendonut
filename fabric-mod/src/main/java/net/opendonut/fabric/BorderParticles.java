package net.opendonut.fabric;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;

import net.opendonut.common.model.BorderParticleSettings;
import net.opendonut.common.model.RegionBounds;
import net.opendonut.common.region.BorderParticleGeometry;
import net.opendonut.common.region.BorderParticleGeometry.BorderParticle;
import net.opendonut.common.region.BorderParticleGeometry.BorderSide;

/**
 * Draws the boundary of the region a player is standing in, as a wall of particles they can see coming.
 *
 * <h2>What it does, and what it deliberately does not</h2>
 * Once per configured interval, for each player, it asks {@link BorderParticleGeometry} which blocks of the
 * boundary are inside a sphere around them and spawns one particle in the centre of each. The geometry is
 * Minecraft-free and tested exactly; this class is the thin part that resolves a particle name, throttles the
 * work and talks to the level.
 *
 * <p>It is deliberately independent of the transfer machinery: it runs whether or not the bridge is up, and
 * whether or not this backend is currently allowed to transfer players. A player who cannot be moved should
 * still be able to see why, and a cosmetic feature has no business being gated behind network health.
 *
 * <h2>Cost</h2>
 * The wall is the intersection of a sphere with a plane, so a nine-block radius produces roughly 250 particles
 * per visible side, at most four sides, sent five times a second — comfortably under a thousand particles a
 * second per player, and nothing at all for a player in the middle of a region, since the geometry returns an
 * empty list when no boundary is in range. {@code border-particles.max-per-side} bounds the worst case, and
 * {@code interval-ticks} bounds the frequency.
 */
final class BorderParticles {

	/** The fallback used when the configured particle cannot be resolved. */
	private static final ParticleOptions FALLBACK = net.minecraft.core.particles.ParticleTypes.END_ROD;

	private final Logger logger;
	private final BorderParticleSettings settings;
	private final ParticleOptions particle;

	/**
	 * The tick at which each player's wall should next be drawn.
	 *
	 * <p>Per player rather than global, so players who join at different moments are not synchronised into
	 * drawing on the same tick. Entries are dropped when a player disconnects.
	 */
	private final Map<UUID, Long> nextDrawTick = new HashMap<>();

	private BorderParticles(Logger logger, BorderParticleSettings settings, ParticleOptions particle) {
		this.logger = logger;
		this.settings = settings;
		this.particle = particle;
	}

	/**
	 * Creates the service, resolving the configured particle once.
	 *
	 * <p>Resolution happens here rather than per particle so that a typo in the configuration is reported once at
	 * startup, with the fallback named, instead of silently producing nothing.
	 *
	 * @param settings the configuration
	 * @param logger the server logger
	 * @return the service, or an empty optional when the feature is disabled
	 */
	static Optional<BorderParticles> create(BorderParticleSettings settings, Logger logger) {
		if (!settings.enabled()) {
			logger.info("OpenDonut: border particles are disabled; players will not see their region's edge");
			return Optional.empty();
		}
		ParticleOptions resolved = resolve(settings.qualifiedParticleName()).orElseGet(() -> {
			logger.warn("OpenDonut: border-particles.particle '{}' is not a particle this server knows, or it "
							+ "needs parameters the configuration cannot express (such as a dust colour). Using "
							+ "{} instead.",
					settings.particle(), "end_rod");
			return FALLBACK;
		});
		logger.info("OpenDonut: border particles ready: {}, radius {}, every {} tick(s), up to {} per side",
				settings.particle(), settings.radius(), settings.intervalTicks(), settings.maxPerSide());
		return Optional.of(new BorderParticles(logger, settings, resolved));
	}

	/**
	 * Resolves a registry name to a particle that can be spawned without parameters.
	 *
	 * @param qualifiedName the name, already namespaced
	 * @return the particle, or empty when the name is unknown or the particle needs data
	 */
	private static Optional<ParticleOptions> resolve(String qualifiedName) {
		Identifier identifier = Identifier.tryParse(qualifiedName);
		if (identifier == null) {
			return Optional.empty();
		}
		ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.getValue(identifier);
		// Only the "simple" particles are also options: a ParameterizedParticleOption carries extra data
		// (a colour, an item, a block) that a bare registry name does not express.
		return type instanceof ParticleOptions options ? Optional.of(options) : Optional.empty();
	}

	/**
	 * Draws the wall for every player whose turn it is.
	 *
	 * @param players the online players
	 * @param ownBounds the region this backend owns, whose boundary is drawn
	 * @param tick the current server tick
	 */
	void tick(List<ServerPlayer> players, RegionBounds ownBounds, long tick) {
		for (ServerPlayer player : players) {
			try {
				tickPlayer(player, ownBounds, tick);
			} catch (RuntimeException e) {
				// Cosmetic: a failure here must never disturb the server, and one bad player must not stop the
				// others from seeing their boundary.
				logger.debug("OpenDonut: could not draw the border for {}: {}",
						player.getName().getString(), e.toString());
			}
		}
	}

	/**
	 * Draws the wall for one player, if their interval has elapsed.
	 *
	 * @param player the player
	 * @param ownBounds the region this backend owns
	 * @param tick the current server tick
	 */
	private void tickPlayer(ServerPlayer player, RegionBounds ownBounds, long tick) {
		UUID playerId = player.getUUID();
		Long due = nextDrawTick.get(playerId);
		if (due != null && tick < due) {
			return;
		}
		nextDrawTick.put(playerId, tick + settings.intervalTicks());

		ServerLevel level = player.level();
		List<BorderParticle> wall = BorderParticleGeometry.particlesFor(
				ownBounds,
				player.getX(), player.getY(), player.getZ(),
				settings.radius(),
				level.getMinY(), level.getMaxY(),
				settings.maxPerSide());

		for (BorderParticle particlePosition : wall) {
			// One particle, no spread, no velocity: the particle stands in the centre of its block rather than
			// filling a volume, which is what makes the wall look like a grid.
			level.sendParticles(particle, particlePosition.x(), particlePosition.y(), particlePosition.z(),
					1, 0.0d, 0.0d, 0.0d, 0.0d);
		}
	}

	/**
	 * Forgets a player's redraw schedule.
	 *
	 * @param playerId the player
	 */
	void forget(UUID playerId) {
		nextDrawTick.remove(playerId);
	}

	/**
	 * Describes what a player would see from where they are standing, for {@code /opendonut border}.
	 *
	 * <p>Existence proof for an administrator: the wall is the one feature that cannot be verified from a log
	 * line, and this turns it into a number that changes as the player walks towards the boundary.
	 *
	 * @param player the player to report on
	 * @param ownBounds the region this backend owns
	 * @return a multi-line report
	 */
	String describeFor(ServerPlayer player, RegionBounds ownBounds) {
		ServerLevel level = player.level();
		List<BorderParticle> wall = BorderParticleGeometry.particlesFor(
				ownBounds,
				player.getX(), player.getY(), player.getZ(),
				settings.radius(),
				level.getMinY(), level.getMaxY(),
				settings.maxPerSide());

		StringBuilder out = new StringBuilder();
		out.append("border particles: ").append(settings.describe()).append(System.lineSeparator());
		out.append("  standing at (").append((int) Math.floor(player.getX())).append(", ")
				.append((int) Math.floor(player.getY())).append(", ")
				.append((int) Math.floor(player.getZ())).append(')')
				.append(" — ").append(wall.size()).append(" particle(s) would be drawn now");

		for (BorderSide side : BorderSide.values()) {
			int planeBlock = BorderParticleGeometry.planeBlock(ownBounds, side);
			int count = 0;
			for (BorderParticle particlePosition : wall) {
				if (particlePosition.side() == side) {
					count++;
				}
			}
			boolean horizontal = side == BorderSide.MIN_X || side == BorderSide.MAX_X;
			double distance = horizontal
					? Math.abs((planeBlock + 0.5d) - player.getX())
					: Math.abs((planeBlock + 0.5d) - player.getZ());
			out.append(System.lineSeparator())
					.append("  ").append(side)
					.append(" at ").append(horizontal ? "x=" : "z=").append(planeBlock)
					.append(": ").append(count).append(" particle(s)")
					.append(String.format(", %.1f block(s) away", distance))
					.append(count == 0 ? "  (out of range of the sphere)" : "");
		}
		return out.toString();
	}
}
