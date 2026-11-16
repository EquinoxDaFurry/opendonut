package net.opendonut.common.model;

/**
 * Block &lt;-&gt; chunk coordinate conversions.
 *
 * <h2>Why this class exists</h2>
 * OpenDonut region boundaries are defined in <em>block</em> coordinates, but they must line up with
 * chunk boundaries, because a chunk is the smallest unit Minecraft can generate, save, load and
 * modify. A region whose edge falls in the middle of a chunk would force the owning and the
 * neighbouring server to generate and write the same chunk, which is exactly what OpenDonut must
 * prevent.
 *
 * <h2>The coordinate rule</h2>
 * Minecraft divides the world into chunks of {@value #CHUNK_SIZE} blocks. Block coordinates map to
 * chunk coordinates using <em>floor</em> division, not truncating division. Java's {@code /}
 * operator truncates towards zero, which is wrong for negative coordinates, while {@code >>} on an
 * {@code int} is an arithmetic shift and therefore already a floor division by 16:
 *
 * <pre>
 *   block  x :  ...  -33 -32 | -17 -16 | -1  0 | 15 16 | 31 32 ...
 *   floorDiv  :      -3  -2  |  -2  -1  | -1  0 |  0  1 |  1  2
 *   truncated :      -2  -2  |  -1  -1  |  0  0 |  0  1 |  1  2   &lt;- wrong for negatives
 * </pre>
 *
 * Consequence: chunk {@code -1} covers blocks {@code -16 .. -1}, chunk {@code 0} covers blocks
 * {@code 0 .. 15}. The world origin sits at the <em>end</em> of chunk {@code -1}, not at the middle.
 *
 * <p>Region bounds in OpenDonut use the half-open interval {@code [min, max)} so that the block
 * coordinate {@code 0} belongs to exactly one region, and chunk-aligned bounds always satisfy
 * {@code min >> 4} and {@code max >> 4} being exact.
 */
public final class ChunkMath {

	/** Blocks per chunk edge. A chunk is a {@value #CHUNK_SIZE} x {@value #CHUNK_SIZE} column. */
	public static final int CHUNK_SIZE = 16;

	/** Bit mask that extracts the in-chunk offset of a block coordinate. */
	public static final int CHUNK_MASK = CHUNK_SIZE - 1;

	private ChunkMath() {
	}

	/**
	 * Converts a block coordinate to the chunk coordinate that contains it.
	 *
	 * @param blockCoord block coordinate on the X or Z axis, may be negative
	 * @return the chunk coordinate (floor division by 16)
	 */
	public static int blockToChunk(int blockCoord) {
		return blockCoord >> 4;
	}

	/**
	 * Converts a block coordinate to the chunk coordinate that contains it, as a {@code long}.
	 *
	 * <p>Use this when the coordinate comes from an untrusted or very large value: region bounds in
	 * OpenDonut are limited to a maximum of ±30,000,000 blocks, so an {@code int} is enough, but
	 * padding arithmetic for display or logging benefits from the wider type.
	 *
	 * @param blockCoord block coordinate, may be negative
	 * @return the chunk coordinate
	 */
	public static long blockToChunk(long blockCoord) {
		return Math.floorDiv(blockCoord, CHUNK_SIZE);
	}

	/**
	 * Returns the lowest block coordinate contained in the given chunk.
	 *
	 * @param chunkCoord chunk coordinate, may be negative
	 * @return the inclusive minimum block coordinate of that chunk
	 */
	public static int chunkToBlockMin(int chunkCoord) {
		return chunkCoord << 4;
	}

	/**
	 * Returns the first block coordinate <em>after</em> the given chunk.
	 *
	 * @param chunkCoord chunk coordinate, may be negative
	 * @return the exclusive maximum block coordinate of that chunk
	 */
	public static int chunkToBlockMaxExclusive(int chunkCoord) {
		return (chunkCoord << 4) + CHUNK_SIZE;
	}

	/**
	 * The offset of a block inside its chunk, always in {@code 0..15} — also for negative
	 * coordinates (block {@code -1} is offset {@value #CHUNK_MASK} of chunk {@code -1}).
	 *
	 * @param blockCoord block coordinate, may be negative
	 * @return the in-chunk offset
	 */
	public static int offsetInChunk(int blockCoord) {
		return blockCoord & CHUNK_MASK;
	}

	/**
	 * Tests whether a block coordinate sits exactly on a chunk boundary.
	 *
	 * @param blockCoord block coordinate, may be negative
	 * @return {@code true} if the coordinate is a multiple of {@value #CHUNK_SIZE}
	 */
	public static boolean isChunkAligned(int blockCoord) {
		return (blockCoord & CHUNK_MASK) == 0;
	}

	/**
	 * Tests whether a length (as opposed to a coordinate) spans whole chunks.
	 *
	 * @param length length in blocks
	 * @return {@code true} if the length is a positive multiple of {@value #CHUNK_SIZE}
	 */
	public static boolean isChunkAlignedLength(int length) {
		return length > 0 && (length & CHUNK_MASK) == 0;
	}

	/**
	 * Snaps a block coordinate down to the start of its chunk.
	 *
	 * @param blockCoord block coordinate, may be negative
	 * @return the smallest chunk-aligned coordinate that is {@code <= blockCoord}
	 */
	public static int floorToChunk(int blockCoord) {
		return chunkToBlockMin(blockToChunk(blockCoord));
	}

	/**
	 * Snaps a block coordinate up to the start of the next chunk boundary that is
	 * {@code >= blockCoord}. Coordinates already on a boundary are returned unchanged.
	 *
	 * @param blockCoord block coordinate, may be negative
	 * @return the smallest chunk-aligned coordinate that is {@code >= blockCoord}
	 */
	public static int ceilToChunk(int blockCoord) {
		return isChunkAligned(blockCoord) ? blockCoord : chunkToBlockMaxExclusive(blockToChunk(blockCoord));
	}
}
