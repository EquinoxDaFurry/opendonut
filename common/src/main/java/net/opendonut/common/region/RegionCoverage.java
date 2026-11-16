package net.opendonut.common.region;

import java.util.List;
import java.util.Objects;

import net.opendonut.common.model.RegionBounds;
import net.opendonut.common.model.ServerDefinition;

/**
 * Exact coverage analysis of a set of server regions inside an area of interest — normally the world
 * border.
 *
 * <h2>What is computed, and why exactly</h2>
 * The validator has to answer three questions without guessing: how much of the world is covered, how
 * much is unassigned, and which regions claim the same block. Block-count estimates are not good
 * enough, because "roughly covered" hides a one-chunk hole that players will find.
 *
 * <p>The analysis reuses the same coordinate compression as {@link SlabRegionIndex}: the X axis is cut
 * at every region edge, and inside each resulting slab the region Z intervals are merged. The union
 * area is then a sum of exact rectangle areas. Overlaps are found by testing the (usually one or two)
 * Z intervals of each slab for intersection, which is exact for the same reason.
 *
 * @param areaInsideBorder total area analysed, normally the world border's area
 * @param coveredArea area owned by at least one region
 * @param unassignedArea area owned by no region
 * @param gaps a sample of the unassigned rectangles, largest first, capped at the requested limit
 * @param gapsTruncated whether more gap rectangles exist than were sampled
 * @param overlaps every pair of regions that claims at least one block in common
 * @param buckets number of X slabs the analysis used
 */
public record RegionCoverage(
		long areaInsideBorder,
		long coveredArea,
		long unassignedArea,
		List<RegionBounds> gaps,
		boolean gapsTruncated,
		List<Overlap> overlaps,
		int buckets) {

	/**
	 * Validates the record.
	 *
	 * @throws IllegalArgumentException if the areas are inconsistent or the limit is not positive
	 */
	public RegionCoverage {
		Objects.requireNonNull(gaps, "gaps");
		Objects.requireNonNull(overlaps, "overlaps");
		if (areaInsideBorder < 0 || coveredArea < 0 || unassignedArea < 0) {
			throw new IllegalArgumentException("coverage areas must not be negative");
		}
		if (coveredArea + unassignedArea != areaInsideBorder) {
			// An exact invariant, not a sanity check. The union area never double counts an overlap (Z
			// intervals are merged inside each slab), so anything other than equality means the analysis
			// skipped part of the area — which is exactly the bug that would hide a hole in the world.
			throw new IllegalArgumentException("covered (" + coveredArea + ") + unassigned (" + unassignedArea
					+ ") must equal the analysed area (" + areaInsideBorder + ")");
		}
		gaps = List.copyOf(gaps);
		overlaps = List.copyOf(overlaps);
	}

	/** The default number of gap rectangles reported by a coverage analysis. */
	public static final int DEFAULT_GAP_SAMPLE_LIMIT = 8;

	/**
	 * One pair of regions that claims a block in common.
	 *
	 * @param firstId identifier of the first region, lexicographically smaller
	 * @param secondId identifier of the second region
	 * @param sharedArea number of blocks claimed by both
	 * @param example a rectangle inside the shared area, for reporting
	 */
	public record Overlap(String firstId, String secondId, long sharedArea, RegionBounds example) {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if an identifier is missing
		 */
		public Overlap {
			Objects.requireNonNull(firstId, "firstId");
			Objects.requireNonNull(secondId, "secondId");
		}

		/**
		 * Human readable description.
		 *
		 * @return for example {@code west / east share 262144 blocks, e.g. X=[0,128) Z=[0,2048)}
		 */
		public String describe() {
			return firstId + " / " + secondId + " share " + sharedArea + " block"
					+ (sharedArea == 1 ? "" : "s") + ", e.g. " + example.describe();
		}
	}

	/**
	 * Analyses a set of regions without building a long-lived index.
	 *
	 * @param regions the regions to analyse
	 * @param area the area of interest, normally the world border
	 * @param gapSampleLimit how many unassigned rectangles to report
	 * @return the coverage report
	 */
	public static RegionCoverage analyze(List<ServerDefinition> regions, RegionBounds area, int gapSampleLimit) {
		return SlabRegionIndex.build(regions).coverage(area, gapSampleLimit);
	}

	/**
	 * Analyses a set of regions using the default gap sample limit.
	 *
	 * @param regions the regions to analyse
	 * @param area the area of interest
	 * @return the coverage report
	 */
	public static RegionCoverage analyze(List<ServerDefinition> regions, RegionBounds area) {
		return analyze(regions, area, DEFAULT_GAP_SAMPLE_LIMIT);
	}

	/**
	 * Tests whether every block inside the analysed area is owned by a region.
	 *
	 * @return {@code true} if {@link #unassignedArea()} is zero
	 */
	public boolean isFullyCovered() {
		return unassignedArea == 0L;
	}

	/**
	 * Tests whether any two regions claim the same block.
	 *
	 * @return {@code true} if at least one overlapping pair was found
	 */
	public boolean hasOverlaps() {
		return !overlaps.isEmpty();
	}

	/**
	 * The share of the analysed area that is not owned by any region.
	 *
	 * @return a percentage in {@code 0..100}
	 */
	public double unassignedPercent() {
		if (areaInsideBorder == 0L) {
			return 0.0d;
		}
		return 100.0d * unassignedArea / areaInsideBorder;
	}

	/**
	 * The share of the analysed area that is owned by at least one region.
	 *
	 * @return a percentage in {@code 0..100}
	 */
	public double coveredPercent() {
		if (areaInsideBorder == 0L) {
			return 0.0d;
		}
		return 100.0d * coveredArea / areaInsideBorder;
	}

	/**
	 * Multi-line human readable summary, used by the CLI report and the startup log.
	 *
	 * @return a description of the coverage, the gaps and the overlaps
	 */
	public String describe() {
		StringBuilder out = new StringBuilder();
		out.append(String.format("coverage: %.3f%% of %d blocks covered (%d unassigned)",
				coveredPercent(), areaInsideBorder, unassignedArea));
		if (!gaps.isEmpty()) {
			out.append(System.lineSeparator()).append("  unassigned areas (largest first, ")
					.append(gaps.size()).append(gapsTruncated ? " shown, more exist" : " found").append("):");
			for (RegionBounds gap : gaps) {
				out.append(System.lineSeparator()).append("    ").append(gap.describe())
						.append("  (").append(gap.area()).append(" blocks)");
			}
		}
		if (!overlaps.isEmpty()) {
			out.append(System.lineSeparator()).append("  overlapping regions:");
			for (Overlap overlap : overlaps) {
				out.append(System.lineSeparator()).append("    ").append(overlap.describe());
			}
		}
		return out.toString();
	}
}
