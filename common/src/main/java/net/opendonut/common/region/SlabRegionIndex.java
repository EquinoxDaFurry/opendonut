package net.opendonut.common.region;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import net.opendonut.common.model.RegionBounds;
import net.opendonut.common.model.ServerDefinition;

/**
 * The production {@link RegionIndex}: coordinate compression on the X axis, binary search on both axes.
 *
 * <h2>The structure</h2>
 * Region edges are only ever at the coordinates the administrator wrote. Cutting the X axis at every
 * distinct {@code minX} and {@code maxX} produces <em>slabs</em> — ranges of X in which the set of
 * candidate regions never changes. With {@code n} regions there are at most {@code 2n - 1} slabs, and
 * typically far fewer because neighbouring regions share edges:
 *
 * <pre>
 *   west  X = [-100000,      0)
 *   east  X = [      0, 100000)
 *
 *   edges  = [-100000, 0, 100000]
 *   slab 0 = [-100000, 0)      candidates: west
 *   slab 1 = [      0, 100000) candidates: east
 * </pre>
 *
 * <p>Inside a slab the candidate regions are sorted by their minimum Z. For a valid configuration their
 * Z intervals are disjoint, so a single binary search finds the owner or proves that the coordinate is
 * unassigned — no scan is needed. A slab whose regions do overlap (only possible in a configuration
 * that failed validation) is flagged, and lookups in that slab fall back to an exact scan so that
 * {@link #findRegions} stays correct and the validator can report the conflict accurately.
 *
 * <h2>Cost</h2>
 * Building is {@code O(n log n)}. A lookup is two binary searches, {@code O(log n)} with a small
 * constant, which is what makes it acceptable to run once per player per tick on a busy backend. The
 * memory footprint is one object per slab plus one array per slab.
 *
 * <h2>Why not an R-tree or a grid?</h2>
 * A grid would need a cell size, and the right cell size depends on the region layout; a bad choice
 * degrades to linear scanning. An R-tree is more code and more allocation per lookup than this
 * structure needs, because OpenDonut's regions are static, axis-aligned and non-overlapping after
 * validation. Compression exploits exactly those properties.
 */
public final class SlabRegionIndex implements RegionIndex {

	/** Candidate regions of one X slab, sorted by minimum Z. */
	private static final class Slab {

		private final int minX;
		private final int maxX;
		private final ServerDefinition[] byMinZ;
		private final boolean disjointZ;

		private Slab(int minX, int maxX, ServerDefinition[] byMinZ, boolean disjointZ) {
			this.minX = minX;
			this.maxX = maxX;
			this.byMinZ = byMinZ;
			this.disjointZ = disjointZ;
		}
	}

	private final List<ServerDefinition> regions;
	private final int[] edges;
	private final Slab[] slabs;

	private SlabRegionIndex(List<ServerDefinition> regions, int[] edges, Slab[] slabs) {
		this.regions = regions;
		this.edges = edges;
		this.slabs = slabs;
	}

	/**
	 * Builds an index from a list of regions.
	 *
	 * <p>Regions that cover no block at all are dropped: they can never own a coordinate, and keeping
	 * them would create empty slabs.
	 *
	 * @param source the regions to index, in configuration order
	 * @return the index
	 */
	public static SlabRegionIndex build(List<ServerDefinition> source) {
		Objects.requireNonNull(source, "source");

		List<ServerDefinition> usable = new ArrayList<>(source.size());
		for (ServerDefinition region : source) {
			if (!region.bounds().isEmpty()) {
				usable.add(region);
			}
		}
		List<ServerDefinition> regions = List.copyOf(usable);

		if (regions.isEmpty()) {
			return new SlabRegionIndex(regions, new int[0], new Slab[0]);
		}

		TreeSet<Integer> edgeSet = new TreeSet<>();
		for (ServerDefinition region : regions) {
			edgeSet.add(region.bounds().minX());
			edgeSet.add(region.bounds().maxX());
		}
		int[] edges = new int[edgeSet.size()];
		int cursor = 0;
		for (int edge : edgeSet) {
			edges[cursor++] = edge;
		}

		// Configuration order, precomputed because it is the tiebreak for equal minimum Z values and a
		// repeated indexOf call inside a comparator would make the build quadratic.
		Map<String, Integer> configOrder = new HashMap<>();
		for (int i = 0; i < regions.size(); i++) {
			configOrder.put(regions.get(i).id(), i);
		}

		Slab[] slabs = new Slab[edges.length - 1];
		for (int i = 0; i < slabs.length; i++) {
			int slabMinX = edges[i];
			int slabMaxX = edges[i + 1];

			List<ServerDefinition> candidates = new ArrayList<>(2);
			for (ServerDefinition region : regions) {
				RegionBounds bounds = region.bounds();
				if (bounds.minX() <= slabMinX && bounds.maxX() >= slabMaxX) {
					candidates.add(region);
				}
			}
			// Deterministic order: by minimum Z, then by configuration order for ties. The tiebreak is
			// what makes a lookup on an overlapping configuration reproducible instead of arbitrary.
			candidates.sort(Comparator
					.comparingInt((ServerDefinition region) -> region.bounds().minZ())
					.thenComparingInt(region -> configOrder.getOrDefault(region.id(), Integer.MAX_VALUE)));

			ServerDefinition[] byMinZ = candidates.toArray(new ServerDefinition[0]);
			boolean disjointZ = isDisjointInZ(byMinZ);
			slabs[i] = new Slab(slabMinX, slabMaxX, byMinZ, disjointZ);
		}
		return new SlabRegionIndex(regions, edges, slabs);
	}

	/**
	 * Tests whether the Z intervals of a slab's regions are pairwise disjoint.
	 *
	 * <p>Assumes the array is sorted by minimum Z, so a single pass suffices: a later interval must
	 * start at or after the maximum end seen so far.
	 *
	 * @param byMinZ candidate regions sorted by minimum Z
	 * @return {@code true} if no two intervals share a coordinate
	 */
	private static boolean isDisjointInZ(ServerDefinition[] byMinZ) {
		int maxEndSoFar = Integer.MIN_VALUE;
		for (ServerDefinition region : byMinZ) {
			RegionBounds bounds = region.bounds();
			if (bounds.minZ() < maxEndSoFar) {
				return false;
			}
			maxEndSoFar = Math.max(maxEndSoFar, bounds.maxZ());
		}
		return true;
	}

	/**
	 * Finds the index of the slab that contains an X coordinate.
	 *
	 * @param x the global block X
	 * @return the slab index, or {@code -1} when X is outside every slab
	 */
	private int slabFor(int x) {
		if (slabs.length == 0 || x < edges[0] || x >= edges[edges.length - 1]) {
			return -1;
		}
		int found = Arrays.binarySearch(edges, x);
		if (found >= 0) {
			// Exactly on an edge: the slab that starts here owns the coordinate, except for the last
			// edge, which has no slab after it. That case was rejected above.
			return found;
		}
		return -found - 2;
	}

	/**
	 * Finds the last position in a slab's candidate array whose minimum Z is {@code <= z}.
	 *
	 * @param slab the slab to search
	 * @param z the global block Z
	 * @return the position, or {@code -1} if every candidate starts above {@code z}
	 */
	private static int lastStartingAtOrBefore(Slab slab, int z) {
		int low = 0;
		int high = slab.byMinZ.length - 1;
		int result = -1;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			if (slab.byMinZ[mid].bounds().minZ() <= z) {
				result = mid;
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		return result;
	}

	@Override
	public Optional<ServerDefinition> findRegion(int x, int z) {
		int slabIndex = slabFor(x);
		if (slabIndex < 0) {
			return Optional.empty();
		}
		Slab slab = slabs[slabIndex];
		if (slab.byMinZ.length == 0) {
			return Optional.empty();
		}

		if (slab.disjointZ) {
			// Fast path: with disjoint intervals ordered by minimum Z, the last interval that starts at
			// or below z is the only one that can contain z.
			int candidate = lastStartingAtOrBefore(slab, z);
			if (candidate >= 0 && slab.byMinZ[candidate].owns(x, z)) {
				return Optional.of(slab.byMinZ[candidate]);
			}
			return Optional.empty();
		}

		// Overlapping configuration: fall back to an exact scan so the answer stays correct.
		for (ServerDefinition region : slab.byMinZ) {
			if (region.owns(x, z)) {
				return Optional.of(region);
			}
		}
		return Optional.empty();
	}

	@Override
	public List<ServerDefinition> findRegions(int x, int z) {
		int slabIndex = slabFor(x);
		if (slabIndex < 0) {
			return List.of();
		}
		Slab slab = slabs[slabIndex];
		List<ServerDefinition> matches = new ArrayList<>(1);
		for (ServerDefinition region : slab.byMinZ) {
			if (region.owns(x, z)) {
				matches.add(region);
			}
		}
		return List.copyOf(matches);
	}

	@Override
	public List<ServerDefinition> regions() {
		return regions;
	}

	@Override
	public int bucketCount() {
		return slabs.length;
	}

	/** @return the sorted X edges that delimit the slabs */
	public int[] edges() {
		return edges.clone();
	}

	/**
	 * Computes exact coverage, unassigned areas and overlaps for an area of interest.
	 *
	 * @param area the area to analyse, normally the world border
	 * @param gapSampleLimit maximum number of unassigned rectangles to report
	 * @return the coverage report
	 */
	public RegionCoverage coverage(RegionBounds area, int gapSampleLimit) {
		Objects.requireNonNull(area, "area");
		if (gapSampleLimit < 1) {
			throw new IllegalArgumentException("gapSampleLimit must be at least 1, got " + gapSampleLimit);
		}

		if (area.isEmpty()) {
			return new RegionCoverage(0L, 0L, 0L, List.of(), false, List.of(), slabs.length);
		}
		long areaInsideBorder = area.area();

		long coveredArea = 0L;
		long unassignedArea = 0L;
		List<RegionBounds> gaps = new ArrayList<>();
		boolean gapsTruncated = false;

		Map<String, OverlapAccumulator> overlaps = new HashMap<>();

		// The analysis sweeps the *area*, not the index's own slabs. That distinction is the whole point:
		// a stretch of X where no region exists at all produces no slab, and an analysis built from the
		// index alone would silently skip it and report a fully covered world with a hole in it. Cutting
		// the area at every region edge (clipped to the area) guarantees the sweep is complete.
		TreeSet<Integer> areaEdges = new TreeSet<>();
		areaEdges.add(area.minX());
		areaEdges.add(area.maxX());
		for (ServerDefinition region : regions) {
			RegionBounds bounds = region.bounds();
			if (bounds.maxX() <= area.minX() || bounds.minX() >= area.maxX()) {
				continue;
			}
			areaEdges.add(Math.max(bounds.minX(), area.minX()));
			areaEdges.add(Math.min(bounds.maxX(), area.maxX()));
		}

		int[] edgeArray = new int[areaEdges.size()];
		int edgeCursor = 0;
		for (int edge : areaEdges) {
			edgeArray[edgeCursor++] = edge;
		}

		for (int edgeIndex = 0; edgeIndex < edgeArray.length - 1; edgeIndex++) {
			int slabMinX = edgeArray[edgeIndex];
			int slabMaxX = edgeArray[edgeIndex + 1];
			if (slabMinX >= slabMaxX) {
				continue;
			}
			long slabWidth = slabMaxX - slabMinX;

			// Candidates are the regions that cover this whole X slab.
			List<ServerDefinition> candidates = new ArrayList<>(2);
			for (ServerDefinition region : regions) {
				RegionBounds bounds = region.bounds();
				if (bounds.minX() <= slabMinX && bounds.maxX() >= slabMaxX) {
					candidates.add(region);
				}
			}
			candidates.sort(Comparator
					.comparingInt((ServerDefinition region) -> region.bounds().minZ())
					.thenComparing(ServerDefinition::id));

			int[][] clipped = new int[candidates.size()][2];
			int clippedCount = 0;
			for (ServerDefinition region : candidates) {
				int minZ = Math.max(region.bounds().minZ(), area.minZ());
				int maxZ = Math.min(region.bounds().maxZ(), area.maxZ());
				if (minZ < maxZ) {
					clipped[clippedCount][0] = minZ;
					clipped[clippedCount][1] = maxZ;
					clippedCount++;
				}
			}
			Arrays.sort(clipped, 0, clippedCount, Comparator.comparingInt((int[] range) -> range[0])
					.thenComparingInt(range -> range[1]));

			long mergedZ = 0L;
			int cursor = area.minZ();
			for (int i = 0; i < clippedCount; i++) {
				int minZ = clipped[i][0];
				int maxZ = clipped[i][1];
				if (minZ > cursor) {
					unassignedArea += slabWidth * (minZ - cursor);
					if (gaps.size() < gapSampleLimit) {
						gaps.add(new RegionBounds(slabMinX, slabMaxX, cursor, minZ));
					} else {
						gapsTruncated = true;
					}
				}
				if (maxZ > cursor) {
					mergedZ += maxZ - Math.max(cursor, minZ);
					cursor = maxZ;
				}
			}
			if (cursor < area.maxZ()) {
				unassignedArea += slabWidth * (area.maxZ() - (long) cursor);
				if (gaps.size() < gapSampleLimit) {
					gaps.add(new RegionBounds(slabMinX, slabMaxX, cursor, area.maxZ()));
				} else {
					gapsTruncated = true;
				}
			}
			coveredArea += slabWidth * mergedZ;

			// Overlap detection: pairwise over this slab's candidates. A slab holds one or two regions in
			// practice, so the quadratic term never matters.
			for (int i = 0; i < candidates.size(); i++) {
				for (int j = i + 1; j < candidates.size(); j++) {
					ServerDefinition a = candidates.get(i);
					ServerDefinition b = candidates.get(j);
					RegionBounds shared = a.bounds().intersection(b.bounds());
					if (shared == null) {
						continue;
					}
					RegionBounds clippedShared = shared.intersection(area);
					if (clippedShared == null) {
						continue;
					}
					overlaps.computeIfAbsent(pairKey(a, b), key -> new OverlapAccumulator(a, b))
							.add(clippedShared);
				}
			}
		}

		List<RegionCoverage.Overlap> reported = overlaps.values().stream()
				.map(OverlapAccumulator::toOverlap)
				.sorted(Comparator.comparing(RegionCoverage.Overlap::firstId)
						.thenComparing(RegionCoverage.Overlap::secondId))
				.toList();

		gaps.sort(Comparator.comparingLong((RegionBounds gap) -> gap.area()).reversed()
				.thenComparingInt(RegionBounds::minX)
				.thenComparingInt(RegionBounds::minZ));

		return new RegionCoverage(areaInsideBorder, coveredArea, unassignedArea, gaps, gapsTruncated, reported,
				slabs.length);
	}

	/**
	 * Accumulates the shared area of one pair of regions across every slab they both appear in.
	 *
	 * <p>The same pair can overlap in several slabs (the overlap rectangle spans more than one slab
	 * whenever another region's edge cuts through it), so the areas have to be summed rather than
	 * overwritten.
	 */
	private static final class OverlapAccumulator {

		private final ServerDefinition first;
		private final ServerDefinition second;
		private long sharedArea;
		private RegionBounds example;

		private OverlapAccumulator(ServerDefinition a, ServerDefinition b) {
			if (a.id().compareTo(b.id()) <= 0) {
				this.first = a;
				this.second = b;
			} else {
				this.first = b;
				this.second = a;
			}
		}

		private void add(RegionBounds shared) {
			sharedArea += shared.area();
			if (example == null) {
				example = shared;
			}
		}

		private RegionCoverage.Overlap toOverlap() {
			return new RegionCoverage.Overlap(first.id(), second.id(), sharedArea, example);
		}
	}

	/**
	 * Builds a stable, order-independent key for a pair of regions.
	 *
	 * @param a one region
	 * @param b the other region
	 * @return a key that does not depend on argument order
	 */
	private static String pairKey(ServerDefinition a, ServerDefinition b) {
		return a.id().compareTo(b.id()) <= 0 ? a.id() + "\u0000" + b.id() : b.id() + "\u0000" + a.id();
	}

	@Override
	public String describe() {
		StringBuilder out = new StringBuilder();
		out.append("SlabRegionIndex[regions=").append(regions.size())
				.append(", slabs=").append(slabs.length).append("]");
		TreeMap<Integer, Integer> candidateHistogram = new TreeMap<>();
		for (Slab slab : slabs) {
			candidateHistogram.merge(slab.byMinZ.length, 1, Integer::sum);
		}
		out.append(" candidates-per-slab=").append(candidateHistogram);
		return out.toString();
	}
}
