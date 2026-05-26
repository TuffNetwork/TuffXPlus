package tf.tuff.viablocks;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import tf.tuff.viablocks.version.VersionAdapter;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;

public class CustomBlockListener {

    public final ViaBlocksPlugin plugin;
    private final VersionAdapter versionAdapter;
    private final PaletteManager paletteManager;
    private final EnumSet<Material> modernMaterials;
    private tf.tuff.netty.ChunkInjector chunkInjector;
    private static final Map<String, Integer> worldMinHeights = new ConcurrentHashMap<>();
    private static final long X_MASK = (1L << 26) - 1L;
    private static final long Z_MASK = (1L << 26) - 1L;
    private static final long Y_MASK = (1L << 12) - 1L;
    private static final int Y_SHIFT = 0;
    private static final int Z_SHIFT = 12;
    private static final int X_SHIFT = 12 + 26; 
    private final Map<UUID, Map<Integer, List<Long>>> pendingUpdates = new HashMap<>();
    private final Set<UUID> pendingFlush = new HashSet<>();
    private static final double UPDATE_RADIUS_SQUARED = 6400;
    private static final @Nonnull byte[] EMPTY_PACKET = new byte[0];
    
    private final Cache<BlockData, Integer> blockDataIdCache;
    private final Cache<String, byte[]> chunkPacketCache;

    private final Cache<Long, Integer> recentModernChanges;

    public CustomBlockListener(ViaBlocksPlugin plugin, VersionAdapter versionAdapter, PaletteManager paletteManager) {
        this.plugin = plugin;
        this.versionAdapter = versionAdapter;
        this.paletteManager = paletteManager;
        this.modernMaterials = versionAdapter.getModernMaterials();
        this.blockDataIdCache = CacheBuilder.newBuilder()
            .maximumSize(8000)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();
        this.chunkPacketCache = CacheBuilder.newBuilder()
            .maximumSize(4096)
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .build();
        this.recentModernChanges = CacheBuilder.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
    }

    public byte[] getCachedChunkData(String worldName, int x, int z) {
        return chunkPacketCache.getIfPresent(chunkKey(worldName, x, z));
    }

    public void setChunkInjector(tf.tuff.netty.ChunkInjector injector) {
        if (injector == null) return;
        this.chunkInjector = injector;
    }

    private @Nonnull String chunkKey(String worldName, int x, int z) {
        return worldName + "_" + x + "_" + z;
    }

    public void onViaBlocksPlayerJoin(Player player) {
        if (plugin.isFirstJoin(player)) {
            plugin.sendWelcomeGui(player);
            plugin.markPlayerAsJoined(player);
        }
        sendPaletteToClient(player);

        preCacheVisibleChunks(player);
        if (chunkInjector != null) {
            chunkInjector.inject(player);
        }
        sendInitialChunks(player);
    }

    private void preCacheVisibleChunks(Player player) {
        World world = player.getWorld();
        int viewDistance = Math.min(this.versionAdapter.getClientViewDistance(player), 16);
        int px = player.getLocation().getChunk().getX();
        int pz = player.getLocation().getChunk().getZ();

        for (int x = -viewDistance; x <= viewDistance; x++) {
            for (int z = -viewDistance; z <= viewDistance; z++) {
                int cx = px + x;
                int cz = pz + z;

                if (world.isChunkLoaded(cx, cz)) {
                    Chunk chunk = world.getChunkAt(cx, cz);
                    prepareChunkCache(chunk);
                }
            }
        }
    }

    private static final int CHUNKS_PER_TICK = 8;

    private void sendInitialChunks(Player player) {
        World world = player.getWorld();
        int viewDistance = Math.min(this.versionAdapter.getClientViewDistance(player), 16);
        int px = player.getLocation().getChunk().getX();
        int pz = player.getLocation().getChunk().getZ();

        List<int[]> chunks = new ArrayList<>();
        for (int x = -viewDistance; x <= viewDistance; x++) {
            for (int z = -viewDistance; z <= viewDistance; z++) {
                int cx = px + x;
                int cz = pz + z;
                if (world.isChunkLoaded(cx, cz)) {
                    chunks.add(new int[]{cx, cz, x * x + z * z});
                }
            }
        }

        chunks.sort((a, b) -> Integer.compare(a[2], b[2]));

        sendChunksBatched(player, world.getName(), chunks, 0);
    }

    private void sendChunksBatched(Player player, String worldName, List<int[]> chunks, int startIndex) {
        if (!player.isOnline() || startIndex >= chunks.size()) return;

        int endIndex = Math.min(startIndex + CHUNKS_PER_TICK, chunks.size());
        for (int i = startIndex; i < endIndex; i++) {
            int[] chunk = chunks.get(i);
            byte[] data = getExtraDataForChunk(worldName, chunk[0], chunk[1]);
            if (data != null && data.length > 0) {
                player.sendPluginMessage(plugin.plugin, ViaBlocksPlugin.CLIENTBOUND_CHANNEL, data);
            }
        }

        if (endIndex < chunks.size()) {
            final int nextStart = endIndex;
            runSyncLater(() -> sendChunksBatched(player, worldName, chunks, nextStart), 1);
        }
    }

    public void handlePlayerQuit(PlayerQuitEvent event) {
        if (chunkInjector != null) {
            chunkInjector.eject(event.getPlayer());
        }

        UUID playerId = event.getPlayer().getUniqueId();
        pendingUpdates.remove(playerId);
        pendingFlush.remove(playerId);
        plugin.viaBlocksEnabledPlayers.remove(playerId);
        plugin.setPlayerEnabled(event.getPlayer(), false);
    }

    public void handleChunkLoad(ChunkLoadEvent event) {
        prepareChunkCache(event.getChunk());
    }

    public void prepareChunkCache(Chunk chunk) {
        if (!chunk.isLoaded() || modernMaterials.isEmpty()) return;

        String key = chunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());

        if (chunkPacketCache.getIfPresent(key) != null) return;

        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
        int minHeight = getMinHeight(chunk.getWorld());
        int maxHeight = chunk.getWorld().getMaxHeight();

        plugin.chunkExecutor.submit(() -> {
            try {
                if (chunkPacketCache.getIfPresent(key) != null) return;
                Map<Integer, List<Long>> foundBlocks = findModernBlocksInChunk(snapshot, minHeight, maxHeight);
                if (foundBlocks.isEmpty()) {
                    chunkPacketCache.put(key, EMPTY_PACKET);
                } else {
                    chunkPacketCache.put(key, buildChunkPacket(foundBlocks));
                }
            } catch (Exception e) {}
        });
    }

    public byte[] getExtraDataForChunk(String worldName, int x, int z) {
        return chunkPacketCache.getIfPresent(chunkKey(worldName, x, z));
    }

    public void cacheChunkWithCallback(World world, int x, int z, Consumer<byte[]> callback) {
        String key = chunkKey(world.getName(), x, z);
        byte[] existing = chunkPacketCache.getIfPresent(key);
        if (existing != null) {
            callback.accept(existing.length > 0 ? existing : null);
            return;
        }

        if (!world.isChunkLoaded(x, z)) {
            chunkPacketCache.put(key, EMPTY_PACKET);
            callback.accept(null);
            return;
        }

        Chunk chunk = world.getChunkAt(x, z);
        if (!chunk.isLoaded() || modernMaterials.isEmpty()) {
            chunkPacketCache.put(key, EMPTY_PACKET);
            callback.accept(null);
            return;
        }

        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
        int minHeight = getMinHeight(world);
        int maxHeight = world.getMaxHeight();

        plugin.chunkExecutor.submit(() -> {
            try {
                byte[] cached = chunkPacketCache.getIfPresent(key);
                if (cached != null) {
                    callback.accept(cached.length > 0 ? cached : null);
                    return;
                }
                Map<Integer, List<Long>> foundBlocks = findModernBlocksInChunk(snapshot, minHeight, maxHeight);
                if (foundBlocks.isEmpty()) {
                    chunkPacketCache.put(key, EMPTY_PACKET);
                    callback.accept(null);
                } else {
                    @SuppressWarnings("null")
                    @Nonnull byte[] data = buildChunkPacket(foundBlocks);
                    chunkPacketCache.put(key, data);
                    callback.accept(data);
                }
            } catch (Exception e) {
                callback.accept(null);
            }
        });
    }

    private Map<Integer, List<Long>> findModernBlocksInChunk(ChunkSnapshot chunkSnapshot, int minHeight, int maxHeight) {
        Int2ObjectMap<LongList> foundBlocks = new Int2ObjectOpenHashMap<>();
    
        int chunkX = chunkSnapshot.getX() << 4;
        int chunkZ = chunkSnapshot.getZ() << 4;
    
        for (int x = 0; x < 16; x++) {
            int worldX = chunkX + x;
            for (int z = 0; z < 16; z++) {
                int worldZ = chunkZ + z;
                for (int y = minHeight; y < maxHeight; y++) {
                
                    // Check material FIRST — getBlockType() returns an enum, no allocation
                    Material blockType = chunkSnapshot.getBlockType(x, y, z);
    
                    if (blockType == Material.AIR
                            || blockType == Material.CAVE_AIR
                            || blockType == Material.VOID_AIR
                            || !this.modernMaterials.contains(blockType)) {
                        continue;
                    }
    
                    // Only allocate BlockData for confirmed modern blocks
                    BlockData data = chunkSnapshot.getBlockData(x, y, z);
    
                    Integer cachedId = blockDataIdCache.getIfPresent(data);
                    int materialId;
                    if (cachedId != null) {
                        materialId = cachedId;
                    } else {
                        materialId = this.paletteManager.getOrCreateId(data.getAsString());
                        blockDataIdCache.put(data, materialId);
                    }
    
                    if (materialId != -1) {
                        long packedLocation = packLocation(worldX, y, worldZ);
                        LongList locs = foundBlocks.get(materialId);
                        if (locs == null) {
                            locs = new LongArrayList();
                            foundBlocks.put(materialId, locs);
                        }
                        locs.add(packedLocation);
                    }
                }
            }
        }
    
        return (Map<Integer, List<Long>>) (Map<?, ?>) foundBlocks;
    }   
    
    public byte[] getExtraDataForMultiBlock(World world, List<Long> locations) {
        Map<Integer, List<Long>> foundBlocks = new HashMap<>();
        
        for (long packedLoc : locations) {
            Integer cachedId = recentModernChanges.getIfPresent(packedLoc);
            if (cachedId != null) {
                List<Long> locs = foundBlocks.get(cachedId);
                if (locs == null) {
                    locs = new ArrayList<>();
                    foundBlocks.put(cachedId, locs);
                }
                locs.add(packedLoc);
                continue;
            }

            int x = (int) (packedLoc >> 38); 
            
            int y = (int) ((packedLoc << 52) >> 52); 
            
            int z = (int) ((packedLoc << 26) >> 38);
            
            Block block = world.getBlockAt(x, y, z);
            BlockData data = block.getBlockData();
            
            if (isModernMaterial(data.getMaterial())) {
                int id = getMaterialId(data);
                if (id != -1) {
                    List<Long> locs2 = foundBlocks.get(id);
                    if (locs2 == null) {
                        locs2 = new ArrayList<>();
                        foundBlocks.put(id, locs2);
                    }
                    locs2.add(packedLoc);
                }
            }
        }

        if (foundBlocks.isEmpty()) return null;
        return buildChunkPacket(foundBlocks); 
    }

    public byte[] getExtraDataForSingleBlock(World world, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        BlockData data = block.getBlockData();
        long packed = packLocation(x, y, z);

        if (!isModernMaterial(data.getMaterial())) {
            Integer cachedId = recentModernChanges.getIfPresent(packed);
            if (cachedId != null && cachedId > 0) {
                recentModernChanges.put(packed, 0);
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("ADD_SINGLE");
                out.writeInt(0);
                out.writeLong(packed);
                return out.toByteArray();
            }
            return null;
        }

        int id = getMaterialId(data);
        if (id == -1) return null;

        recentModernChanges.put(packed, id);
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("ADD_SINGLE");
        out.writeInt(id);
        out.writeLong(packed);
        return out.toByteArray();
    }

    public void handleBlockPlace(BlockPlaceEvent event) {
        handleModernBlockChange(
            event.getBlockReplacedState().getBlockData(),
            event.getBlock().getBlockData(),
            event.getBlock().getLocation()
        );
    }

    public void handleBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        BlockData data = block.getBlockData();
        Location loc = block.getLocation();

        handleModernBlockChange(data, Material.AIR.createBlockData(), loc);

        if (data instanceof Door) {
            Door door = (Door) data;
            Location otherHalf = door.getHalf() == Bisected.Half.BOTTOM
                ? loc.clone().add(0, 1, 0)
                : loc.clone().add(0, -1, 0);
            long otherPacked = packLocation(otherHalf);
            recentModernChanges.put(otherPacked, 0);
            invalidateChunkCache(otherHalf.getChunk());
        }
    }

    public void handleBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            handleModernBlockChange(
                block.getBlockData(),
                Material.AIR.createBlockData(),
                block.getLocation()
            );
        }
    }

    public void handleBlockFromTo(BlockFromToEvent event) {
        Block destroyedBlock = event.getToBlock();
        handleModernBlockChange(
            destroyedBlock.getBlockData(),
            Material.AIR.createBlockData(),
            destroyedBlock.getLocation()
        );
    }

    public void handleBlockGrow(BlockGrowEvent event) {
        handleModernBlockChange(
            event.getBlock().getBlockData(),
            event.getNewState().getBlockData(),
            event.getBlock().getLocation()
        );
    }

    public void handleBlockFade(BlockFadeEvent event) {
        handleModernBlockChange(
            event.getBlock().getBlockData(),
            event.getNewState().getBlockData(),
            event.getBlock().getLocation()
        );
    }

    public void handleBlockForm(BlockFormEvent event) {
        handleModernBlockChange(
            event.getBlock().getBlockData(),
            event.getNewState().getBlockData(),
            event.getBlock().getLocation()
        );
    }

    public void handleBlockSpread(BlockSpreadEvent event) {
        handleModernBlockChange(
            event.getBlock().getBlockData(),
            event.getNewState().getBlockData(),
            event.getBlock().getLocation()
        );
    }

    public void handleBlockPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        if (isModernMaterial(block.getType())) {
            BlockData data = block.getBlockData();
            long packed = packLocation(block.getLocation());

            Integer cachedId = recentModernChanges.getIfPresent(packed);
            int currentId = getMaterialId(data);

            if (cachedId == null || cachedId != currentId) {
                recentModernChanges.put(packed, currentId);
                sendBlockStateUpdateToNearbyPlayers(block.getLocation(), data);
                invalidateChunkCache(block.getChunk());
            }
        }
    }

    private void handleModernBlockChange(BlockData before, BlockData after, Location location) {
        if (!plugin.isEnabled() || location == null) return;

        boolean afterModern = after != null && isModernMaterial(after.getMaterial());
        boolean beforeModern = before != null && isModernMaterial(before.getMaterial());
        long packed = packLocation(location);

        if (afterModern) {
            int id = getMaterialId(after);
            recentModernChanges.put(packed, id);
            sendBlockStateUpdateToNearbyPlayers(location, after);
        } else if (beforeModern || recentModernChanges.getIfPresent(packed) != null) {
            recentModernChanges.put(packed, 0);
            sendClearUpdateToNearbyPlayers(location);
        } else {
            recentModernChanges.invalidate(packed);
        }

        invalidateChunkCache(location.getChunk());
    }

    public Integer getRecentChange(long packed) {
        return recentModernChanges.getIfPresent(packed);
    }

    private void sendBlockStateUpdateToNearbyPlayers(Location location, BlockData data) {
        if (!plugin.isEnabled() || data == null || location.getWorld() == null) return;
        Integer cachedId = blockDataIdCache.getIfPresent(data);
        int stateId;
        if (cachedId != null) {
            stateId = cachedId;
        } else {
            stateId = this.paletteManager.getOrCreateId(data.getAsString());
            blockDataIdCache.put(data, stateId);
        }
        if (stateId == -1) return;

        World world = location.getWorld();
        for (Player player : world.getPlayers()) {
            if (plugin.isPlayerEnabled(player) && player.getLocation().distanceSquared(location) < UPDATE_RADIUS_SQUARED) {
                sendPacket(player, stateId, location);
            }
        }
    }

    private void sendClearUpdateToNearbyPlayers(Location location) {
        if (!plugin.isEnabled() || plugin.viaBlocksEnabledPlayers.isEmpty() || location.getWorld() == null) return;
        final int AIR_ID = 0;
        World world = location.getWorld();
        
        for (Player player : world.getPlayers()) {
            if (plugin.isPlayerEnabled(player) && player.getLocation().distanceSquared(location) < UPDATE_RADIUS_SQUARED) {
                sendPacket(player, AIR_ID, location);
            }
        }
    }

    private void invalidateChunkCache(Chunk chunk) {
        if (chunk == null) return;
        chunkPacketCache.invalidate(chunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()));
    }

    private void sendPacket(Player player, int stateId, Location location) {
        if (!player.isOnline()) return;
        UUID playerId = player.getUniqueId();
        Map<Integer, List<Long>> updateData = pendingUpdates.get(playerId);
        if (updateData == null) {
            updateData = new HashMap<>();
            pendingUpdates.put(playerId, updateData);
        }
        List<Long> stateList = updateData.get(stateId);
        if (stateList == null) {
            stateList = new ArrayList<>();
            updateData.put(stateId, stateList);
        }
        stateList.add(packLocation(location));
        if (pendingFlush.add(playerId)) {
            runSyncLater(() -> flushPendingUpdates(playerId), plugin.getUpdateBatchDelayTicks());
        }
    }

    private void flushPendingUpdates(UUID playerId) {
        Map<Integer, List<Long>> updateData = pendingUpdates.remove(playerId);
        pendingFlush.remove(playerId);
        if (updateData == null || updateData.isEmpty()) return;

        Player player = plugin.plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) return;

        byte[] packetData = buildChunkPacket(updateData);
        player.sendPluginMessage(plugin.plugin, ViaBlocksPlugin.CLIENTBOUND_CHANNEL, packetData);
    }

    private int getMaterialId(BlockData data) {
        @SuppressWarnings("null")
        Integer cachedId = blockDataIdCache.getIfPresent(data);
        if (cachedId != null) return cachedId;

        int id = this.paletteManager.getOrCreateId(data.getAsString());
        blockDataIdCache.put(data, id);
        return id;
    }
    
    private byte[] buildChunkPacket(Map<Integer, List<Long>> blockData) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("ADD_CHUNK"); 
        out.writeInt(blockData.size());
        for (Map.Entry<Integer, List<Long>> entry : blockData.entrySet()) {
            out.writeInt(entry.getKey());
            out.writeInt(entry.getValue().size());
            for (Long loc : entry.getValue()) {
                out.writeLong(loc);
            }
        }
        return out.toByteArray();
    }

    public void sendPaletteToClient(Player player) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("INIT_PALETTE");
        List<String> palette = this.paletteManager.getPalette();
        out.writeInt(palette.size());
        for (String state : palette) {
            out.writeUTF(state);
        }
        player.sendPluginMessage(plugin.plugin, ViaBlocksPlugin.CLIENTBOUND_CHANNEL, out.toByteArray());
    }
    
    public boolean isModernMaterial(Material material) {
        return this.modernMaterials.contains(material);
    }

    public void processChunkForSinglePlayer(Chunk chunk, Player player) {
        if (!chunk.isLoaded() || !plugin.isPlayerEnabled(player)) return;
        prepareChunkCache(chunk);
        byte[] data = getExtraDataForChunk(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        if (data != null && data.length > 0) {
            player.sendPluginMessage(plugin.plugin, ViaBlocksPlugin.CLIENTBOUND_CHANNEL, data);
        }
    }

    public void clearCache() {
        blockDataIdCache.invalidateAll();
        pendingUpdates.clear();
        pendingFlush.clear();
        chunkPacketCache.invalidateAll();
        recentModernChanges.invalidateAll();
    }

    private void runSyncLater(Runnable task, long delay) { 
        if (!plugin.plugin.isEnabled()) return;
        try {
            plugin.plugin.getServer().getScheduler().runTaskLater(plugin.plugin, task, delay); 
        } catch (Exception e) {}
    }

    private int getMinHeight(World world) {
        return worldMinHeights.computeIfAbsent(world.getName(), k -> {
            try {
                Method method = world.getClass().getMethod("getMinHeight");
                Object value = method.invoke(world);
                if (value instanceof Integer) {
                    return (Integer) value;
                }
            } catch (Exception e) {}
            return 0;
        });
    }
    
    public long packLocation(int x, int y, int z) {
        return ((long)x & X_MASK) << X_SHIFT | ((long)z & Z_MASK) << Z_SHIFT | ((long)y & Y_MASK);
    }

    public long packLocation(Location loc) {
        return packLocation(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
