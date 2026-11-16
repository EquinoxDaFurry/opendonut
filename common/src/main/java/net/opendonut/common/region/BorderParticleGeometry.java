package net.opendonut.common.region;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import net.opendonut.common.model.RegionBounds;

/**
 * Where to draw the border of a region so a player can see it coming.
 *
 * <h2>What it draws</h2>
 * A wall of particles on the boundary the player is walking towards: one particle in the centre of every block
 * that is both <em>on the boundary plane</em> and <em>inside a sphere of a given radius around the player</em>.
 * The wall is one block thick, because the boundary is a plane.
 *
 * <p>The consequence of that rule is the effect administrators are after: the sphere reaches the plane only
 * when the player is within {@code radius} blocks of it, and the disc where the two intersect grows as the
 * player gets closer. At the edge of the radius the player sees a few particles; standing next to the boundary
 * they see a full nine-block wall. Walking away, it shrinks and disappears — no wall is drawn when the sphere
 * does not touch the plane, so a player in the middle of their region sees nothing at all.
 *
 * <h2>Which block is "the border"</h2>
 * The first block the player may <em>not</em> enter, on each of the region's four sides:
 *
 * <pre>
 *   west region  X = [-100000, 0)
 *
 *   the eastern boundary    is between block -1 (allowed) and block 0 (forbidden)   → the wall is at x = 0
 *   the western boundary    is between block -100001 (forbidden) and -100000 (allowed) → the wall is at x = -100001
 * </pre>
 *
 * <p>Both cases are "one block past the boundary, on the side the player is turned away from", so the wall
 * always appears one step ahead of an approaching player — which is what makes it useful and is why the rule is
 * stated rather than left implicit. Note the consequence at the edge of the world: where a region's minimum
 * edge sits on the world border, the wall is drawn one block outside it, marking the limit rather than the
 * crossable line.
 *
 * <h2>Why the geometry lives here</h2>
 * This class contains no Minecraft types, so the arithmetic can be tested exactly — and it is the part that is
 * easy to get subtly wrong (an off-by-one at a boundary, a disc that does not grow, a sphere that reaches a
 * plane it should not). {@code BorderParticleGeometryTest} checks all of those. The Fabric side only has to
 * spawn a particle at each returned position.
 *
 * <p>All positions returned are block centres, which is where a particle looks like it belongs: a block
 * occupies {@code [x, x+1)}, so its centre is {@code x + 0.5}.
 */
public final class BorderParticleGeometry {

	private BorderParticleGeometry() {
	}

	/**
	 * Which side of a region a particle belongs to.
	 *
	 * <p>Reported so callers can log, colour or prioritise per side; the geometry itself treats all four the
	 * same way.
	 */
	public enum BorderSide {

		/** The plane at {@code minX - 1}, the first forbidden block to the west. */
		MIN_X,

		/** The plane at {@code maxX}, the first forbidden block to the east. */
		MAX_X,

		/** The plane at {@code minZ - 1}, the first forbidden block to the north. */
		MIN_Z,

		/** The plane at {@code maxZ}, the first forbidden block to the south. */
		MAX_Z
	}

	/**
	 * One particle to spawn: the centre of a block on a boundary plane.
	 *
	 * @param x the particle X, always {@code blockX + 0.5}
	 * @param y the particle Y, always {@code blockY + 0.5}
	 * @param z the particle Z, always {@code blockZ + 0.5}
	 * @param side which boundary this particle marks
	 * @param distanceSquared the squared distance from the player to this particle, used for ordering and for
	 *                        trimming when a caller sets a budget
	 */
	public record BorderParticle(double x, double y, double z, BorderSide side, double distanceSquared) {

		/**
		 * The block this particle is centred in.
		 *
		 * @return the block coordinate as a string, for logging
		 */
		public String blockPosition() {
			return (int) Math.floor(x) + "," + (int) Math.floor(y) + "," + (int) Math.floor(z);
		}
	}

	/**
	 * The block coordinate of a boundary plane: the first block on that side that the region does not own.
	 *
	 * @param region the region
	 * @param side the side
	 * @return the block coordinate of the plane
	 */
	public static int planeBlock(RegionBounds region, BorderSide side) {
		Objects.requireNonNull(region, "region");
		Objects.requireNonNull(side, "side");
		return switch (side) {
			case MIN_X -> region.minX() - 1;
			case MAX_X -> region.maxX();
			case MIN_Z -> region.minZ() - 1;
			case MAX_Z -> region.maxZ();
		};
	}

	/**
	 * Computes every particle that should be visible to a player standing somewhere in a region.
	 *
	 * @param region the region to draw the borders of
	 * @param playerX the player's X
	 * @param playerY the player's Y (their feet, as Minecraft reports it)
	 * @param playerZ the player's Z
	 * @param radius the radius of the sphere around the player, in blocks
	 * @param minY the lowest block of the level to draw in, so nothing is drawn under the world
	 * @param maxY the highest block of the level to draw in, so nothing is drawn above the build limit
	 * @param maxPerSide the most particles to return for any one side, nearest first; a budget rather than a
	 *                   limit on correctness, because a large radius on a long boundary can produce thousands
	 * @return the particles to spawn, in a deterministic order (side, then distance, then position); empty when
	 *         no boundary is within the radius, which is the common case in the middle of a region
	 */
	public static List<BorderParticle> particlesFor(
			RegionBounds region,
			double playerX,
			double playerY,
			double playerZ,
			int radius,
			int minY,
			int maxY,
			int maxPerSide) {

		Objects.requireNonNull(region, "region");
		if (radius < 1 || maxPerSide < 1 || maxY < minY || region.isEmpty()) {
			return List.of();
		}

		double radiusSquared = (double) radius * (double) radius;
		List<BorderParticle> particles = new ArrayList<>();

		for (BorderSide side : BorderSide.values()) {
			int planeBlock = planeBlock(region, side);
			boolean fixedInX = side == BorderSide.MIN_X || side == BorderSide.MAX_X;

			// The distance from the player to this plane, measured from centre to centre. When it exceeds the
			// radius the sphere cannot touch the plane at all, which is what makes the wall appear and grow.
			double fixedOffset = fixedInX
					? (planeBlock + 0.5d) - playerX
					: (planeBlock + 0.5d) - playerZ;
			if (Math.abs(fixedOffset) > radius) {
				continue;
			}

			List<BorderParticle> sideParticles = new ArrayList<>();
			int lowestY = Math.max(minY, (int) Math.floor(playerY - radius));
			int highestY = Math.min(maxY, (int) Math.floor(playerY + radius));

			for (int y = lowestY; y <= highestY; y++) {
				double dy = (y + 0.5d) - playerY;
				double dySquared = dy * dy;
				if (dySquared > radiusSquared) {
					continue;
				}
				// The remaining budget along the free horizontal axis, from the squared-distance relation.
				double remaining = radiusSquared - dySquared - fixedOffset * fixedOffset;
				if (remaining < 0) {
					continue;
				}
				double span = Math.sqrt(remaining);

				if (fixedInX) {
					double centreZ = playerZ;
					int fromZ = (int) Math.floor(centreZ + 0.5d - span - 1.0d);
					int toZ = (int) Math.ceil(centreZ + 0.5d + span + 1.0d);
					for (int z = fromZ; z <= toZ; z++) {
						double dz = (z + 0.5d) - playerZ;
						double distanceSquared = fixedOffset * fixedOffset + dySquared + dz * dz;
						if (distanceSquared <= radiusSquared) {
							sideParticles.add(new BorderParticle(planeBlock + 0.5d, y + 0.5d, z + 0.5d,
									side, distanceSquared));
						}
					}
				} else {
					int fromX = (int) Math.floor(playerX + 0.5d - span - 1.0d);
					int toX = (int) Math.ceil(playerX + 0.5d + span + 1.0d);
					for (int x = fromX; x <= toX; x++) {
						double dx = (x + 0.5d) - playerX;
						double distanceSquared = dx * dx + dySquared + fixedOffset * fixedOffset;
						if (distanceSquared <= radiusSquared) {
							sideParticles.add(new BorderParticle(x + 0.5d, y + 0.5d, planeBlock + 0.5d,
									side, distanceSquared));
						}
					}
				}
			}

			if (sideParticles.size() > maxPerSide) {
				sideParticles.sort(Comparator.comparingDouble(BorderParticle::distanceSquared));
				sideParticles = new ArrayList<>(sideParticles.subList(0, maxPerSide));
			}
			particles.addAll(sideParticles);
		}

		particles.sort(Comparator
				.comparing(BorderParticle::side)
				.thenComparingDouble(BorderParticle::distanceSquared)
				.thenComparingDouble(BorderParticle::x)
				.thenComparingDouble(BorderParticle::y)
				.thenComparingDouble(BorderParticle::z));
		return List.copyOf(particles);
	}

	/**
	 * How large the wall on one side is, without building the list.
	 *
	 * <p>Used by the diagnostics command and by the tests that assert the wall grows as a player approaches;
	 * kept next to {@link #particlesFor} so the two cannot drift.
	 *
	 * @param region the region
	 * @param side the side
	 * @param playerX the player's X
	 * @param playerY the player's Y
	 * @param playerZ the player's Z
	 * @param radius the sphere radius
	 * @param minY lowest block of the level
	 * @param maxY highest block of the level
	 * @return the number of blocks on that plane inside the sphere
	 */
	public static int countFor(
			RegionBounds region,
			BorderSide side,
			double playerX,
			double playerY,
			double playerZ,
			int radius,
			int minY,
			int maxY) {
		int count = 0;
		for (BorderParticle particle : particlesFor(region, playerX, playerY, playerZ, radius, minY, maxY,
				Integer.MAX_VALUE)) {
			if (particle.side() == side) {
				count++;
			}
		}
		return count;
	}
}
