package net.opendonut.common.model;

/**
 * The square world border shared by every backend.
 *
 * <p>OpenDonut requires a <em>square</em> border so that "the area covered by servers" is a simple
 * rectangle and coverage can be computed exactly. The border is described the way
 * {@code /worldborder center} and {@code /worldborder set} describe it — a centre and a size — and
 * is converted here into half-open bounds:
 *
 * <pre>
 *   minX = centerX - size / 2        maxX = centerX + size / 2
 *   minZ = centerZ - size / 2        maxZ = centerZ + size / 2
 * </pre>
 *
 * <p>The configuration validator requires an even size (so the centre is a whole block) that is a
 * multiple of {@value ChunkMath#CHUNK_SIZE}, and a chunk-aligned centre, so that every edge of the
 * border also falls on a chunk boundary.
 *
 * @param centerX border centre on the X axis
 * @param centerZ border centre on the Z axis
 * @param size total edge length of the square, in blocks
 */
public record WorldBorderDefinition(int centerX, int centerZ, int size) {

	/**
	 * Validates the border.
	 *
	 * @throws IllegalArgumentException if the size is not positive
	 */
	public WorldBorderDefinition {
		if (size <= 0) {
			throw new IllegalArgumentException("border size must be a positive number of blocks, got " + size);
		}
	}

	/** @return inclusive minimum global X of the world */
	public int minX() {
		return centerX - size / 2;
	}

	/** @return exclusive maximum global X of the world */
	public int maxX() {
		return centerX + size / 2;
	}

	/** @return inclusive minimum global Z of the world */
	public int minZ() {
		return centerZ - size / 2;
	}

	/** @return exclusive maximum global Z of the world */
	public int maxZ() {
		return centerZ + size / 2;
	}

	/**
	 * Tests whether a block coordinate lies inside the world border.
	 *
	 * @param x global block X
	 * @param z global block Z
	 * @return {@code true} if the coordinate is inside the border
	 */
	public boolean contains(int x, int z) {
		return x >= minX() && x < maxX() && z >= minZ() && z < maxZ();
	}

	/**
	 * The border as a {@link RegionBounds}, so it can be compared with server regions using the same
	 * interval arithmetic.
	 *
	 * @return the border rectangle
	 */
	public RegionBounds asBounds() {
		return new RegionBounds(minX(), maxX(), minZ(), maxZ());
	}

	/**
	 * Tests whether the border edges and centre line up with chunk boundaries.
	 *
	 * @return {@code true} if the size is chunk aligned and the centre is chunk aligned
	 */
	public boolean isChunkAligned() {
		return ChunkMath.isChunkAlignedLength(size)
				&& ChunkMath.isChunkAligned(minX())
				&& ChunkMath.isChunkAligned(minZ())
				&& ChunkMath.isChunkAligned(maxX())
				&& ChunkMath.isChunkAligned(maxZ());
	}

	/**
	 * Human readable summary for logs and reports.
	 *
	 * @return for example {@code center=(0,0) size=200000 X=[-100000,100000) Z=[-100000,100000)}
	 */
	public String describe() {
		return "center=(" + centerX + "," + centerZ + ") size=" + size + " "
				+ "X=[" + minX() + "," + maxX() + ") Z=[" + minZ() + "," + maxZ() + ")";
	}

	@Override
	public String toString() {
		return describe();
	}
}
