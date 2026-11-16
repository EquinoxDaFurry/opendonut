package net.opendonut.common.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import net.opendonut.common.model.RegionBounds;
import net.opendonut.common.model.ServerAddress;
import net.opendonut.common.model.ServerDefinition;

/**
 * The region lookup engine: world corners, every regional boundary, negative coordinates, overlaps and
 * unassigned areas.
 *
 * <p>The fixture reproduces the shipped example layout — a 200,000 block square border split into a west
 * and an east half — and adds a four-way layout with a deliberate hole, because the interesting failures
 * happen at internal corners and in gaps. Every case is asserted against both indexes
 * ({@link SlabRegionIndex} and {@link LinearRegionIndex}) so that a mistake in either implementation
 * shows up here.
 */
class RegionLookupTest {

	private static ServerDefinition server(String id, int minX, int maxX, int minZ, int maxZ, int port) {
		return new ServerDefinition(id, new ServerAddress("127.0.0.1", port),
				new RegionBounds(minX, maxX, minZ, maxZ));
	}

	/** west X=[-100000,0) Z=[-100000,100000), east X=[0,100000) Z=[-100000,100000). */
	private static final ServerDefinition WEST = server("west", -100000, 0, -100000, 100000, 25566);
	private static final ServerDefinition EAST = server("east", 0, 100000, -100000, 100000, 25567);
	private static final List<ServerDefinition> TWO_SERVERS = List.of(WEST, EAST);

	private final RegionIndex index = SlabRegionIndex.build(TWO_SERVERS);
	private final RegionIndex reference = LinearRegionIndex.build(TWO_SERVERS);

	/**
	 * Asserts that both implementations agree and that the answer is the expected server.
	 *
	 * @param x the global block X
	 * @param z the global block Z
	 * @param expectedId the expected owning server id, or {@code null} when unassigned
	 */
	private void assertOwner(int x, int z, String expectedId) {
		String fast = index.findRegion(x, z).map(ServerDefinition::id).orElse(null);
		String slow = reference.findRegion(x, z).map(ServerDefinition::id).orElse(null);
		assertEquals(expectedId, fast, "slab index disagreed at (" + x + ", " + z + ")");
		assertEquals(expectedId, slow, "linear index disagreed at (" + x + ", " + z + ")");
		assertEquals(fast, slow, "the two indexes must always agree");
	}

	@Nested
	@DisplayName("world corners")
	class Corners {

		@ParameterizedTest(name = "corner ({0},{1}) belongs to {2}")
		@CsvSource({
				"-100000, -100000, west",
				"-100000, 99999, west",
				"-1, -100000, west",
				"-1, 99999, west",
				"0, -100000, east",
				"0, 99999, east",
				"99999, -100000, east",
				"99999, 99999, east",
		})
		void cornersAreOwned(int x, int z, String expected) {
			assertOwner(x, z, expected);
		}

		@Test
		@DisplayName("the exact corner block of each region is inside it")
		void innermostCornerBlocks() {
			assertTrue(WEST.bounds().contains(-100000, -100000));
			assertTrue(WEST.bounds().contains(-1, 99999));
			assertTrue(EAST.bounds().contains(0, -100000));
			assertTrue(EAST.bounds().contains(99999, 99999));
		}
	}

	@Nested
	@DisplayName("boundaries: immediately before, on, and after the edge")
	class Boundaries {

		@ParameterizedTest(name = "x={0} z={1} -> {2}")
		@CsvSource({
				// The internal X edge between west and east, three samples each side.
				"-2, 0, west",
				"-1, 0, west",
				"0, 0, east",
				"1, 0, east",
				// Well away from the internal edge, to prove the Z axis is not involved in this decision.
				"-1, 99999, west",
				"0, 99999, east",
				"0, -100000, east",
				"-1, -100000, west",
				// Outside the west edge of the world.
				"-100001, 0, ",
				// Outside the east edge of the world.
				"100000, 0, ",
				// Outside the Z range of both regions.
				"0, 100000, ",
				"-1, -100001, ",
				"0, -100001, ",
				"0, 100000, ",
		})
		void eitherSideOfTheEdge(int x, int z, String expected) {
			assertOwner(x, z, expected == null || expected.isBlank() ? null : expected);
		}

		@Test
		@DisplayName("no coordinate in the fixture is claimed twice")
		void noCoordinateIsClaimedTwice() {
			for (int x = -100002; x <= 100001; x += 7) {
				for (int z = -100002; z <= 100001; z += 97) {
					assertTrue(index.findRegions(x, z).size() <= 1,
							"(" + x + ", " + z + ") was claimed by more than one region");
				}
			}
		}

		@Test
		@DisplayName("the region index reports the expected bucket count")
		void bucketCountIsBounded() {
			// Two regions sharing one internal edge produce three edges and therefore two slabs.
			assertEquals(2, index.bucketCount(), "two abutting regions must compress to two slabs");
			assertTrue(index.bucketCount() <= 2 * TWO_SERVERS.size() - 1,
					"compression must never exceed 2n-1 slabs");
		}
	}

	@Nested
	@DisplayName("negative coordinates")
	class NegativeCoordinates {

		@ParameterizedTest(name = "(-16k {0}, {1}) -> {2}")
		@CsvSource({
				"-16, -16, west",
				"-1, -1, west",
				"-99999, -99999, west",
				"-100000, 99999, west",
		})
		void negativeCoordinatesAreResolved(int x, int z, String expected) {
			assertOwner(x, z, expected);
		}

		@Test
		@DisplayName("chunk -1 is entirely inside west, chunk 0 entirely inside east")
		void chunkOwnershipAroundTheOrigin() {
			assertEquals("west", index.findRegion(-1, -1).orElseThrow().id());
			assertEquals("east", index.findRegion(0, 0).orElseThrow().id());
			// Every block of chunk -1 belongs to west...
			for (int block = -16; block < 0; block++) {
				assertEquals("west", index.findRegion(block, block).orElseThrow().id(),
						"block " + block + " of chunk -1");
			}
			// ...and every block of chunk 0 belongs to east, with no ambiguity at the seam.
			for (int block = 0; block < 16; block++) {
				assertEquals("east", index.findRegion(block, block).orElseThrow().id(),
						"block " + block + " of chunk 0");
			}
		}

		@Test
		@DisplayName("extremes of the int range do not overflow")
		void extremeCoordinates() {
			assertOwner(Integer.MIN_VALUE, Integer.MIN_VALUE, null);
			assertOwner(Integer.MAX_VALUE, Integer.MAX_VALUE, null);
			assertOwner(Integer.MIN_VALUE, 0, null);
			assertOwner(0, Integer.MAX_VALUE, null);
		}
	}

	@Nested
	@DisplayName("overlapping regions")
	class Overlapping {

		private final ServerDefinition overlap = server("overlap", -50000, 50000, -50000, 50000, 25568);
		private final RegionIndex overlapping = SlabRegionIndex.build(List.of(WEST, EAST, overlap));

		@Test
		@DisplayName("findRegions reports every owner so the validator can describe the conflict")
		void reportsAllOwners() {
			// Block -1 is west's last block and is also inside the overlapping region.
			List<ServerDefinition> westSide = overlapping.findRegions(-1, 0);
			assertEquals(2, westSide.size(), "west and overlap both claim (-1,0)");
			assertTrue(westSide.stream().anyMatch(server -> server.id().equals("west")));
			assertTrue(westSide.stream().anyMatch(server -> server.id().equals("overlap")));

			// Block 0 is east's first block, and is likewise inside the overlap.
			List<ServerDefinition> eastSide = overlapping.findRegions(0, 0);
			assertEquals(2, eastSide.size(), "east and overlap both claim (0,0)");
			assertTrue(eastSide.stream().anyMatch(server -> server.id().equals("east")));
			assertTrue(eastSide.stream().anyMatch(server -> server.id().equals("overlap")));
		}

		@Test
		@DisplayName("findRegion still returns one deterministic owner")
		void picksDeterministically() {
			// Documented rule: the owner with the lowest minimum Z, then configuration order. In this slab
			// the candidates ordered by minimum Z are east (-100000) then overlap (-50000), so east wins.
			assertEquals("east", overlapping.findRegion(0, 0).orElseThrow().id());
			assertEquals(overlapping.findRegion(0, 0), overlapping.findRegion(0, 0),
					"repeated lookups must return the same owner");
			// And the answer is always one of the regions that actually contains the coordinate.
			assertTrue(overlapping.findRegions(0, 0).contains(overlapping.findRegion(0, 0).orElseThrow()));
		}

		@Test
		@DisplayName("an overlap is visible through the coverage analysis")
		void overlapIsDetected() {
			RegionCoverage coverage = RegionCoverage.analyze(List.of(WEST, EAST, overlap),
					new RegionBounds(-100000, 100000, -100000, 100000));
			assertTrue(coverage.hasOverlaps());
			// "overlap" is X=[-50000,50000) Z=[-50000,50000). It shares X=[-50000,0) x Z=[-50000,50000)
			// with west, which is 50000 x 100000 = 5e9 blocks, and the mirror image with east.
			assertEquals(2, coverage.overlaps().size(), "one pair per neighbour");
			for (RegionCoverage.Overlap pair : coverage.overlaps()) {
				assertEquals(5_000_000_000L, pair.sharedArea(),
						"west/overlap and east/overlap share 5e9 blocks each: " + pair.describe());
			}
			assertEquals(10_000_000_000L,
					coverage.overlaps().stream().mapToLong(RegionCoverage.Overlap::sharedArea).sum(),
					"the two pairs together report 1e10 shared blocks");
		}
	}

	@Nested
	@DisplayName("unassigned areas")
	class Unassigned {

		/** A 32 x 32 border, used because the coordinate arithmetic stays checkable by hand. */
		private static final RegionBounds SMALL_BORDER = new RegionBounds(-32, 32, -32, 32);

		/** The complete four-way split of {@link #SMALL_BORDER}: no gaps, no overlaps. */
		private final List<ServerDefinition> fullSplit = List.of(
				server("nw", -32, 0, -32, 0, 25570),
				server("ne", 0, 32, -32, 0, 25571),
				server("sw", -32, 0, 0, 32, 25572),
				server("se", 0, 32, 0, 32, 25573));

		/** The same split with the south-east quadrant missing: one 32 x 32 hole. */
		private final List<ServerDefinition> missingSouthEast = List.of(
				server("nw", -32, 0, -32, 0, 25570),
				server("ne", 0, 32, -32, 0, 25571),
				server("sw", -32, 0, 0, 32, 25572));

		@Test
		@DisplayName("the missing quadrant is unassigned in both indexes")
		void holeIsUnassigned() {
			RegionIndex holey = SlabRegionIndex.build(missingSouthEast);
			RegionIndex referenceHoley = LinearRegionIndex.build(missingSouthEast);
			for (int x = 0; x < 32; x++) {
				for (int z = 0; z < 32; z++) {
					assertTrue(holey.findRegion(x, z).isEmpty(), "(" + x + "," + z + ") is in the hole");
					assertTrue(referenceHoley.findRegion(x, z).isEmpty());
					assertFalse(holey.isAssigned(x, z));
				}
			}
		}

		@Test
		@DisplayName("the remaining three quadrants are still owned correctly")
		void quadrantsAreOwned() {
			RegionIndex holey = SlabRegionIndex.build(missingSouthEast);
			assertEquals("nw", holey.findRegion(-1, -1).orElseThrow().id());
			assertEquals("ne", holey.findRegion(0, -1).orElseThrow().id());
			assertEquals("sw", holey.findRegion(-1, 0).orElseThrow().id());
			assertTrue(holey.findRegion(0, 0).isEmpty(), "the south-east quadrant is missing");
		}

		@Test
		@DisplayName("coverage reports the exact size of the hole")
		void coverageComputesTheHole() {
			RegionCoverage coverage = RegionCoverage.analyze(missingSouthEast, SMALL_BORDER);
			assertFalse(coverage.isFullyCovered());
			assertEquals(32L * 32L, coverage.unassignedArea(), "the hole is one 32 x 32 quadrant");
			assertEquals(1, coverage.gaps().size(), "the hole is a single rectangle");
			assertEquals(new RegionBounds(0, 32, 0, 32), coverage.gaps().get(0));
			assertEquals(32L * 32L * 3L, coverage.coveredArea(), "three of the four quadrants are covered");
			assertEquals(75.0d, coverage.coveredPercent(), 0.0001d);
		}

		@Test
		@DisplayName("a missing X range with no region at all is still counted as unassigned")
		void columnWithNoRegionIsCounted() {
			// A deliberate regression guard: an analysis built only from the regions' own edges would see
			// no slab at all for X=[0,32) here, and would report a fully covered world.
			List<ServerDefinition> westOnly = List.of(server("west", -32, 0, -32, 32, 25570));
			RegionCoverage coverage = RegionCoverage.analyze(westOnly, SMALL_BORDER);
			assertEquals(32L * 64L, coverage.unassignedArea(), "exactly the eastern half is unassigned");
			assertEquals(50.0d, coverage.coveredPercent(), 0.0001d);
			assertEquals(1, coverage.gaps().size());
			assertEquals(new RegionBounds(0, 32, -32, 32), coverage.gaps().get(0));
		}

		@Test
		@DisplayName("a full four-way split has no gaps and no overlaps")
		void fullSplitIsClean() {
			RegionCoverage coverage = RegionCoverage.analyze(fullSplit, SMALL_BORDER);
			assertTrue(coverage.isFullyCovered());
			assertFalse(coverage.hasOverlaps());
			assertEquals(coverage.areaInsideBorder(), coverage.coveredArea());
			assertTrue(coverage.gaps().isEmpty());
			assertEquals(100.0d, coverage.coveredPercent(), 0.0001d);
			// Four regions sharing internal edges compress to two slabs (X < 0 and X >= 0).
			assertEquals(2, SlabRegionIndex.build(fullSplit).bucketCount());
		}
	}

	@Nested
	@DisplayName("structural behaviour")
	class Structure {

		@Test
		@DisplayName("an empty index answers every lookup with unassigned")
		void emptyIndex() {
			RegionIndex empty = SlabRegionIndex.build(List.of());
			assertTrue(empty.findRegion(0, 0).isEmpty());
			assertTrue(empty.findRegions(0, 0).isEmpty());
			assertEquals(0, empty.bucketCount());
			assertTrue(empty.regions().isEmpty());
		}

		@Test
		@DisplayName("regions that cover no block are dropped from the index")
		void emptyRegionsAreDropped() {
			ServerDefinition degenerate = server("degenerate", 0, 0, 0, 0, 25599);
			RegionIndex idx = SlabRegionIndex.build(List.of(WEST, degenerate));
			assertEquals(1, idx.regions().size(), "a region with no area owns nothing and is not indexed");
			assertTrue(idx.findRegion(0, 0).isEmpty());
		}

		@Test
		@DisplayName("the index keeps the configured region objects")
		void regionsArePreserved() {
			assertSame(WEST, index.regions().get(0));
			assertEquals(2, index.regions().size());
		}

		@Test
		@DisplayName("lookups are deterministic across repeated calls")
		void deterministic() {
			for (int i = 0; i < 1000; i++) {
				assertEquals("west", index.findRegion(-1000 - i, i).orElseThrow().id());
			}
		}

		@Test
		@DisplayName("three servers in a row compress to three slabs")
		void threeInARow() {
			List<ServerDefinition> three = List.of(
					server("a", -48, -16, -16, 16, 25580),
					server("b", -16, 16, -16, 16, 25581),
					server("c", 16, 48, -16, 16, 25582));
			RegionIndex idx = SlabRegionIndex.build(three);
			assertEquals(3, idx.bucketCount());
			assertEquals("a", idx.findRegion(-48, 0).orElseThrow().id());
			assertEquals("a", idx.findRegion(-17, 0).orElseThrow().id());
			assertEquals("b", idx.findRegion(-16, 0).orElseThrow().id());
			assertEquals("b", idx.findRegion(15, 0).orElseThrow().id());
			assertEquals("c", idx.findRegion(16, 0).orElseThrow().id());
			assertEquals("c", idx.findRegion(47, 0).orElseThrow().id());
			assertTrue(idx.findRegion(48, 0).isEmpty());
		}
	}
}
