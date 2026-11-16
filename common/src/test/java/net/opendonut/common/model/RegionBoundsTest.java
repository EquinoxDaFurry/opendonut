package net.opendonut.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The half-open interval convention.
 *
 * <p>Every ownership decision in OpenDonut reduces to {@link RegionBounds#contains(int, int)}, so the
 * boundary behaviour is tested explicitly for all four edges rather than only in the middle of a region.
 */
class RegionBoundsTest {

	/** The region used by the shipped example configuration: X = [-100000, 0), Z = [-100000, 100000). */
	private static final RegionBounds WEST = new RegionBounds(-100000, 0, -100000, 100000);

	/** Its neighbour: X = [0, 100000), Z = [-100000, 100000). */
	private static final RegionBounds EAST = new RegionBounds(0, 100000, -100000, 100000);

	@ParameterizedTest(name = "({0},{1}) inside WEST is {2}")
	@CsvSource({
			// Inside.
			"-100000, -100000, true",
			"-1, -1, true",
			"-99999, 99999, true",
			// Just outside, on each edge.
			"-100001, 0, false",
			"0, 0, false",
			"-1, -100001, false",
			"-1, 100000, false",
			// Far outside.
			"100000, 0, false",
			"-100000, -100001, false",
	})
	void containsRespectsMinInclusiveMaxExclusive(int x, int z, boolean expected) {
		assertEquals(expected, WEST.contains(x, z), "(" + x + ", " + z + ") in " + WEST.describe());
	}

	@Test
	@DisplayName("the shared edge block belongs to exactly one region")
	void sharedEdgeIsUnambiguous() {
		assertTrue(WEST.contains(-1, 0), "block -1 belongs to west");
		assertFalse(WEST.contains(0, 0), "block 0 must not belong to west");
		assertTrue(EAST.contains(0, 0), "block 0 belongs to east");
		assertFalse(EAST.contains(-1, 0), "block -1 must not belong to east");
		assertEquals(0L, WEST.intersectionArea(EAST), "adjacent regions must not share a block");
	}

	@Test
	@DisplayName("an inverted region is rejected at construction")
	void rejectsInvertedBounds() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> new RegionBounds(10, 5, 0, 10));
		assertTrue(e.getMessage().contains("maxX"), "the message must name the offending axis: " + e.getMessage());
		assertThrows(IllegalArgumentException.class, () -> new RegionBounds(0, 10, 10, 5));
	}

	@Test
	@DisplayName("an empty region contains nothing but is not an error")
	void emptyRegion() {
		RegionBounds empty = new RegionBounds(5, 5, 0, 10);
		assertTrue(empty.isEmpty());
		assertFalse(empty.contains(5, 5));
		assertEquals(0L, empty.area());
	}

	@Test
	@DisplayName("dimensions and area are derived from the half-open intervals")
	void dimensions() {
		assertEquals(100000, WEST.width());
		assertEquals(200000, WEST.depth());
		assertEquals(20_000_000_000L, WEST.area());
		// Blocks -100000..-1 span chunks -6250..-1, so the exclusive maximum chunk is 0.
		assertEquals(-6250, WEST.chunkMinX());
		assertEquals(0, WEST.chunkMaxX(), "west ends where chunk 0 begins");
		assertEquals(-6250, WEST.chunkMinZ());
		assertEquals(6250, WEST.chunkMaxZ());
	}

	@Test
	@DisplayName("chunk alignment requires all four edges on chunk boundaries")
	void chunkAlignment() {
		assertTrue(WEST.isChunkAligned());
		assertTrue(new RegionBounds(-16, 16, -16, 16).isChunkAligned());
		assertFalse(new RegionBounds(-1, 16, 0, 16).isChunkAligned(), "min-x is off");
		assertFalse(new RegionBounds(0, 15, 0, 16).isChunkAligned(), "max-x is off");
	}

	@Test
	@DisplayName("a chunk is owned when the region overlaps any of it")
	void chunkOwnership() {
		// The crux of the whole design: chunk -1 is west's last chunk and chunk 0 is east's first, and
		// neither region claims a single block of the other's chunk.
		assertTrue(WEST.containsChunk(-1, 0), "chunk -1 spans blocks -16..-1, entirely inside west");
		assertFalse(WEST.containsChunk(0, 0), "chunk 0 spans blocks 0..15, entirely outside west");
		assertTrue(EAST.containsChunk(0, 0), "chunk 0 is the first chunk of east");
		assertFalse(EAST.containsChunk(-1, 0), "chunk -1 belongs to west");
		assertFalse(WEST.containsChunk(0, 0) && EAST.containsChunk(0, 0),
				"no chunk may be claimed by both neighbours");
		assertFalse(WEST.containsChunk(6250, 0), "chunk 6250 is past west's max-x");
		assertTrue(WEST.containsChunk(-6250, 0), "chunk -6250 is west's first chunk");
	}

	@Test
	@DisplayName("overlap detection is exact")
	void overlaps() {
		RegionBounds overlapping = new RegionBounds(-50000, 50000, 0, 100000);
		assertTrue(WEST.intersects(overlapping));
		assertEquals(50_000L * 100_000L, WEST.intersectionArea(overlapping));

		RegionBounds touching = new RegionBounds(-100000, 100000, 100000, 200000);
		assertFalse(WEST.intersects(touching), "regions that only touch on an edge do not overlap");
		assertEquals(0L, WEST.intersectionArea(touching));

		assertNull(WEST.intersection(touching));
		assertNotNull(WEST.intersection(overlapping));
		assertEquals(new RegionBounds(-50000, 0, 0, 100000), WEST.intersection(overlapping));
	}

	@Test
	@DisplayName("shrinking keeps the region usable for a push-back")
	void shrink() {
		RegionBounds shrunk = WEST.shrink(4);
		assertEquals(new RegionBounds(-99996, -4, -99996, 99996), shrunk);
		assertFalse(shrunk.contains(-1, 0), "the shrunk region must not contain the border block");
		assertTrue(shrunk.contains(-5, 0));

		RegionBounds tiny = new RegionBounds(0, 2, 0, 2);
		RegionBounds shrunkTiny = tiny.shrink(100);
		assertFalse(shrunkTiny.isEmpty(), "shrinking must never invert a region");
	}

	@Test
	@DisplayName("describe renders the configuration's own notation")
	void describe() {
		assertEquals("X=[-100000,0) Z=[-100000,100000)", WEST.describe());
	}
}
