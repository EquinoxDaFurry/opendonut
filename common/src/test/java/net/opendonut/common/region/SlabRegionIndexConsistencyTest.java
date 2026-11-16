package net.opendonut.common.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.opendonut.common.model.RegionBounds;
import net.opendonut.common.model.ServerAddress;
import net.opendonut.common.model.ServerDefinition;

/**
 * Randomised cross-check of the production {@link SlabRegionIndex} against the reference
 * {@link LinearRegionIndex}.
 *
 * <h2>Why this exists</h2>
 * The slab index is the only non-obvious piece of arithmetic in OpenDonut, and hand-written boundary
 * tests only cover the cases their author thought of. This test generates thousands of region layouts —
 * including deliberately nasty ones with abutting edges, shared corners, negative coordinates and
 * overlapping rectangles — and asserts that the two implementations never disagree anywhere, for a
 * sample of coordinates near every edge and corner.
 *
 * <p>The random generator is seeded with fixed values, so a failure is always reproducible: the test
 * prints the seed it used.
 */
class SlabRegionIndexConsistencyTest {

	/** Layouts generated per seed. */
	private static final int LAYOUTS_PER_SEED = 60;

	/** Fixed seeds keep failures reproducible. */
	private static final long[] SEEDS = {1L, 42L, 20261117L, -987654321L};

	@Test
	@DisplayName("slab and linear indexes agree on random layouts")
	void agreesOnRandomLayouts() {
		for (long seed : SEEDS) {
			Random random = new Random(seed);
			for (int layout = 0; layout < LAYOUTS_PER_SEED; layout++) {
				List<ServerDefinition> servers = generateLayout(random, layout);
				RegionIndex fast = SlabRegionIndex.build(servers);
				RegionIndex reference = LinearRegionIndex.build(servers);
				compareOverSamples(fast, reference, servers, seed, layout, random);
			}
		}
	}

	@Test
	@DisplayName("no valid layout ever yields two owners for one coordinate")
	void noOverlapsInGeneratedGrids() {
		Random random = new Random(7L);
		for (int layout = 0; layout < 40; layout++) {
			List<ServerDefinition> servers = generateGrid(random);
			RegionIndex index = SlabRegionIndex.build(servers);
			for (int x = -200; x <= 200; x += 3) {
				for (int z = -200; z <= 200; z += 3) {
					assertTrue(index.findRegions(x, z).size() <= 1,
							"layout " + layout + " gave multiple owners at (" + x + ", " + z + ")");
				}
			}
		}
	}

	@Test
	@DisplayName("the bucket count never exceeds 2n-1 for a valid layout")
	void bucketCountBound() {
		Random random = new Random(99L);
		for (int layout = 0; layout < 30; layout++) {
			List<ServerDefinition> servers = generateGrid(random);
			SlabRegionIndex index = SlabRegionIndex.build(servers);
			assertTrue(index.bucketCount() <= 2 * servers.size() - 1,
					"layout " + layout + " used " + index.bucketCount() + " slabs for " + servers.size()
							+ " regions");
		}
	}

	/**
	 * Generates a grid-like layout by cutting a rectangle into random columns and rows.
	 *
	 * @param random the generator
	 * @return a set of non-overlapping, chunk-aligned regions
	 */
	private static List<ServerDefinition> generateGrid(Random random) {
		List<ServerDefinition> servers = new ArrayList<>();
		int columns = 1 + random.nextInt(4);
		int rows = 1 + random.nextInt(3);
		int width = 16 * (1 + random.nextInt(6));
		int depth = 16 * (1 + random.nextInt(6));
		int originX = -16 * random.nextInt(5);
		int originZ = -16 * random.nextInt(5);
		int port = 25600;

		for (int column = 0; column < columns; column++) {
			int minX = originX + column * width;
			// The last column absorbs the remainder so the union has no accidental gap between columns.
			int maxX = (column == columns - 1) ? originX + columns * width : minX + width;
			for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
				int minZ = originZ + rowIndex * depth;
				int maxZ = (rowIndex == rows - 1) ? originZ + rows * depth : minZ + depth;
				servers.add(new ServerDefinition(
						"r" + column + "-" + rowIndex,
						new ServerAddress("127.0.0.1", port++),
						new RegionBounds(minX, maxX, minZ, maxZ)));
			}
		}
		return servers;
	}

	/**
	 * Generates an arbitrary layout, including overlaps and gaps.
	 *
	 * @param random the generator
	 * @param layout the layout number, used to make ids unique
	 * @return a set of regions
	 */
	private static List<ServerDefinition> generateLayout(Random random, int layout) {
		int count = 1 + random.nextInt(6);
		List<ServerDefinition> servers = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			// A mix of chunk-aligned and deliberately misaligned rectangles, because the index must stay
			// correct for a configuration that has not been validated yet.
			boolean aligned = random.nextBoolean();
			int minX = (random.nextInt(40) - 20) * (aligned ? 16 : 1);
			int minZ = (random.nextInt(40) - 20) * (aligned ? 16 : 1);
			int maxX = minX + 1 + random.nextInt(aligned ? 5 * 16 : 40);
			int maxZ = minZ + 1 + random.nextInt(aligned ? 5 * 16 : 40);
			servers.add(new ServerDefinition(
					"s" + layout + "_" + i,
					new ServerAddress("127.0.0.1", 30000 + i),
					new RegionBounds(minX, maxX, minZ, maxZ)));
		}
		return servers;
	}

	/**
	 * Compares the two indexes around every region's edges and at random interior points.
	 *
	 * @param fast the production index
	 * @param reference the reference index
	 * @param servers the layout, used to derive interesting coordinates
	 * @param seed the seed that generated the layout, for the failure message
	 * @param layout the layout number, for the failure message
	 * @param random the generator, for interior samples
	 */
	private static void compareOverSamples(
			RegionIndex fast,
			RegionIndex reference,
			List<ServerDefinition> servers,
			long seed,
			int layout,
			Random random) {

		List<int[]> samples = new ArrayList<>();
		for (ServerDefinition server : servers) {
			RegionBounds bounds = server.bounds();
			int[] xs = {bounds.minX() - 1, bounds.minX(), bounds.minX() + 1, bounds.maxX() - 1,
					bounds.maxX(), bounds.maxX() + 1};
			int[] zs = {bounds.minZ() - 1, bounds.minZ(), bounds.minZ() + 1, bounds.maxZ() - 1,
					bounds.maxZ(), bounds.maxZ() + 1};
			for (int x : xs) {
				for (int z : zs) {
					samples.add(new int[] {x, z});
				}
			}
			for (int i = 0; i < 8; i++) {
				samples.add(new int[] {
						bounds.minX() + random.nextInt(Math.max(1, bounds.width())),
						bounds.minZ() + random.nextInt(Math.max(1, bounds.depth()))});
			}
		}
		// Points far outside every region, where the answer must be "unassigned".
		samples.add(new int[] {-100000, -100000});
		samples.add(new int[] {100000, 100000});
		samples.add(new int[] {0, 0});

		for (int[] sample : samples) {
			int x = sample[0];
			int z = sample[1];
			// The set of owners must match exactly. The *order* of multiple owners is deliberately not
			// part of the contract: the slab index orders by geometry inside the slab, the reference
			// implementation by configuration order, and both are deterministic. Only a validated
			// configuration has one owner anyway.
			List<String> fastIds = sortedIds(fast.findRegions(x, z));
			List<String> referenceIds = sortedIds(reference.findRegions(x, z));
			assertEquals(referenceIds, fastIds,
					"seed " + seed + " layout " + layout + " disagreed at (" + x + ", " + z + ")");

			String fastOwner = fast.findRegion(x, z).map(ServerDefinition::id).orElse(null);
			String expected = fastIds.isEmpty() ? null : fastIds.get(0);
			if (fastIds.size() <= 1) {
				// With at most one owner there is no ambiguity about which one findRegion must report.
				assertEquals(expected, fastOwner,
						"seed " + seed + " layout " + layout + " single-owner mismatch at (" + x + ", " + z + ")");
			} else {
				// With overlaps, findRegion must still return one of the owners it reported.
				assertTrue(fastIds.contains(fastOwner),
						"seed " + seed + " layout " + layout + ": findRegion returned " + fastOwner
								+ " which is not among " + fastIds + " at (" + x + ", " + z + ")");
			}
		}
	}

	/**
	 * Maps a list of servers to their ids.
	 *
	 * @param servers the servers
	 * @return the ids
	 */
	private static List<String> sortedIds(List<ServerDefinition> servers) {
		List<String> result = new ArrayList<>(servers.size());
		for (ServerDefinition server : servers) {
			result.add(server.id());
		}
		result.sort(null);
		return result;
	}
}
