package net.opendonut.common.region;

import java.util.List;
import java.util.Optional;

import net.opendonut.common.model.RegionBounds;
import net.opendonut.common.model.ServerDefinition;

/**
 * Answers the central question of OpenDonut: <em>which backend owns this global coordinate?</em>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><strong>Deterministic.</strong> The same coordinate always yields the same answer for a given
 *       index. Implementations must not depend on iteration order that varies between runs.</li>
 *   <li><strong>Half-open ownership.</strong> A region owns {@code [minX, maxX)} x {@code [minZ, maxZ)}.
 *       The coordinate {@code x = maxX} belongs to the next region, never to this one.</li>
 *   <li><strong>Negative coordinates.</strong> Lookups work for any {@code int} coordinate, including
 *       values outside the world border; they simply report no owner.</li>
 *   <li><strong>Uncovered coordinates.</strong> Return {@link Optional#empty()}. Callers decide what an
 *       unassigned coordinate means; the lookup engine never invents an owner.</li>
 *   <li><strong>Overlaps.</strong> A correctly validated configuration has none. If an index is built
 *       from a configuration that does overlap, {@link #findRegion} still returns an answer (the
 *       deterministic first owner by minimum Z, then by configuration order) and
 *       {@link #findRegions} reports every owner, so the validator can describe the conflict.</li>
 * </ul>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link SlabRegionIndex} — the production implementation: coordinate compression over the X
 *       axis plus a binary search inside each slab, {@code O(log n)} per lookup.</li>
 *   <li>{@link LinearRegionIndex} — the reference implementation: a plain scan, used by the unit
 *       tests to cross-check the production index and by administrators to double-check a routing
 *       decision.</li>
 * </ul>
 *
 * <p>All implementations are immutable and safe for concurrent use.
 */
public interface RegionIndex {

	/**
	 * Finds the backend that owns a global block coordinate.
	 *
	 * @param x global block X
	 * @param z global block Z
	 * @return the owning backend, or empty when the coordinate is unassigned
	 */
	Optional<ServerDefinition> findRegion(int x, int z);

	/**
	 * Finds every backend that claims a global block coordinate.
	 *
	 * <p>Returns at most one entry for a valid configuration. A longer list means the configuration
	 * overlaps and an administrator must fix it, because two servers writing the same chunk corrupt
	 * the world.
	 *
	 * <p>The <em>set</em> of returned regions is part of the contract; their order is not. Each
	 * implementation is deterministic — {@link SlabRegionIndex} orders by geometry inside the slab,
	 * {@link LinearRegionIndex} by configuration order — but they may differ, and callers that care
	 * should sort. Only a configuration that failed validation ever returns more than one entry.
	 *
	 * @param x global block X
	 * @param z global block Z
	 * @return every owning backend, in a deterministic but implementation-defined order; empty when
	 *         unassigned
	 */
	List<ServerDefinition> findRegions(int x, int z);

	/**
	 * Tests whether a coordinate is owned by any backend.
	 *
	 * @param x global block X
	 * @param z global block Z
	 * @return {@code true} if at least one region contains the coordinate
	 */
	default boolean isAssigned(int x, int z) {
		return findRegion(x, z).isPresent();
	}

	/**
	 * Every region known to this index, in configuration order.
	 *
	 * @return an immutable list of regions
	 */
	List<ServerDefinition> regions();

	/**
	 * Number of internal lookup buckets.
	 *
	 * <p>Exposed for diagnostics: the slab index creates at most {@code 2n - 1} slabs for {@code n}
	 * regions, which is what makes the structure worth having, and a test asserts that bound.
	 *
	 * @return the number of buckets the index searches
	 */
	int bucketCount();

	/**
	 * Builds the production index for a set of regions.
	 *
	 * @param regions the regions to index
	 * @return a {@link SlabRegionIndex}
	 */
	static RegionIndex of(List<ServerDefinition> regions) {
		return SlabRegionIndex.build(regions);
	}

	/**
	 * Finds the region that owns a coordinate, clipped to the world border.
	 *
	 * <p>Coordinates outside the border are always unassigned, even if a (misconfigured, or
	 * deliberately oversized) region would otherwise contain them. The world border is the outer
	 * limit of the shared world, so honouring it here keeps the routing decision consistent with
	 * what players can actually reach.
	 *
	 * @param x global block X
	 * @param z global block Z
	 * @param border the world border
	 * @return the owning backend, or empty when the coordinate is outside the border or unassigned
	 */
	default Optional<ServerDefinition> findRegionWithin(int x, int z, RegionBounds border) {
		if (!border.contains(x, z)) {
			return Optional.empty();
		}
		return findRegion(x, z);
	}

	/**
	 * Human readable description of the index layout, for the {@code opendonut route} CLI and for
	 * startup logs.
	 *
	 * @return a multi-line summary
	 */
	default String describe() {
		return getClass().getSimpleName() + "[regions=" + regions().size() + ", buckets=" + bucketCount() + "]";
	}
}
