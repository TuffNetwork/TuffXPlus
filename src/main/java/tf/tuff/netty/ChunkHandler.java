package tf.tuff.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import tf.tuff.viablocks.CustomBlockListener;
import tf.tuff.y0.Y0Plugin;

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

            if (packetId == 0x0B && viaBlocks != null) {
                handleBlockChange(ctx, buf, promise);
                return;
            }

            if (packetId == 0x10 && viaBlocks != null) {
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

    private void handleChunkPacket(ChannelHandlerContext ctx, ByteBuf buf, ChannelPromise promise) throws Exception {
        int chunkX = buf.readInt();
        int chunkZ = buf.readInt();
        buf.resetReaderIndex();

        byte[] viaData = viaBlocks != null ? viaBlocks.getExtraDataForChunk(player.getWorld().getName(), chunkX, chunkZ) : null;
        byte[] y0Data = y0 != null ? y0.getY0DataForChunk(player, chunkX, chunkZ) : null;

        boolean needVia = viaBlocks != null;
        boolean needY0 = y0 != null && y0.isPlayerReady(player);

        boolean viaReady = !needVia || viaData != null;
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
        int idx = buf.readerIndex();
        long val = buf.getLong(idx);
        int x = (int) (val >> 38);
        int z = (int) ((val >> 12) & 0x3FFFFFF);
        int y = (int) (val & 0xFFF);
        if (x >= 0x2000000) x -= 0x4000000;
        if (z >= 0x2000000) z -= 0x4000000;
        if (y >= 0x800) y -= 0x1000;

        byte[] data = viaBlocks.getExtraDataForSingleBlock(player.getWorld(), x, y, z);
        if (data != null && data.length > 0) {
            buf.resetReaderIndex();
            writeWithViaOnly(ctx, buf, promise, data);
            return;
        }
        buf.resetReaderIndex();
        super.write(ctx, buf, promise);
    }

    private void handleMultiBlockChange(ChannelHandlerContext ctx, ByteBuf buf, ChannelPromise promise) throws Exception {
        buf.resetReaderIndex();
        buf.skipBytes(varIntLen(buf));
        int cx = buf.readInt();
        int cz = buf.readInt();

        if (Math.abs(cx) < 2000000 && Math.abs(cz) < 2000000) {
            int count = readVarInt(buf);
            java.util.List<Long> locs = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                short h = buf.readUnsignedByte();
                int by = buf.readUnsignedByte();
                readVarInt(buf);
                int bx = (h >> 4 & 15) + (cx * 16);
                int bz = (h & 15) + (cz * 16);
                locs.add(viaBlocks.packLocation(bx, by, bz));
            }
            byte[] data = viaBlocks.getExtraDataForMultiBlock(player.getWorld(), locs);
            if (data != null && data.length > 0) {
                buf.resetReaderIndex();
                writeWithViaOnly(ctx, buf, promise, data);
                return;
            }
        }
        buf.resetReaderIndex();
        super.write(ctx, buf, promise);
    }

    private void requestViaCache(int cx, int cz, long key) {
        Bukkit.getScheduler().runTask(viaBlocks.plugin.plugin, () -> {
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
        Bukkit.getScheduler().runTask(viaBlocks.plugin.plugin, () -> {
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
        composite.addComponents(true, buf.retain(), tail);

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
