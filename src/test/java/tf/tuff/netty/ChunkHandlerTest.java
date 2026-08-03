package tf.tuff.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ResourceLeakDetector;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class ChunkHandlerTest {
	private static ResourceLeakDetector.Level previousLeakDetectionLevel;

	@BeforeAll
	static void enableParanoidLeakDetection() {
		previousLeakDetectionLevel = ResourceLeakDetector.getLevel();
		ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
	}

	@AfterAll
	static void restoreLeakDetectionLevel() {
		ResourceLeakDetector.setLevel(previousLeakDetectionLevel);
	}

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

	@Test
	void releasesOriginalBufferAfterImmediateChunkTransformation() {
		byte[] viaData = {11, 12, 13};
		TestChunkHandler handler = new TestChunkHandler(viaData, null);
		EmbeddedChannel channel = new EmbeddedChannel(handler);
		ByteBuf original = chunkPacket(4, -7);
		byte[] originalData = ByteBufUtil.getBytes(original);

		assertTrue(channel.writeOutbound(original));

		assertEquals(0, original.refCnt());
		assertOutboundBytes(channel, concatenate(originalData, viaData));
		assertFalse(channel.finishAndReleaseAll());
	}

	@Test
	void releasesOriginalBufferAfterDelayedChunkTransformation() {
		byte[] viaData = {21, 22};
		TestChunkHandler handler = new TestChunkHandler(null, viaData);
		EmbeddedChannel channel = new EmbeddedChannel(handler);
		ByteBuf original = chunkPacket(8, 9);
		byte[] originalData = ByteBufUtil.getBytes(original);

		channel.writeOneOutbound(original);
		channel.runPendingTasks();
		channel.flushOutbound();

		assertEquals(0, original.refCnt());
		assertOutboundBytes(channel, concatenate(originalData, viaData));
		assertFalse(channel.finishAndReleaseAll());
	}

	@Test
	void transfersOriginalBufferDownstreamAfterChunkTimeout() {
		TestChunkHandler handler = new TestChunkHandler(null, null, 0);
		EmbeddedChannel channel = new EmbeddedChannel(handler);
		ByteBuf original = chunkPacket(10, 11);

		channel.writeOneOutbound(original);
		channel.runScheduledPendingTasks();
		channel.runPendingTasks();
		channel.flushOutbound();

		ByteBuf outbound = channel.readOutbound();
		assertSame(original, outbound);
		assertEquals(1, original.refCnt());
		outbound.release();
		assertEquals(0, original.refCnt());
		assertFalse(channel.finishAndReleaseAll());
	}

	@Test
	void releasesOriginalBufferAfterBlockDataTransformation() {
		TestChunkHandler handler = new TestChunkHandler(null, null);
		EmbeddedChannel channel = new EmbeddedChannel(handler);
		ChannelHandlerContext context = channel.pipeline().context(handler);
		ByteBuf original = Unpooled.wrappedBuffer(new byte[]{1, 2, 3});
		byte[] viaData = {4, 5};

		handler.writeWithViaOnly(context, original, channel.newPromise(), viaData);
		channel.flushOutbound();

		assertEquals(0, original.refCnt());
		assertOutboundBytes(channel, new byte[]{1, 2, 3, 4, 5});
		assertFalse(channel.finishAndReleaseAll());
	}

	@Test
	void releasesOriginalBufferWhenReplacementConstructionFails() {
		TestChunkHandler handler = new TestChunkHandler(null, null);
		EmbeddedChannel channel = new EmbeddedChannel(handler);
		channel.config().setAllocator(new FailingByteBufAllocator());
		ChannelHandlerContext context = channel.pipeline().context(handler);
		ByteBuf original = Unpooled.wrappedBuffer(new byte[]{1, 2, 3});
		ChannelPromise promise = channel.newPromise();

		handler.writeWithViaOnly(context, original, promise, new byte[]{4, 5});

		assertEquals(0, original.refCnt());
		assertTrue(promise.isDone());
		assertFalse(promise.isSuccess());
		assertFalse(channel.finishAndReleaseAll());
	}

	@Test
	void releasesBuffersWhenReplacementWriteFails() {
		TestChunkHandler handler = new TestChunkHandler(null, null);
		EmbeddedChannel channel = new EmbeddedChannel(handler);
		RecordingByteBufAllocator allocator = new RecordingByteBufAllocator();
		channel.config().setAllocator(allocator);
		EmbeddedChannel foreignChannel = new EmbeddedChannel();
		ChannelHandlerContext context = channel.pipeline().context(handler);
		ByteBuf original = Unpooled.wrappedBuffer(new byte[]{1, 2, 3});
		ChannelPromise foreignPromise = foreignChannel.newPromise();

		handler.writeWithViaOnly(context, original, foreignPromise, new byte[]{4, 5});

		ByteBuf replacement = allocator.onlyAllocation();
		// ctx.write rejects the foreign promise and releases the replacement itself. Only the
		// allocator's own reference may remain; a second release by the handler would drop it to 0.
		assertEquals(1, replacement.refCnt());
		replacement.release();

		assertEquals(0, original.refCnt());
		assertTrue(foreignPromise.isDone());
		assertFalse(foreignPromise.isSuccess());
		assertFalse(channel.finishAndReleaseAll());
		assertFalse(foreignChannel.finishAndReleaseAll());
	}

	@Test
	void releasesQueuedBufferWhenHandlerIsRemoved() {
		TestChunkHandler handler = new TestChunkHandler(null, null);
		EmbeddedChannel channel = new EmbeddedChannel(handler);
		ByteBuf original = chunkPacket(12, 13);
		ChannelPromise promise = channel.newPromise();

		channel.pipeline().write(original, promise);
		channel.pipeline().remove(handler);

		assertEquals(0, original.refCnt());
		assertTrue(promise.isDone());
		assertFalse(promise.isSuccess());
		assertFalse(channel.finishAndReleaseAll());
	}

	private static ByteBuf chunkPacket(int chunkX, int chunkZ) {
		return Unpooled.buffer(9)
			.writeByte(0x20)
			.writeInt(chunkX)
			.writeInt(chunkZ);
	}

	private static void assertOutboundBytes(EmbeddedChannel channel, byte[] expected) {
		ByteBuf outbound = channel.readOutbound();
		assertNotNull(outbound);
		try {
			assertArrayEquals(expected, ByteBufUtil.getBytes(outbound));
		} finally {
			outbound.release();
		}
	}

	private static byte[] concatenate(byte[] first, byte[] second) {
		byte[] result = new byte[first.length + second.length];
		System.arraycopy(first, 0, result, 0, first.length);
		System.arraycopy(second, 0, result, first.length, second.length);
		return result;
	}

	private static Player player() {
		UUID playerId = UUID.randomUUID();
		return (Player) Proxy.newProxyInstance(
			Player.class.getClassLoader(),
			new Class<?>[]{Player.class},
			(proxy, method, args) -> {
				if (method.getName().equals("getUniqueId")) return playerId;
				if (method.getName().equals("toString")) return "ChunkHandlerTestPlayer";
				if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
				if (method.getName().equals("equals")) return proxy == args[0];
				return null;
			}
		);
	}

	private static final class TestChunkHandler extends ChunkHandler {
		private final byte[] immediateData;
		private final byte[] completedData;

		private TestChunkHandler(byte[] immediateData, byte[] completedData) {
			this(immediateData, completedData, 500);
		}

		private TestChunkHandler(byte[] immediateData, byte[] completedData, long timeoutMs) {
			super(null, null, player(), timeoutMs);
			this.immediateData = immediateData;
			this.completedData = completedData;
		}

		@Override
		boolean isViaActive() {
			return true;
		}

		@Override
		byte[] getViaDataForChunk(int chunkX, int chunkZ) {
			return immediateData;
		}

		@Override
		void requestViaCache(int chunkX, int chunkZ, long key) {
			if (completedData != null) {
				completeViaCache(key, completedData);
			}
		}
	}

	/** Unpooled allocator that can be specialised to model allocation and ownership failures. */
	private static class TestByteBufAllocator extends AbstractByteBufAllocator {
		private TestByteBufAllocator() {
			super(false);
		}

		@Override
		protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
			return Unpooled.buffer(initialCapacity, maxCapacity);
		}

		@Override
		protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
			return Unpooled.directBuffer(initialCapacity, maxCapacity);
		}

		@Override
		public boolean isDirectBufferPooled() {
			return false;
		}
	}

	/** Fails every allocation, so replacement construction fails the way it can in production. */
	private static final class FailingByteBufAllocator extends TestByteBufAllocator {
		@Override
		public ByteBuf buffer(int initialCapacity) {
			throw new IllegalStateException("allocation refused");
		}
	}

	/**
	 * Keeps a reference to every buffer it allocates. The extra retain makes an over-release
	 * observable: safeRelease swallows the IllegalReferenceCountException, but the reference count
	 * still drops below the one reference this allocator holds.
	 */
	private static final class RecordingByteBufAllocator extends TestByteBufAllocator {
		private final List<ByteBuf> allocated = new ArrayList<>();

		@Override
		public ByteBuf buffer(int initialCapacity) {
			ByteBuf buf = super.buffer(initialCapacity);
			allocated.add(buf.retain());
			return buf;
		}

		private ByteBuf onlyAllocation() {
			assertEquals(1, allocated.size());
			return allocated.get(0);
		}
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
