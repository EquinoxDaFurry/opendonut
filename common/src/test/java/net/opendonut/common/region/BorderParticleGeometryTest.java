package net.opendonut.common.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.opendonut.common.model.RegionBounds;
import net.opendonut.common.region.BorderParticleGeometry.BorderParticle;
import net.opendonut.common.region.BorderParticleGeometry.BorderSide;

/**
 * The border-particle geometry: which blocks get a particle, and how the wall behaves as a player approaches.
 *
 * <p>Every assertion here is about arithmetic that a player would notice immediately if it were wrong — a wall
 * one block off, a wall that does not grow, a wall visible in the middle of the region — so the cases are
 * written as sentences about what a player sees, not as formulas.
 */
class BorderParticleGeometryTest {

	/** The region used by the shipped two-server example: X = [-100000, 0), Z = [-100000, 100000). */
	private static final RegionBounds WEST = new RegionBounds(-100000, 0, -100000, 100000);

	/** A convenient level height range for tests. */
	private static final int MIN_Y = -64;

	private static final int MAX_Y = 320;

	private static List<BorderParticle> particles(double x, double y, double z, int radius) {
		return BorderParticleGeometry.particlesFor(WEST, x, y, z, radius, MIN_Y, MAX_Y, 4096);
	}

	private static List<BorderParticle> onSide(double x, double y, double z, int radius, BorderSide side) {
		return particles(x, y, z, radius).stream().filter(particle -> particle.side() == side).toList();
	}

	@Nested
	@DisplayName("which plane a side draws on")
	class Planes {

		@Test
		@DisplayName("the wall stands on the first block the region does not own")
		void planesAreTheFirstForbiddenBlocks() {
			// The example from the documentation: west owns up to block -1, block 0 belongs to east, so the
			// wall a west player walks towards is at x = 0.
			assertEquals(0, BorderParticleGeometry.planeBlock(WEST, BorderSide.MAX_X));
			assertEquals(-100001, BorderParticleGeometry.planeBlock(WEST, BorderSide.MIN_X));
			assertEquals(100000, BorderParticleGeometry.planeBlock(WEST, BorderSide.MAX_Z));
			assertEquals(-100001, BorderParticleGeometry.planeBlock(WEST, BorderSide.MIN_Z));
		}

		@Test
		@DisplayName("a player one block from the eastern boundary sees the wall at x = 0")
		void wallIsWhereThePlayerExpectsIt() {
			// Standing at x = -1, looking east: every particle is in the block at x = 0, whatever the height.
			List<BorderParticle> wall = onSide(-1.5d, 68.0d, 0.5d, 9, BorderSide.MAX_X);
			assertFalse(wall.isEmpty(), "the wall must be visible from one block away");
			for (BorderParticle particle : wall) {
				assertEquals(0.5d, particle.x(), "the wall is the plane of block x = 0");
			}
		}
	}

	@Nested
	@DisplayName("the wall grows as the player approaches")
	class Growth {

		@Test
		@DisplayName("nothing is drawn until the sphere reaches the boundary")
		void noWallInTheMiddleOfTheRegion() {
			// Nine blocks of radius means a player further than nine blocks from every edge sees nothing at all.
			assertTrue(particles(-50.5d, 68.0d, 0.5d, 9).isEmpty(),
					"a player in the middle of a region must not see a wall");
			assertTrue(particles(-10.5d, 68.0d, 0.5d, 9).isEmpty(),
					"ten blocks away is still out of range of a nine block sphere");
		}

		@Test
		@DisplayName("the wall appears at the edge of the radius and gets bigger from there")
		void wallGrowsMonotonically() {
			// Walk from nine blocks away to standing on the boundary, and count the particles at each step.
			int previous = -1;
			List<Integer> counts = new java.util.ArrayList<>();
			for (double x = -9.5d; x <= 0.5d; x += 1.0d) {
				int count = onSide(x, 68.0d, 0.5d, 9, BorderSide.MAX_X).size();
				counts.add(count);
				assertTrue(count >= previous,
						"the wall must never shrink as the player approaches: " + counts);
				previous = count;
			}
			assertEquals(0, counts.get(0), "at nine blocks away the sphere only just stops reaching");
			assertTrue(counts.get(counts.size() - 1) > counts.get(1),
					"standing on the boundary must show a much bigger wall than the first appearance: " + counts);
		}

		@Test
		@DisplayName("standing on the boundary shows a full disc, not a full sphere")
		void wallIsADiscOnThePlane() {
			// With the player centred half a block from the plane, the visible set is one block thick.
			List<BorderParticle> wall = onSide(0.5d, 68.0d, 0.5d, 9, BorderSide.MAX_X);
			assertFalse(wall.isEmpty());
			for (BorderParticle particle : wall) {
				assertEquals(0.5d, particle.x());
				assertTrue(Math.abs(particle.y() - 68.5d) <= 9.5d, "the wall is clipped by the sphere vertically");
				assertTrue(Math.abs(particle.z() - 0.5d) <= 9.5d, "and horizontally");
			}
			// A 9-block disc is a bit over 250 blocks: large enough to read as a wall, small enough to be cheap.
			assertTrue(wall.size() > 200 && wall.size() < 300,
					"a nine block radius should give a disc of roughly 250 blocks, was " + wall.size());
		}
	}

	@Nested
	@DisplayName("corners and edges")
	class Corners {

		@Test
		@DisplayName("a player in a corner sees two perpendicular walls")
		void cornerShowsTwoWalls() {
			// The north-east corner of west: both x = 0 (the eastern boundary) and z = 100000 (the southern one)
			// are within reach. Walls must meet at the corner rather than one hiding the other.
			Set<BorderSide> sides = EnumSet.noneOf(BorderSide.class);
			for (BorderParticle particle : particles(-1.5d, 68.0d, 99999.5d, 9)) {
				sides.add(particle.side());
			}
			assertTrue(sides.contains(BorderSide.MAX_X), "the eastern wall: " + sides);
			assertTrue(sides.contains(BorderSide.MAX_Z), "the southern wall: " + sides);
			assertFalse(sides.contains(BorderSide.MIN_X), "the far side must not be drawn: " + sides);
			assertFalse(sides.contains(BorderSide.MIN_Z), "the far side must not be drawn: " + sides);
		}

		@Test
		@DisplayName("a player exactly on the boundary still sees the wall")
		void standingOnTheBoundary() {
			// This is the moment before the transfer fires, and the wall must not vanish.
			assertFalse(onSide(0.5d, 64.0d, 0.5d, 9, BorderSide.MAX_X).isEmpty());
		}

		@Test
		@DisplayName("walls are drawn per side, so a long boundary cannot starve a short one")
		void budgetIsPerSide() {
			// A budget of one particle per side must still produce one particle per visible side, not one in
			// total: the caller's cap is about traffic, not about which side wins.
			List<BorderParticle> capped = BorderParticleGeometry.particlesFor(
					WEST, -1.5d, 68.0d, 99999.5d, 9, MIN_Y, MAX_Y, 1);
			assertEquals(2, capped.size(), "one particle for each of the two visible sides: " + capped);
			assertTrue(capped.stream().anyMatch(particle -> particle.side() == BorderSide.MAX_X));
			assertTrue(capped.stream().anyMatch(particle -> particle.side() == BorderSide.MAX_Z));
		}
	}

	@Nested
	@DisplayName("level bounds")
	class LevelBounds {

		@Test
		@DisplayName("nothing is drawn outside the level's build height")
		void respectsBuildHeight() {
			// A player at the bottom of the world, with a small level range: no particle may be below minY.
			List<BorderParticle> wall = BorderParticleGeometry.particlesFor(
					WEST, -1.5d, -63.0d, 0.5d, 9, -64, 320, 4096);
			assertFalse(wall.isEmpty());
			for (BorderParticle particle : wall) {
				assertTrue(particle.y() >= -64 + 0.5d, "no particle under the world: " + particle);
			}

			// And with a deliberately tiny range, the wall is clipped to it.
			List<BorderParticle> clipped = BorderParticleGeometry.particlesFor(
					WEST, -1.5d, 68.0d, 0.5d, 9, 60, 70, 4096);
			assertFalse(clipped.isEmpty());
			for (BorderParticle particle : clipped) {
				assertTrue(particle.y() >= 60.5d && particle.y() <= 70.5d, "inside the level: " + particle);
			}
		}

		@Test
		@DisplayName("a radius, budget or level range that cannot produce anything yields nothing")
		void degenerateInputs() {
			assertTrue(BorderParticleGeometry.particlesFor(WEST, -1.5d, 68.0d, 0.5d, 0, MIN_Y, MAX_Y, 8).isEmpty());
			assertTrue(BorderParticleGeometry.particlesFor(WEST, -1.5d, 68.0d, 0.5d, 9, MIN_Y, MAX_Y, 0).isEmpty());
			assertTrue(BorderParticleGeometry.particlesFor(WEST, -1.5d, 68.0d, 0.5d, 9, 70, 60, 8).isEmpty());
			RegionBounds empty = new RegionBounds(0, 0, 0, 10);
			assertTrue(BorderParticleGeometry.particlesFor(empty, 0.5d, 68.0d, 0.5d, 9, MIN_Y, MAX_Y, 8).isEmpty());
		}
	}

	@Nested
	@DisplayName("the positions themselves")
	class Positions {

		@Test
		@DisplayName("every particle sits in the centre of its block")
		void particlesAreBlockCentres() {
			for (BorderParticle particle : particles(-1.5d, 68.0d, 0.5d, 9)) {
				assertEquals(0.5d, particle.x() - Math.floor(particle.x()), 1.0e-9, "x centre");
				assertEquals(0.5d, particle.y() - Math.floor(particle.y()), 1.0e-9, "y centre");
				assertEquals(0.5d, particle.z() - Math.floor(particle.z()), 1.0e-9, "z centre");
			}
		}

		@Test
		@DisplayName("the ordering is deterministic, so two redraws look identical")
		void orderingIsStable() {
			List<BorderParticle> first = particles(-1.5d, 68.0d, 0.5d, 9);
			List<BorderParticle> second = particles(-1.5d, 68.0d, 0.5d, 9);
			assertEquals(first, second, "the same position must produce the same wall, in the same order");
		}

		@Test
		@DisplayName("the reported distance is the real distance to the player")
		void distancesAreCorrect() {
			double px = -1.5d;
			double py = 68.0d;
			double pz = 0.5d;
			for (BorderParticle particle : particles(px, py, pz, 9)) {
				double dx = particle.x() - px;
				double dy = particle.y() - py;
				double dz = particle.z() - pz;
				assertEquals(dx * dx + dy * dy + dz * dz, particle.distanceSquared(), 1.0e-9);
				assertTrue(particle.distanceSquared() <= 9.0d * 9.0d + 1.0e-9,
						"every particle is inside the sphere: " + particle);
			}
		}

		@Test
		@DisplayName("countFor agrees with the list it describes")
		void countMatchesList() {
			for (int radius = 2; radius <= 12; radius++) {
				int counted = BorderParticleGeometry.countFor(
						WEST, BorderSide.MAX_X, -1.5d, 68.0d, 0.5d, radius, MIN_Y, MAX_Y);
				int listed = onSide(-1.5d, 68.0d, 0.5d, radius, BorderSide.MAX_X).size();
				assertEquals(listed, counted, "countFor and particlesFor must not drift (radius " + radius + ")");
			}
		}
	}
}
