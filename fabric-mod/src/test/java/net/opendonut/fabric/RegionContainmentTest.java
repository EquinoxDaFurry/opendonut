package net.opendonut.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.opendonut.common.model.RegionBounds;

/**
 * The pure geometry of {@link RegionContainment}: outside tests, the clamp lines, and the border distance —
 * the exact numbers the player clamp, the chunk-radius shrink and the entity sweep are built on.
 */
class RegionContainmentTest {

	/** The west server's region: x in [-100000, 0), z in [-100000, 100000). */
	private static final RegionBounds WEST = new RegionBounds(-100000, 0, -100000, 100000);

	/** The east server's region: x in [0, 100000), z in [-100000, 100000). */
	private static final RegionBounds EAST = new RegionBounds(0, 100000, -100000, 100000);

	@Test
	@DisplayName("isOutside follows the half-open rectangle: max is exclusive")
	void outsideTests() {
		assertFalse(RegionContainment.isOutside(WEST, -1, 0), "x=-1 is the last inside column of the west");
		assertTrue(RegionContainment.isOutside(WEST, 0, 0), "x=0 belongs to the east");
		assertTrue(RegionContainment.isOutside(EAST, -1, 0), "x=-1 belongs to the west");
		assertFalse(RegionContainment.isOutside(EAST, 0, 0));
		assertTrue(RegionContainment.isOutside(WEST, -100000, 100000), "z=max is outside");
		assertFalse(RegionContainment.isOutside(WEST, -100000, 99999));
	}

	@Test
	@DisplayName("clampBlockX/Z leave inside coordinates alone and cap the overshoot at the limit")
	void clampCapping() {
		assertEquals(-50000, RegionContainment.clampBlockX(WEST, -50000, 2));
		assertEquals(5, RegionContainment.clampBlockX(EAST, 5, 2), "inside the east region, untouched");

		// Deep in the east, clamped to maxX - 1 + overshoot = the last allowed block past the border.
		assertEquals(1, RegionContainment.clampBlockX(WEST, 500, 2));
		// Deep in the west, clamped to minX - overshoot.
		assertEquals(-100002, RegionContainment.clampBlockX(WEST, -100500, 2));
		// Already within the overshoot: unchanged. x=1 is exactly 2 blocks outside (limit 2).
		assertEquals(1, RegionContainment.clampBlockX(WEST, 1, 2));
		assertEquals(-100001, RegionContainment.clampBlockX(WEST, -100001, 2));
		// But x=2 is 3 blocks outside, over the limit of 2, so it is pulled back to 1.
		assertEquals(1, RegionContainment.clampBlockX(WEST, 2, 2));

		assertEquals(100001, RegionContainment.clampBlockZ(WEST, 100003, 2));
		assertEquals(-100002, RegionContainment.clampBlockZ(WEST, -100009, 2));
		assertEquals(0, RegionContainment.clampBlockZ(WEST, 0, 0), "overshoot 0 means fully inside");
	}

	@Test
	@DisplayName("the clamped position is never further outside than the overshoot limit, anywhere")
	void clampInvariant() {
		int overshoot = 2;
		int[] samples = {-1_000_000, -100_003, -100_002, -100_001, -100_000, -1, 0, 1, 2, 3, 999, 1_000_000};
		for (int x : samples) {
			for (int z : samples) {
				int cx = RegionContainment.clampBlockX(WEST, x, overshoot);
				int cz = RegionContainment.clampBlockZ(WEST, z, overshoot);
				int outside = OpenDonutBackend.distanceOutside(WEST, cx, cz);
				assertTrue(outside <= overshoot,
						() -> "clamped (" + cx + ", " + cz + ") from (" + x + ", " + z + ") is " + outside
								+ " blocks outside, over the limit " + overshoot);
			}
		}
	}

	@Test
	@DisplayName("borderDistance counts the blocks until a border crossing, and is 0 outside")
	void borderDistance() {
		assertEquals(1, RegionContainment.borderDistance(WEST, -1, 0), "one step from x=-1 crosses into the east");
		assertEquals(1, RegionContainment.borderDistance(WEST, -100000, 0), "one step from minX crosses out");
		assertEquals(50000, RegionContainment.borderDistance(WEST, -50000, 0));
		assertEquals(0, RegionContainment.borderDistance(WEST, 0, 0), "outside is 0");
		assertEquals(0, RegionContainment.borderDistance(WEST, 5, 100005));
		assertEquals(2, RegionContainment.borderDistance(EAST, 1, 0));
	}

	@Test
	@DisplayName("distanceOutside agrees with the boundary at x=0 between west and east")
	void sharedBorder() {
		assertEquals(0, OpenDonutBackend.distanceOutside(WEST, -1, 0));
		assertEquals(1, OpenDonutBackend.distanceOutside(WEST, 0, 0));
		assertEquals(2, OpenDonutBackend.distanceOutside(WEST, 1, 0));
		assertEquals(0, OpenDonutBackend.distanceOutside(EAST, 0, 0));
		assertEquals(1, OpenDonutBackend.distanceOutside(EAST, -1, 0));
		// Chebyshev: the larger of the two axes wins — here z is 6 out, x is 4 out, so 6.
		assertEquals(6, OpenDonutBackend.distanceOutside(WEST, 3, 100005));
	}
}
