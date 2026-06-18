package tf.tuff.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.bukkit.World;
import org.bukkit.entity.Player;
import tf.tuff.viablocks.CustomBlockListener;
import tf.tuff.y0.Y0Plugin;
import tf.tuff.util.SchedulerCompat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ChunkHandler extends ChannelOutboundHandlerAdapter {

	private final CustomBlockListener viaBlocks;
	private final Y0Plugin y0;
	private final Player player;
	private final UUID playerId;
	private final Map<Long, QueuedPacket> queue = new ConcurrentHashMap<>();
	private volatile ChannelHandlerContext ctx;

	private static final long TIMEOUT_MS = 500;

	static record BlockChangePosition(int x, int y, int z) {}

	public ChunkHandler(CustomBlockListener viaBlocks, Y0Plugin y0, Player player) {
		this.viaBlocks = viaBlocks;
		this.y0 = y0;
		this.player = player;
		this.playerId = player.getUniqueId();
	}

	private static class QueuedPacket {
		final ByteBuf buf;
		final ChannelPromise promise;
		final int chunkX;
		final int chunkZ;
		final long time;
		volatile boolean viaReady;
		volatile boolean y0Ready;
		volatile byte[] viaData;
		volatile byte[] y0Data;

		QueuedPacket(ByteBuf buf, ChannelPromise promise, int cx, int cz) {
			this.buf = buf;
			this.promise = promise;
			this.chunkX = cx;
			this.chunkZ = cz;
			this.time = System.currentTimeMillis();
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

	private boolean isViaActive() {
		return viaBlocks != null
			&& viaBlocks.plugin.isEnabled()
			&& viaBlocks.plugin.isPlayerEnabled(player);
	}

	private void handleChunkPacket(ChannelHandlerContext ctx, ByteBuf buf, ChannelPromise promise) throws Exception {
		int chunkX = buf.readInt();
		int chunkZ = buf.readInt();
		buf.resetReaderIndex();

		boolean viaActive = isViaActive();
		byte[] viaData = viaActive ? viaBlocks.getExtraDataForChunk(player.getWorld().getName(), chunkX, chunkZ) : null;
		byte[] y0Data = y0 != null ? y0.getY0DataForChunk(player, chunkX, chunkZ) : null;

		boolean needY0 = y0 != null && y0.isPlayerReady(player);

		boolean viaReady = !viaActive || viaData != null;
		boolean y0Ready = !needY0 || y0Data != null;

		if (viaReady && y0Ready) {
			writeWithData(ctx, buf, promise, viaData, y0Data);
			return;
		}

		long key = key(chunkX, chunkZ);
		QueuedPacket q = new QueuedPacket(buf.retain(), promise, chunkX, chunkZ);
		q.viaReady = viaReady;
		q.y0Ready = y0Ready;
		q.viaData = viaData;
		q.y0Data = y0Data;
		queue.put(key, q);

		if (!viaReady) {
			requestViaCache(chunkX, chunkZ, key);
		}
		if (!y0Ready) {
			requestY0Cache(chunkX, chunkZ, key);
		}

		scheduleTimeout(key);
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
		ByteBuf retained = buf.retain();
		SchedulerCompat.runRegion(viaBlocks.plugin.plugin, world, chunkX, chunkZ, () -> {
			byte[] data = null;
			try {
				if (player.isOnline() && isViaActive()) {
					data = supplier.call();
				}
			} catch (Exception ignored) {
			}
			final byte[] resolvedData = data;

			ChannelHandlerContext currentCtx = this.ctx != null ? this.ctx : ctx;
			currentCtx.channel().eventLoop().execute(() -> {
				try {
					retained.resetReaderIndex();
					if (resolvedData != null && resolvedData.length > 0) {
						writeWithViaOnly(currentCtx, retained, promise, resolvedData);
					} else {
						currentCtx.write(retained, promise);
					}
				} catch (Exception ignored) {
				} finally {
					if (retained.refCnt() > 0) {
						retained.release();
					}
				}
			});
		});
	}

	private void requestViaCache(int cx, int cz, long key) {
		SchedulerCompat.runRegion(viaBlocks.plugin.plugin, player.getWorld(), cx, cz, () -> {
			if (!player.isOnline()) {
				release(key);
				return;
			}
			viaBlocks.cacheChunkWithCallback(player.getWorld(), cx, cz, data -> {
				QueuedPacket q = queue.get(key);
				if (q != null) {
					q.viaData = data;
					q.viaReady = true;
					tryRelease(key);
				}
			});
		});
	}

	private void requestY0Cache(int cx, int cz, long key) {
		SchedulerCompat.runRegion(viaBlocks.plugin.plugin, player.getWorld(), cx, cz, () -> {
			if (!player.isOnline()) {
				release(key);
				return;
			}
			y0.cacheChunkWithCallback(player, cx, cz, data -> {
				QueuedPacket q = queue.get(key);
				if (q != null) {
					q.y0Data = data;
					q.y0Ready = true;
					tryRelease(key);
				}
			});
		});
	}

	private void tryRelease(long key) {
		QueuedPacket q = queue.get(key);
		if (q != null && q.viaReady && q.y0Ready) {
			release(key);
		}
	}

	private void release(long key) {
		QueuedPacket q = queue.remove(key);
		if (q == null) return;

		if (ctx != null && ctx.channel().isOpen()) {
			ctx.channel().eventLoop().execute(() -> {
				try {
					writeWithData(ctx, q.buf, q.promise, q.viaData, q.y0Data);
				} catch (Exception e) {
				} finally {
					if (q.buf.refCnt() > 0) {
						q.buf.release();
					}
				}
			});
		} else {
			if (q.buf.refCnt() > 0) {
				q.buf.release();
			}
		}
	}

	private void scheduleTimeout(long key) {
		if (ctx != null) {
			ctx.channel().eventLoop().schedule(() -> {
				QueuedPacket q = queue.get(key);
				if (q != null && System.currentTimeMillis() - q.time >= TIMEOUT_MS) {
					release(key);
				}
			}, TIMEOUT_MS, TimeUnit.MILLISECONDS);
		}
	}

	private void writeWithData(ChannelHandlerContext ctx, ByteBuf buf, ChannelPromise promise,
							   byte[] viaData, byte[] y0Data) throws Exception {
		boolean hasVia = viaData != null && viaData.length > 0;
		boolean hasY0 = y0Data != null && y0Data.length > 0;

		if (!hasVia && !hasY0) {
			ctx.write(buf, promise);
			return;
		}

		CompositeByteBuf composite = ctx.alloc().compositeBuffer();
		composite.addComponent(true, buf.retain());

		if (hasVia) {
			ByteBuf tail = ctx.alloc().buffer();
			tail.writeBytes(viaData);
			composite.addComponent(true, tail);
		}

		if (hasY0) {
			ByteBuf tail = ctx.alloc().buffer();
			tail.writeInt(0x59304348);
			tail.writeInt(y0Data.length);
			tail.writeBytes(y0Data);
			composite.addComponent(true, tail);
		}

		ctx.write(composite, promise);
	}

	private void writeWithViaOnly(ChannelHandlerContext ctx, ByteBuf buf, ChannelPromise promise,
								  byte[] data) throws Exception {
		ByteBuf tail = ctx.alloc().buffer();
		tail.writeBytes(data);

		CompositeByteBuf composite = ctx.alloc().compositeBuffer();
		composite.addComponents(true, buf, tail);

		ctx.write(composite, promise);
	}

	private long key(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
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
		for (QueuedPacket q : queue.values()) {
			if (q.buf.refCnt() > 0) {
				q.buf.release();
			}
		}
		queue.clear();
	}
}
