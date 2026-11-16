package net.opendonut.common.region;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.opendonut.common.model.ServerDefinition;

/**
 * The reference implementation of {@link RegionIndex}: a plain linear scan in configuration order.
 *
 * <p>This is the implementation to read when you want to know what the lookup engine is supposed to
 * do. It is deliberately naive — {@code O(n)} per lookup, no clever data structure — so that it is
 * obviously correct, and it is used by the unit tests to cross-check {@link SlabRegionIndex} over
 * randomised region layouts.
 *
 * <p>It is also reachable at runtime through
 * {@link net.opendonut.common.model.OpenDonutConfig#linearIndex()}, which is useful when a routing
 * decision looks wrong: if both indexes agree, the index is not the problem.
 */
public final class LinearRegionIndex implements RegionIndex {

	private final List<ServerDefinition> regions;

	private LinearRegionIndex(List<ServerDefinition> regions) {
		this.regions = List.copyOf(regions);
	}

	/**
	 * Builds a scanning index.
	 *
	 * @param regions the regions to index, in configuration order
	 * @return the index
	 */
	public static LinearRegionIndex build(List<ServerDefinition> regions) {
		Objects.requireNonNull(regions, "regions");
		return new LinearRegionIndex(regions);
	}

	@Override
	public Optional<ServerDefinition> findRegion(int x, int z) {
		List<ServerDefinition> matches = findRegions(x, z);
		return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
	}

	@Override
	public List<ServerDefinition> findRegions(int x, int z) {
		List<ServerDefinition> matches = new ArrayList<>(2);
		for (ServerDefinition region : regions) {
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
		// A linear scan has no buckets; reporting the region count keeps diagnostics comparable.
		return regions.size();
	}
}
