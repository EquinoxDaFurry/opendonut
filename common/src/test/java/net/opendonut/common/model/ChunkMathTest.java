package net.opendonut.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Block &lt;-&gt; chunk conversion, with the emphasis on negative coordinates.
 *
 * <p>This is the smallest class in OpenDonut and the one that most easily produces a world-corrupting
 * bug: an off-by-one here means a region boundary lands one block inside a chunk, and two servers then
 * generate the same chunk. The negative-coordinate cases are therefore spelled out one by one rather
 * than derived from a formula in the test, so that a change in behaviour is visible in the diff.
 */
class ChunkMathTest {

	@Nested
	@DisplayName("blockToChunk uses floor division")
	class BlockToChunk {

		@ParameterizedTest(name = "block {0} is in chunk {1}")
		@CsvSource({
				// Positive coordinates.
				"0, 0",
				"1, 0",
				"15, 0",
				"16, 1",
				"31, 1",
				"32, 2",
				// Negative coordinates: -1 is the LAST block of chunk -1, not the first of chunk 0.
				"-1, -1",
				"-15, -1",
				"-16, -1",
				"-17, -2",
				"-32, -2",
				"-33, -3",
				// Far from the origin, both directions.
				"100000, 6250",
				"-100000, -6250",
				"2147483647, 134217727",
		})
		void mapsBlockToChunk(int block, int expectedChunk) {
			assertEquals(expectedChunk, ChunkMath.blockToChunk(block));
		}

		@Test
		@DisplayName("matches Math.floorDiv for every block of the chunks around the origin")
		void agreesWithFloorDivision() {
			for (int block = -64; block <= 64; block++) {
				assertEquals(Math.floorDiv(block, 16L), (long) ChunkMath.blockToChunk(block),
						"block " + block);
				assertEquals(Math.floorDiv(block, 16L), ChunkMath.blockToChunk((long) block),
						"long block " + block);
			}
		}

		@Test
		@DisplayName("truncating division would be wrong, which is why it is not used")
		void demonstratesWhyTruncationIsWrong() {
			// The naive implementation. Kept as an executable statement of the bug that was avoided.
			int naive = -1 / 16;
			assertEquals(0, naive, "Java's / truncates towards zero");
			assertEquals(-1, ChunkMath.blockToChunk(-1), "floor division is what Minecraft uses");
		}
	}

	@Nested
	@DisplayName("chunk to block conversion")
	class ChunkToBlock {

		@Test
		@DisplayName("chunk -1 covers blocks -16..-1")
		void negativeChunkCoversNegativeBlocks() {
			assertEquals(-16, ChunkMath.chunkToBlockMin(-1));
			assertEquals(0, ChunkMath.chunkToBlockMaxExclusive(-1));
		}

		@Test
		@DisplayName("min and max-exclusive are consistent with blockToChunk")
		void roundTrips() {
			for (int chunk = -100; chunk <= 100; chunk++) {
				int min = ChunkMath.chunkToBlockMin(chunk);
				int max = ChunkMath.chunkToBlockMaxExclusive(chunk);
				assertEquals(16, max - min, "chunk " + chunk + " must span 16 blocks");
				assertEquals(chunk, ChunkMath.blockToChunk(min), "min block of chunk " + chunk);
				assertEquals(chunk, ChunkMath.blockToChunk(max - 1), "last block of chunk " + chunk);
				assertEquals(chunk + 1, ChunkMath.blockToChunk(max), "block after chunk " + chunk);
			}
		}
	}

	@Nested
	@DisplayName("offsets and alignment")
	class Offsets {

		@Test
		@DisplayName("offsetInChunk is always 0..15, also for negative coordinates")
		void offsetIsNeverNegative() {
			for (int block = -40; block <= 40; block++) {
				int offset = ChunkMath.offsetInChunk(block);
				assertTrue(offset >= 0 && offset <= 15, "offset of block " + block + " was " + offset);
				assertEquals(block - ChunkMath.chunkToBlockMin(ChunkMath.blockToChunk(block)), offset,
						"offset must be block minus the chunk's first block");
			}
		}

		@Test
		@DisplayName("alignment is true exactly on multiples of 16, including negative ones")
		void alignment() {
			assertTrue(ChunkMath.isChunkAligned(0));
			assertTrue(ChunkMath.isChunkAligned(16));
			assertTrue(ChunkMath.isChunkAligned(-16));
			assertTrue(ChunkMath.isChunkAligned(-100000));
			assertTrue(ChunkMath.isChunkAligned(100000));
			assertFalse(ChunkMath.isChunkAligned(-1));
			assertFalse(ChunkMath.isChunkAligned(8));
			assertFalse(ChunkMath.isChunkAligned(99999));
		}

		@Test
		@DisplayName("a length is aligned only when positive and a multiple of 16")
		void alignedLength() {
			assertTrue(ChunkMath.isChunkAlignedLength(16));
			assertTrue(ChunkMath.isChunkAlignedLength(200000));
			assertFalse(ChunkMath.isChunkAlignedLength(0), "a zero-length region is never aligned");
			assertFalse(ChunkMath.isChunkAlignedLength(-16));
			assertFalse(ChunkMath.isChunkAlignedLength(15));
		}

		@Test
		@DisplayName("floor and ceil snap outwards as documented")
		void floorAndCeil() {
			assertEquals(0, ChunkMath.floorToChunk(0));
			assertEquals(-16, ChunkMath.floorToChunk(-1));
			assertEquals(-16, ChunkMath.floorToChunk(-16));
			assertEquals(-32, ChunkMath.floorToChunk(-17));

			assertEquals(0, ChunkMath.ceilToChunk(0), "an aligned coordinate is returned unchanged");
			assertEquals(0, ChunkMath.ceilToChunk(-1));
			assertEquals(-16, ChunkMath.ceilToChunk(-16));
			assertEquals(16, ChunkMath.ceilToChunk(1));
			assertEquals(16, ChunkMath.ceilToChunk(15));
		}

		@Test
		@DisplayName("floor then ceil of a coordinate brackets it")
		void bracketsTheCoordinate() {
			for (int block = -50; block <= 50; block++) {
				int floor = ChunkMath.floorToChunk(block);
				int ceil = ChunkMath.ceilToChunk(block);
				assertTrue(floor <= block, "floor " + floor + " must not exceed " + block);
				assertTrue(ceil >= block, "ceil " + ceil + " must not be below " + block);
				assertTrue(ceil - floor <= 16, "the bracket must not exceed one chunk");
			}
		}
	}
}
