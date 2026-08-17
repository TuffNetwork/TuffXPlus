package tf.tuff.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import org.bukkit.World;
import org.bukkit.entity.Player;
import tf.tuff.viablocks.CustomBlockListener;
import tf.tuff.y0.Y0Plugin;
import tf.tuff.util.SchedulerCompat;

import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ChunkHandler extends ChannelOutboundHandlerAdapter {

	private final CustomBlockListener viaBlocks;
	private final Y0Plugin y0;
	private final Player player;
	private final UUID playerId;
	private final Map<Long, QueuedPacket> queue = new ConcurrentHashMap<>();
	private final AtomicLong nextRequestId = new AtomicLong();
	private final long timeoutMs;
	private volatile ChannelHandlerContext ctx;

	private static final long TIMEOUT_MS = 500;

	static record BlockChangePosition(int x, int y, int z) {}

	public ChunkHandler(CustomBlockListener viaBlocks, Y0Plugin y0, Player player) {
		this(viaBlocks, y0, player, TIMEOUT_MS);
	}

	ChunkHandler(CustomBlockListener viaBlocks, Y0Plugin y0, Player player, long timeoutMs) {
		this.viaBlocks = viaBlocks;
		this.y0 = y0;
		this.player = player;
		this.playerId = player.getUniqueId();
		this.timeoutMs = timeoutMs;
	}

	private static class QueuedPacket {
		final ByteBuf buf;
		final ChannelPromise promise;
		volatile boolean viaReady;
		volatile boolean y0Ready;
		volatile byte[] viaData;
		volatile byte[] y0Data;

		QueuedPacket(ByteBuf buf, ChannelPromise promise) {
			this.buf = buf;
			this.promise = promise;
		}
	}

	/**
	 * Single-claim guard for one in-flight packet. The first caller to claim it owns the buffer and
	 * the promise; every later caller must leave both alone.
	 */
	private static final class PacketOwnership {
		final ByteBuf buf;
		final ChannelPromise promise;
		private final AtomicBoolean unclaimed = new AtomicBoolean(true);

		PacketOwnership(ByteBuf buf, ChannelPromise promise) {
			this.buf = buf;
			this.promise = promise;
		}

		boolean claim() {
			return unclaimed.compareAndSet(true, false);
		}

		void failIfUnclaimed(Throwable error) {
			if (claim()) {
				failAndRelease(buf, promise, error);
			}
		}
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		this.ctx = ctx;
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		if (!(msg instanceof ByteBuf)) {
			super.write(ctx, msg, promise);
			return;
		}

		ByteBuf buf = (ByteBuf) msg;
		buf.markReaderIndex();

		try {
			int packetId = readVarInt(buf);

			if (packetId == 0x20) {
				handleChunkPacket(ctx, buf, promise);
				return;
			}

			if (packetId == 0x0B && isViaActive()) {
				handleBlockChange(ctx, buf, promise);
				return;
			}

			if (packetId == 0x10 && isViaActive()) {
				handleMultiBlockChange(ctx, buf, promise);
				return;
			}

		} catch (Exception e) {
		} finally {
			if (buf.refCnt() > 0 && msg == buf) {
				buf.resetReaderIndex();
			}
		}

		super.write(ctx, msg, promise);
	}

	boolean isViaActive() {
		return viaBlocks != null
			&& viaBlocks.plugin.isEnabled()
			&& viaBlocks.plugin.isPlayerEnabled(player);
	}

	byte[] getViaDataForChunk(int chunkX, int chunkZ) {
		return viaBlocks.getExtraDataForChunk(player.getWorld().getName(), chunkX, chunkZ);
	}

	private void handleChunkPacket(ChannelHandlerContext ctx, ByteBuf buf, ChannelPromise promise) throws Exception {
		int chunkX = buf.readInt();
		int chunkZ = buf.readInt();
		buf.resetReaderIndex();

		boolean viaActive = isViaActive();
		byte[] viaData = viaActive ? getViaDataForChunk(chunkX, chunkZ) : null;
		byte[] y0Data = y0 != null ? y0.getY0DataForChunk(player, chunkX, chunkZ) : null;

		boolean needY0 = y0 != null && y0.isPlayerReady(player);

		boolean viaReady = !viaActive || viaData != null;
		boolean y0Ready = !needY0 || y0Data != null;

		if (viaReady && y0Ready) {
			writeWithData(ctx, buf, promise, viaData, y0Data);
			return;
		}

		long requestId = nextRequestId.getAndIncrement();
		QueuedPacket q = new QueuedPacket(buf, promise);
		q.viaReady = viaReady;
		q.y0Ready = y0Ready;
		q.viaData = viaData;
		q.y0Data = y0Data;
		queue.put(requestId, q);

		try {
			if (!viaReady) {
				requestViaCache(chunkX, chunkZ, requestId);
			}
			if (!y0Ready) {
				requestY0Cache(chunkX, chunkZ, requestId);
			}

			scheduleTimeout(requestId, q);
		} catch (Throwable error) {
			removeAndFail(requestId, q, error);
		}
	}

	private void handleBlockChange(ChannelHandlerContext ctx, ByteBuf buf, ChannelPromise promise) throws Exception {
		BlockChangePosition position = decodeSingleBlockChangePosition(buf.getLong(buf.readerIndex()));
		resolveViaDataOnRegionThread(ctx, buf, promise, player.getWorld(), position.x >> 4, position.z >> 4, () -> {
			World world = player.getWorld();
			if (!world.isChunkLoaded(position.x >> 4, position.z >> 4)) {
				return null;
			}
			return viaBlocks.getExtraDataForSingleBlock(world, position.x, position.y, position.z);
		});
	}

	private void handleMultiBlockChange(ChannelHandlerContext ctx, ByteBuf buf, ChannelPromise promise) throws Exception {
		buf.resetReaderIndex();
		buf.skipBytes(varIntLen(buf));
		long chunkSectionPos = buf.readLong();
		int cx = decodeSectionCoordX(chunkSectionPos);
		int cz = decodeSectionCoordZ(chunkSectionPos);
		buf.readBoolean();
		int count = readVarInt(buf);
		java.util.List<Long> locs = new java.util.ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			BlockChangePosition position = decodeMultiBlockChangePosition(chunkSectionPos, readVarLong(buf));
			locs.add(viaBlocks.packLocation(position.x, position.y, position.z));
		}

		resolveViaDataOnRegionThread(ctx, buf, promise, player.getWorld(), cx, cz, () -> viaBlocks.getExtraDataForMultiBlock(player.getWorld(), locs));
	}

	private void resolveViaDataOnRegionThread(ChannelHandlerContext ctx, ByteBuf buf, ChannelPromise promise,
											  World world, int chunkX, int chunkZ,
											  java.util.concurrent.Callable<byte[]> supplier) {
		// The region task may run synchronously, so only the first claimant may write or release the
		// packet. Every other path must leave the buffer alone.
		PacketOwnership packet = new PacketOwnership(buf, promise);
		dispatchOrFail(packet, () -> SchedulerCompat.runRegion(viaBlocks.plugin.plugin, world, chunkX, chunkZ,
			() -> writeResolvedViaData(ctx, packet, resolveQuietly(supplier))));
	}

	/** Runs a scheduling step, failing the packet when the step throws before anything claimed it. */
	private static void dispatchOrFail(PacketOwnership packet, Runnable step) {
		try {
			step.run();
		} catch (Throwable error) {
			packet.failIfUnclaimed(error);
		}
	}

	private byte[] resolveQuietly(java.util.concurrent.Callable<byte[]> supplier) {
		try {
			return player.isOnline() && isViaActive() ? supplier.call() : null;
		} catch (Exception ignored) {
			return null;
		}
	}

	private void writeResolvedViaData(ChannelHandlerContext ctx, PacketOwnership packet, byte[] viaData) {
		ChannelHandlerContext currentCtx = this.ctx != null ? this.ctx : ctx;
		dispatchOrFail(packet, () -> currentCtx.channel().eventLoop()
			.execute(() -> writeClaimed(currentCtx, packet, viaData)));
	}

	/** Runs on the event loop, so it fails the packet itself instead of letting the throw escape. */
	private void writeClaimed(ChannelHandlerContext ctx, PacketOwnership packet, byte[] viaData) {
		if (!packet.claim()) {
			return;
		}
		try {
			packet.buf.resetReaderIndex();
			writeWithData(ctx, packet.buf, packet.promise, viaData, null);
		} catch (Throwable error) {
			failAndRelease(packet.buf, packet.promise, error);
		}
	}

	void requestViaCache(int cx, int cz, long requestId) {
		requestCacheOnRegionThread(cx, cz, requestId, () ->
			viaBlocks.cacheChunkWithCallback(player.getWorld(), cx, cz, data -> completeViaCache(requestId, data)));
	}

	private void requestY0Cache(int cx, int cz, long requestId) {
		requestCacheOnRegionThread(cx, cz, requestId, () ->
			y0.cacheChunkWithCallback(player, cx, cz, data -> completeY0Cache(requestId, data)));
	}

	/** Runs a cache request on the chunk's region thread, writing the queued packet as-is if the player left. */
	private void requestCacheOnRegionThread(int cx, int cz, long requestId, Runnable request) {
		SchedulerCompat.runRegion(viaBlocks.plugin.plugin, player.getWorld(), cx, cz, () -> {
			if (!player.isOnline()) {
				dequeueAndWrite(requestId);
				return;
			}
			request.run();
		});
	}

	void completeViaCache(long requestId, byte[] data) {
		completeCache(requestId, q -> {
			q.viaData = data;
			q.viaReady = true;
		});
	}

	void completeY0Cache(long requestId, byte[] data) {
		completeCache(requestId, q -> {
			q.y0Data = data;
			q.y0Ready = true;
		});
	}

	private void completeCache(long requestId, java.util.function.Consumer<QueuedPacket> apply) {
		QueuedPacket q = queue.get(requestId);
		if (q == null) return;
		apply.accept(q);
		tryDequeueAndWrite(requestId);
	}

	private void tryDequeueAndWrite(long requestId) {
		QueuedPacket q = queue.get(requestId);
		if (q != null && q.viaReady && q.y0Ready) {
			dequeueAndWrite(requestId);
		}
	}

	private void dequeueAndWrite(long requestId) {
		QueuedPacket q = queue.remove(requestId);
		if (q == null) return;
		writeQueuedPacket(q);
	}

	private void writeQueuedPacket(QueuedPacket q) {
		if (ctx != null && ctx.channel().isOpen()) {
			try {
				ctx.channel().eventLoop().execute(() -> writeWithData(ctx, q.buf, q.promise, q.viaData, q.y0Data));
			} catch (Throwable error) {
				failAndRelease(q, error);
			}
		} else {
			failAndRelease(q, new ClosedChannelException());
		}
	}

	private void scheduleTimeout(long requestId, QueuedPacket q) {
		if (ctx != null) {
			ctx.channel().eventLoop().schedule(() -> {
				if (queue.remove(requestId, q)) {
					writeQueuedPacket(q);
				}
			}, timeoutMs, TimeUnit.MILLISECONDS);
		}
	}

	void writeWithData(ChannelHandlerContext ctx, ByteBuf buf, ChannelPromise promise,
						byte[] viaData, byte[] y0Data) {
		boolean hasVia = viaData != null && viaData.length > 0;
		boolean hasY0 = y0Data != null && y0Data.length > 0;

		if (!hasVia && !hasY0) {
			writeOriginal(ctx, buf, promise);
			return;
		}

		ByteBuf bufOut = null;
		try {
			int totalSize = buf.readableBytes();
			if (hasVia) totalSize += viaData.length;
			if (hasY0) totalSize += 4 + 4 + y0Data.length; // 4 bytes for Int magic, 4 bytes for length

			bufOut = ctx.alloc().buffer(totalSize);
			bufOut.writeBytes(buf);

			if (hasVia) {
				bufOut.writeBytes(viaData);
			}

			if (hasY0) {
				bufOut.writeInt(0x59304348);
				bufOut.writeInt(y0Data.length);
				bufOut.writeBytes(y0Data);
			}

			// Hand ownership to ctx.write before calling it: a synchronous rejection releases the
			// message itself, so the cleanup below must no longer see the buffer.
			ByteBuf toWrite = bufOut;
			bufOut = null;
			ctx.write(toWrite, promise);
		} catch (Throwable error) {
			promise.tryFailure(error);
		} finally {
			releaseIfLive(bufOut);
			releaseIfLive(buf);
		}
	}

	private void writeOriginal(ChannelHandlerContext ctx, ByteBuf buf, ChannelPromise promise) {
		try {
			ctx.write(buf, promise);
		} catch (Throwable error) {
			// ctx.write already released the message when it rejected it synchronously, so the
			// release below is skipped by the reference count guard.
			failAndRelease(buf, promise, error);
		}
	}

	private void removeAndFail(long requestId, QueuedPacket q, Throwable error) {
		if (queue.remove(requestId, q)) {
			failAndRelease(q, error);
		}
	}

	private static void failAndRelease(QueuedPacket q, Throwable error) {
		failAndRelease(q.buf, q.promise, error);
	}

	private static void failAndRelease(ByteBuf buf, ChannelPromise promise, Throwable error) {
		releaseIfLive(buf);
		promise.tryFailure(error);
	}

	/** Releases a buffer this handler still owns. Buffers already handed off or freed are left alone. */
	private static void releaseIfLive(ByteBuf buf) {
		if (buf != null && buf.refCnt() > 0) {
			ReferenceCountUtil.safeRelease(buf);
		}
	}

	private int readVarInt(ByteBuf buf) {
		int n = 0;
		int r = 0;
		byte b;
		do {
			b = buf.readByte();
			r |= (b & 0x7F) << (7 * n++);
			if (n > 5) throw new RuntimeException("VarInt too big");
		} while ((b & 0x80) != 0);
		return r;
	}

	private long readVarLong(ByteBuf buf) {
		long value = 0L;
		int position = 0;
		byte currentByte;
		do {
			currentByte = buf.readByte();
			value |= (long) (currentByte & 0x7F) << position;
			position += 7;
			if (position > 70) {
				throw new RuntimeException("VarLong too big");
			}
		} while ((currentByte & 0x80) != 0);
		return value;
	}

	static BlockChangePosition decodeSingleBlockChangePosition(long value) {
		int x = decodeSigned((int) (value >> 38), 26);
		int z = decodeSigned((int) ((value >> 12) & 0x3FFFFFFL), 26);
		int y = decodeSigned((int) (value & 0xFFFL), 12);
		return new BlockChangePosition(x, y, z);
	}

	static BlockChangePosition decodeMultiBlockChangePosition(long sectionPosition, long entry) {
		int sectionX = decodeSectionCoordX(sectionPosition);
		int sectionY = decodeSectionCoordY(sectionPosition);
		int sectionZ = decodeSectionCoordZ(sectionPosition);
		int localPosition = (int) (entry & 0xFFFL);
		int x = (sectionX << 4) | ((localPosition >> 8) & 0xF);
		int z = (sectionZ << 4) | ((localPosition >> 4) & 0xF);
		int y = (sectionY << 4) | (localPosition & 0xF);
		return new BlockChangePosition(x, y, z);
	}

	private static int decodeSectionCoordX(long sectionPosition) {
		return decodeSigned((int) (sectionPosition >> 42), 22);
	}

	private static int decodeSectionCoordY(long sectionPosition) {
		return decodeSigned((int) (sectionPosition & 0xFFFFFL), 20);
	}

	private static int decodeSectionCoordZ(long sectionPosition) {
		return decodeSigned((int) ((sectionPosition >> 20) & 0x3FFFFFL), 22);
	}

	private static int decodeSigned(int value, int bits) {
		int signBit = 1 << (bits - 1);
		int fullMask = (1 << bits) - 1;
		value &= fullMask;
		return (value ^ signBit) - signBit;
	}

	private int varIntLen(ByteBuf buf) {
		int s = buf.readerIndex();
		readVarInt(buf);
		int l = buf.readerIndex() - s;
		buf.readerIndex(s);
		return l;
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) {
		for (Map.Entry<Long, QueuedPacket> entry : queue.entrySet()) {
			removeAndFail(entry.getKey(), entry.getValue(), new ClosedChannelException());
		}
	}
}
