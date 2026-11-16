package net.opendonut.common.model;

/**
 * An axis-aligned rectangular region in global block coordinates.
 *
 * <h2>Interval convention</h2>
 * Bounds are <strong>minimum-inclusive, maximum-exclusive</strong>: {@code [minX, maxX)} and
 * {@code [minZ, maxZ)}. A block at {@code x = maxX} belongs to the <em>next</em> region, never to
 * this one. This convention makes overlapping ownership impossible to express by accident and
 * matches how Minecraft itself treats chunk boundaries.
 *
 * <pre>
 *   region "west": X = [-100000, 0)      blocks -100000 .. -1
 *   region "east": X = [0, 100000)       blocks 0 .. 99999
 *   block x = -1  -> west
 *   block x =  0  -> east     (no ambiguity, no overlap)
 * </pre>
 *
 * <p>Bounds are expected to be chunk aligned, see {@link #isChunkAligned()}. A misaligned region is
 * still representable — the configuration validator reports it rather than this class rejecting it —
 * because administrators occasionally paste hand-written coordinates and deserve a precise
 * diagnostic instead of a stack trace.
 *
 * @param minX inclusive minimum X (block coordinate)
 * @param maxX exclusive maximum X (block coordinate)
 * @param minZ inclusive minimum Z (block coordinate)
 * @param maxZ exclusive maximum Z (block coordinate)
 */
public record RegionBounds(int minX, int maxX, int minZ, int maxZ) {

	/**
	 * Validates that the rectangle is not inverted.
	 *
	 * @throws IllegalArgumentException if {@code maxX < minX} or {@code maxZ < minZ}
	 */
	public RegionBounds {
		if (maxX < minX) {
			throw new IllegalArgumentException("maxX (" + maxX + ") must be >= minX (" + minX + ")");
		}
		if (maxZ < minZ) {
			throw new IllegalArgumentException("maxZ (" + maxZ + ") must be >= minZ (" + minZ + ")");
		}
	}

	/**
	 * Tests whether a block coordinate pair is owned by this region.
	 *
	 * @param x global block X
	 * @param z global block Z
	 * @return {@code true} if {@code minX <= x < maxX} and {@code minZ <= z < maxZ}
	 */
	public boolean contains(int x, int z) {
		return x >= minX && x < maxX && z >= minZ && z < maxZ;
	}

	/**
	 * Tests whether this region owns any part of a chunk.
	 *
	 * <p>For chunk-aligned bounds this is equivalent to the chunk lying entirely inside the region.
	 * For misaligned bounds a shared chunk is reported as owned by both regions, which is precisely
	 * the situation the configuration validator flags.
	 *
	 * @param chunkX chunk X coordinate
	 * @param chunkZ chunk Z coordinate
	 * @return {@code true} if the chunk rectangles intersect
	 */
	public boolean containsChunk(int chunkX, int chunkZ) {
		int chunkMinX = ChunkMath.chunkToBlockMin(chunkX);
		int chunkMinZ = ChunkMath.chunkToBlockMin(chunkZ);
		int chunkMaxX = chunkMinX + ChunkMath.CHUNK_SIZE;
		int chunkMaxZ = chunkMinZ + ChunkMath.CHUNK_SIZE;
		return chunkMinX < maxX && chunkMaxX > minX && chunkMinZ < maxZ && chunkMaxZ > minZ;
	}

	/** @return width in blocks along X */
	public int width() {
		return maxX - minX;
	}

	/** @return depth in blocks along Z */
	public int depth() {
		return maxZ - minZ;
	}

	/** @return area in blocks squared */
	public long area() {
		return (long) width() * (long) depth();
	}

	/** @return {@code true} if the region covers no block at all */
	public boolean isEmpty() {
		return minX >= maxX || minZ >= maxZ;
	}

	/**
	 * Tests whether all four edges sit on chunk boundaries, so that no chunk is shared with a
	 * neighbour.
	 *
	 * @return {@code true} if every edge is a multiple of {@value ChunkMath#CHUNK_SIZE}
	 */
	public boolean isChunkAligned() {
		return ChunkMath.isChunkAligned(minX)
				&& ChunkMath.isChunkAligned(maxX)
				&& ChunkMath.isChunkAligned(minZ)
				&& ChunkMath.isChunkAligned(maxZ);
	}

	/**
	 * Tests whether two regions share at least one block.
	 *
	 * @param other the other region
	 * @return {@code true} if the rectangles intersect
	 */
	public boolean intersects(RegionBounds other) {
		return minX < other.maxX && other.minX < maxX && minZ < other.maxZ && other.minZ < maxZ;
	}

	/**
	 * Computes the number of blocks claimed by both regions.
	 *
	 * @param other the other region
	 * @return the intersection area, {@code 0} when the regions are disjoint
	 */
	public long intersectionArea(RegionBounds other) {
		long overlapX = Math.min(maxX, other.maxX) - Math.max(minX, other.minX);
		long overlapZ = Math.min(maxZ, other.maxZ) - Math.max(minZ, other.minZ);
		if (overlapX <= 0 || overlapZ <= 0) {
			return 0L;
		}
		return overlapX * overlapZ;
	}

	/**
	 * Returns the intersection of two regions.
	 *
	 * @param other the other region
	 * @return the overlapping rectangle, or {@code null} if the regions are disjoint
	 */
	public RegionBounds intersection(RegionBounds other) {
		int overlapMinX = Math.max(minX, other.minX);
		int overlapMaxX = Math.min(maxX, other.maxX);
		int overlapMinZ = Math.max(minZ, other.minZ);
		int overlapMaxZ = Math.min(maxZ, other.maxZ);
		// Emptiness has to be tested before constructing: disjoint inputs produce inverted values, and the
		// record's own validation would (correctly) reject those rather than report "no intersection".
		if (overlapMaxX <= overlapMinX || overlapMaxZ <= overlapMinZ) {
			return null;
		}
		return new RegionBounds(overlapMinX, overlapMaxX, overlapMinZ, overlapMaxZ);
	}

	/** @return inclusive minimum chunk X */
	public int chunkMinX() {
		return ChunkMath.blockToChunk(minX);
	}

	/** @return exclusive maximum chunk X */
	public int chunkMaxX() {
		return ChunkMath.blockToChunk(maxX);
	}

	/** @return inclusive minimum chunk Z */
	public int chunkMinZ() {
		return ChunkMath.blockToChunk(minZ);
	}

	/** @return exclusive maximum chunk Z */
	public int chunkMaxZ() {
		return ChunkMath.blockToChunk(maxZ);
	}

	/**
	 * Shrinks the region inwards by the given number of blocks on every side.
	 *
	 * <p>Used by the transfer logic to define a "safe interior": a player who is pushed back after a
	 * failed transfer is placed this far inside their own region so that the very next movement does
	 * not immediately re-trigger a transfer request.
	 *
	 * @param margin blocks to remove from each side; values that would invert an axis are clamped
	 * @return the shrunk region
	 */
	public RegionBounds shrink(int margin) {
		int safeMarginX = Math.min(margin, Math.max(0, width() / 2 - 1));
		int safeMarginZ = Math.min(margin, Math.max(0, depth() / 2 - 1));
		return new RegionBounds(minX + safeMarginX, maxX - safeMarginX, minZ + safeMarginZ, maxZ - safeMarginZ);
	}

	/**
	 * Formats the region the way administrators write it in the YAML file.
	 *
	 * @return a human readable description such as {@code X=[-100000,0) Z=[-100000,100000)}
	 */
	public String describe() {
		return "X=[" + minX + "," + maxX + ") Z=[" + minZ + "," + maxZ + ")";
	}

	@Override
	public String toString() {
		return describe();
	}
}
