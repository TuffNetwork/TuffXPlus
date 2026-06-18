package tf.tuff.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChunkHandlerTest {

	@Test
	void decodesSingleBlockChangePositionWithNegativeCoordinates() {
		long packed = packBlockPosition(-21, -64, 37);

		ChunkHandler.BlockChangePosition position = ChunkHandler.decodeSingleBlockChangePosition(packed);

		assertEquals(-21, position.x());
		assertEquals(-64, position.y());
		assertEquals(37, position.z());
	}

	@Test
	void decodesSectionBlockChangePositionBelowYZero() {
		long sectionPosition = packSectionPosition(12, -5, -9);
		long entry = packSectionEntry(3, 11, 7, 8123);

		ChunkHandler.BlockChangePosition position = ChunkHandler.decodeMultiBlockChangePosition(sectionPosition, entry);

		assertEquals((12 << 4) + 3, position.x());
		assertEquals((-5 << 4) + 7, position.y());
		assertEquals((-9 << 4) + 11, position.z());
	}

	private static long packBlockPosition(int x, int y, int z) {
		return ((long) x & 0x3FFFFFFL) << 38
			| ((long) z & 0x3FFFFFFL) << 12
			| ((long) y & 0xFFFL);
	}

	private static long packSectionPosition(int x, int y, int z) {
		return ((long) x & 0x3FFFFFL) << 42
			| ((long) z & 0x3FFFFFL) << 20
			| ((long) y & 0xFFFFFL);
	}

	private static long packSectionEntry(int localX, int localZ, int localY, int blockStateId) {
		int localPosition = ((localX & 0xF) << 8) | ((localZ & 0xF) << 4) | (localY & 0xF);
		return ((long) blockStateId << 12) | (localPosition & 0xFFFL);
	}
}
