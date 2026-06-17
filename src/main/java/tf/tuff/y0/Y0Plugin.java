package tf.tuff.y0;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import javax.annotation.Nonnull;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import tf.tuff.TuffX;
import tf.tuff.netty.ChunkInjector;
import tf.tuff.util.SchedulerCompat;

public class Y0Plugin {

    public static final String CHANNEL = "eagler:below_y0";
    public ViaBlockIds viaIds;

    private ObjectOpenHashSet<String> enabledWorlds;
    private boolean debug;
    private int processorThreadCount;
    private int cacheSize;
    private int cacheExp;
    private int concLevel;
    private boolean kickOutdatedClients;

    private final Set<UUID> readyPlayers = ConcurrentHashMap.newKeySet();
    private volatile Cache<WorldChunk, ObjectArrayList<byte[]>> chunkCache;
    private volatile Cache<WorldChunk, byte[]> chunkCacheCombined;
    private volatile ExecutorService chunkProcessor;

    private final ThreadLocal<Object2ObjectOpenHashMap<BlockData, int[]>> threadChunkData = ThreadLocal.withInitial(() -> new Object2ObjectOpenHashMap<>(256));
    private final ThreadLocal<ByteArrayOutputStream> threadOut = ThreadLocal.withInitial(() -> new ByteArrayOutputStream(8256));
    private final ThreadLocal<byte[]> threadData = ThreadLocal.withInitial(() -> new byte[12288]);

    private TuffX plugin;

    private static final int[] EMPTY_LEGACY = {1, 0};

    private static final Map<BlockData, Integer> emissionCache = new ConcurrentHashMap<>();
    private static Method getLightEmissionMethod;

    public ChunkPacketListener chunkPacketListener;
    private ChunkInjector chunkInjector;

    static {
        try {
            getLightEmissionMethod = BlockData.class.getMethod("getLightEmission");
            getLightEmissionMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
            getLightEmissionMethod = null;
        }
    }

    private static final Map<Material, Integer> legacy_light_map = Map.ofEntries(
        Map.entry(Material.TORCH, 14),
        Map.entry(Material.SOUL_TORCH, 10),
        Map.entry(Material.LANTERN, 15),
        Map.entry(Material.SOUL_LANTERN, 10),
        Map.entry(Material.GLOWSTONE, 15),
        Map.entry(Material.SEA_LANTERN, 15),
        Map.entry(Material.REDSTONE_LAMP, 15),
        Map.entry(Material.SHROOMLIGHT, 15),
        Map.entry(Material.CAMPFIRE, 15),
        Map.entry(Material.SOUL_CAMPFIRE, 10),
        Map.entry(Material.END_ROD, 14),
        Map.entry(Material.MAGMA_BLOCK, 3),
        Map.entry(Material.FIRE, 15),
        Map.entry(Material.SOUL_FIRE, 10),
        Map.entry(Material.CANDLE, 3),
        Map.entry(Material.WHITE_CANDLE, 3),
        Map.entry(Material.CAKE, 0),
        Map.entry(Material.CANDLE_CAKE, 3)
    );

    public Y0Plugin(TuffX plugin){
        this.plugin = plugin;
    }

    private void debug(String m) {
        if (debug) plugin.getLogger().info("[Y0-Debug] " + m);
    }

    public void log(Level level, String msg, Throwable e) {
        plugin.getLogger().log(level, "[Y0] "+msg, e);
    }
    public void log(Level level, String msg) {
        plugin.getLogger().log(level, "[Y0] "+msg);
    }
    public void info(String msg) {
        log(Level.INFO, msg);
    }
    public void severe(String msg) {
        log(Level.SEVERE, msg);
    }

    public record WorldChunk(String w, int x, int z) {}

    private void loadConfig() {
        debug = plugin.getConfig().getBoolean("y0.debug-mode");

        ObjectArrayList<String> ewList = new ObjectArrayList<>(plugin.getConfig().getStringList("y0.enabled-worlds"));
        enabledWorlds = new ObjectOpenHashSet<>(ewList.size());
        if (plugin.getConfig().getBoolean("y0.y0-enabled")) enabledWorlds.addAll(ewList);

        int threadSetting = plugin.getConfig().getInt("y0.chunk-processor-threads");
        if (threadSetting <= 0) {
            processorThreadCount = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        } else {
            processorThreadCount = threadSetting;
        }
        
        cacheSize = plugin.getConfig().getInt("y0.cache-size");
        cacheExp = plugin.getConfig().getInt("y0.cache-expiration");
        concLevel = Runtime.getRuntime().availableProcessors();
        kickOutdatedClients = plugin.getConfig().getBoolean("y0.kick-outdated-clients");
    }

    private void startup() {
        loadConfig();

        chunkCache = CacheBuilder.newBuilder()
            .maximumSize(cacheSize)
            .expireAfterAccess(cacheExp, TimeUnit.MINUTES)
            .concurrencyLevel(concLevel)
            .initialCapacity(256)
            .build();
        chunkCacheCombined = CacheBuilder.newBuilder()
            .maximumSize(cacheSize)
            .expireAfterAccess(cacheExp, TimeUnit.MINUTES)
            .concurrencyLevel(concLevel)
            .initialCapacity(256)
            .build();

        chunkProcessor = Executors.newFixedThreadPool(processorThreadCount, run -> {
            Thread thread = new Thread(run, "TuffX-Chunk-" + System.nanoTime());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    private void shutdown() {
        if (chunkCache != null) {
            chunkCache.invalidateAll();
        }
        if (chunkCacheCombined != null) {
            chunkCacheCombined.invalidateAll();
        }

        if (chunkProcessor != null) {
            chunkProcessor.shutdown();
            try {
                if (!chunkProcessor.awaitTermination(10, TimeUnit.SECONDS)) {
                    chunkProcessor.shutdownNow();
                    if (!chunkProcessor.awaitTermination(5, TimeUnit.SECONDS)) {
                        severe("Failed to shutdown chunk processor pool!");
                    }
                }
            } catch (InterruptedException e) {
                chunkProcessor.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                chunkProcessor = null;
            }
        }

    }

    public void onTuffXReload() {
        loadConfig();

        shutdown();
        startup();

        emissionCache.clear();

        info("Y0 reloaded.");
    }

    public void onTuffXEnable() {
        plugin.getConfig().addDefault("y0.y0-enabled", false);
        plugin.getConfig().addDefault("y0.debug-mode", false);
        plugin.getConfig().addDefault("y0.enabled-worlds", new ArrayList<>(java.util.Collections.singletonList("world")));
        plugin.getConfig().addDefault("y0.chunk-processor-threads", -1);
        plugin.getConfig().addDefault("y0.cache-size", 1024);
        plugin.getConfig().addDefault("y0.cache-expiration", 5);
        plugin.getConfig().addDefault("y0.kick-outdated-clients", true);
        plugin.getConfig().options().copyDefaults(true);

        startup();

        this.chunkPacketListener = new ChunkPacketListener(this);

        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, plugin);

        if (viaIds == null) viaIds = new ViaBlockIds(this.plugin);
    }

    public record Coords(int x, int y, int z) {}

    public void onTuffXDisable() {
        shutdown();

        readyPlayers.clear();

        if (viaIds != null) viaIds = null;
    }

    public boolean isPlayerReady(Player player) {
        if (player == null) return false;
        return readyPlayers.contains(player.getUniqueId());
    }

    public void setChunkInjector(ChunkInjector injector) {
        if (injector == null) return;
        this.chunkInjector = injector;
    }

    public void handlePacket(Player player, byte[] data) {
        try (DataInputStream i = new DataInputStream(new ByteArrayInputStream(data))) {
            i.readInt();
            i.readInt();
            i.readInt();
            int al = i.readUnsignedByte();
            byte[] ab = new byte[al];
            i.readFully(ab);
            String subchannel = new String(ab, StandardCharsets.UTF_8);
            handlePacket(player, subchannel);
        } catch (IOException e) {
            log(Level.WARNING, "Failed to parse plugin message from " + player.getName() + ": " + e.getMessage());
        }
    }

    private void handlePacket(Player player, String subchannel) {
        if (!enabledWorlds.contains(player.getWorld().getName()) && !subchannel.equalsIgnoreCase("ready")) {
            SchedulerCompat.sendPluginMessage(plugin, player, CHANNEL, y0StatusPkt(false));
            return;
        }

        switch (subchannel.toLowerCase()) {
            case "ready2":
                debug("Player " + player.getName() + " is READY.");
                readyPlayers.add(player.getUniqueId());
                if (enabledWorlds.contains(player.getWorld().getName())) {
                    readyPlayers.add(player.getUniqueId());
                    preCacheVisibleChunks(player);
                    if (chunkInjector != null) {
                        chunkInjector.inject(player);
                    }
                    SchedulerCompat.sendPluginMessage(plugin, player, CHANNEL, y0StatusPkt(true));
                    resendChunksInView(player);
                } else {
                    SchedulerCompat.sendPluginMessage(plugin, player, CHANNEL, y0StatusPkt(false));
                }
                break;
            case "use_on_block":
                break;
            case "ready":
                if (kickOutdatedClients) {
                    player.kickPlayer("§cYour client is not compatible with the version of §6TuffX§c the server has installed!\n§7Please update your client.");
                }
        }
    }

    private void preCacheVisibleChunks(Player player) {
        World world = player.getWorld();
        int viewDistance = player.getClientViewDistance();
        int playerChunkX = player.getLocation().getChunk().getX();
        int playerChunkZ = player.getLocation().getChunk().getZ();

        Object2ObjectOpenHashMap<BlockData, int[]> cvt = new Object2ObjectOpenHashMap<>(256);

        for (int x = -viewDistance; x <= viewDistance; x++) {
            for (int z = -viewDistance; z <= viewDistance; z++) {
                int currentChunkX = playerChunkX + x;
                int currentChunkZ = playerChunkZ + z;

                if (world.isChunkLoaded(currentChunkX, currentChunkZ)) {
                    WorldChunk k = new WorldChunk(world.getName(), currentChunkX, currentChunkZ);
                    if (chunkCache.getIfPresent(k) == null) {
                        try {
                            Chunk chunk = world.getChunkAt(currentChunkX, currentChunkZ);
                            ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
                            ObjectArrayList<byte[]> pp = new ObjectArrayList<>(4);
                            cvt.clear();

                            for (int sy = -4; sy < 0; sy++) {
                                byte[] sectionData = createSectionPayload(snapshot, currentChunkX, currentChunkZ, sy, cvt);
                                if (sectionData != null) {
                                    pp.add(sectionData);
                                }
                            }

                            chunkCache.put(k, pp);
                            storeCombined(k, pp);
                        } catch (Exception e) { debug("Exception while pre-caching visible chunks for player "+player.getName()+": "+e.getMessage()); }
                    }
                }
            }
        }
    }

    private static final int Y0_CHUNKS_PER_TICK = 8;

    public void resendChunksInView(Player player) {
        World world = player.getWorld();
        int viewDistance = player.getClientViewDistance();
        int playerChunkX = player.getLocation().getChunk().getX();
        int playerChunkZ = player.getLocation().getChunk().getZ();

        List<int[]> chunks = new ArrayList<>();
        for (int x = -viewDistance; x <= viewDistance; x++) {
            for (int z = -viewDistance; z <= viewDistance; z++) {
                int currentChunkX = playerChunkX + x;
                int currentChunkZ = playerChunkZ + z;

                if (world.isChunkLoaded(currentChunkX, currentChunkZ)) {
                    chunks.add(new int[]{currentChunkX, currentChunkZ, x * x + z * z});
                }
            }
        }

        chunks.sort((a, b) -> Integer.compare(a[2], b[2]));

        sendY0ChunksBatched(player, world.getName(), chunks, 0);
    }

    private void sendY0ChunksBatched(Player player, String worldName, List<int[]> chunks, int startIndex) {
        if (!player.isOnline() || startIndex >= chunks.size()) return;

        int endIndex = Math.min(startIndex + Y0_CHUNKS_PER_TICK, chunks.size());
        for (int i = startIndex; i < endIndex; i++) {
            int[] chunk = chunks.get(i);
            WorldChunk k = new WorldChunk(worldName, chunk[0], chunk[1]);
            ObjectArrayList<byte[]> cachedData = chunkCache.getIfPresent(k);
            if (cachedData != null && !cachedData.isEmpty()) {
                for (byte[] py : cachedData) {
                    SchedulerCompat.sendPluginMessage(plugin, player, CHANNEL, py);
                }
            }
        }

        if (endIndex < chunks.size()) {
            final int nextStart = endIndex;
            if (player.isOnline()) {
                SchedulerCompat.runEntityLater(player, plugin, () -> sendY0ChunksBatched(player, worldName, chunks, nextStart), 1L);
            }
        }
    }

    private byte[] y0StatusPkt(boolean s) {
        try (ByteArrayOutputStream bout = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bout)) {
            out.writeUTF("y0_status");
            out.writeBoolean(s);
            return bout.toByteArray();
        } catch (IOException e) { return null; }
    }

    private byte[] dimensionChangePkt() {
        try (ByteArrayOutputStream bout = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bout)) {
            out.writeUTF("dimension_change");
            return bout.toByteArray();
        } catch (IOException e) { return null; }
    }

    public void handlePlayerChangeWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        SchedulerCompat.sendPluginMessage(plugin, player, CHANNEL, dimensionChangePkt());
        boolean isEnabledWorld = enabledWorlds.contains(player.getWorld().getName());
        SchedulerCompat.sendPluginMessage(plugin, player, CHANNEL, y0StatusPkt(isEnabledWorld));
        if (isPlayerReady(player) && isEnabledWorld) {
            resendChunksInView(player);
        }
    }

    public void handlePlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        SchedulerCompat.sendPluginMessage(plugin, player, CHANNEL, dimensionChangePkt());
        boolean isEnabledWorld = enabledWorlds.contains(player.getWorld().getName());
        SchedulerCompat.sendPluginMessage(plugin, player, CHANNEL, y0StatusPkt(isEnabledWorld));
    }

    public void processAndSendChunk(final Player player, final Chunk c) {
        if (c == null || player == null || !player.isOnline()) return;
        if (!c.getWorld().equals(player.getWorld())) return;

        if (enabledWorlds != null && !enabledWorlds.contains(c.getWorld().getName())) return;

        final WorldChunk k = new WorldChunk(c.getWorld().getName(), c.getX(), c.getZ());
        ObjectArrayList<byte[]> cachedData = chunkCache.getIfPresent(k);
        if (cachedData != null) {
            if (player.isOnline() && c.getWorld().equals(player.getWorld())) {
                for (byte[] py : cachedData) {
                    SchedulerCompat.sendPluginMessage(plugin, player, CHANNEL, py);
                }
            }
            return;
        }

        final ChunkSnapshot snapshot = c.getChunkSnapshot(false, false, false);
        processSnapshotAsync(player, snapshot, c.getX(), c.getZ());
    }

    private void processSnapshotAsync(final Player player, final ChunkSnapshot snapshot, final int chunkX, final int chunkZ) {
        ExecutorService executor = chunkProcessor;
        if (executor == null || executor.isShutdown()) return;

        final WorldChunk k = new WorldChunk(snapshot.getWorldName(), chunkX, chunkZ);

        executor.submit(() -> {
            final ObjectArrayList<byte[]> pp = new ObjectArrayList<>(4);
            final Object2ObjectOpenHashMap<BlockData, int[]> cvt = threadChunkData.get();
            cvt.clear();
            for (int sy = -4; sy < 0; sy++) {
                if (!player.isOnline()) {
                    return;
                }
                try {
                    byte[] py = createSectionPayload(snapshot, chunkX, chunkZ, sy, cvt);
                    if (py != null) {
                        pp.add(py);
                    }
                } catch (IOException e) {
                    severe("Payload creation failed: " + e.getMessage());
                }
            }
            chunkCache.put(k, pp);
            storeCombined(k, pp);
            if (!pp.isEmpty()) {
                for (byte[] py : pp) {
                    SchedulerCompat.sendPluginMessage(plugin, player, CHANNEL, py);
                }
            }
        });
    }

    private void invalidateChunkCache(World w, int x, int z) {
        WorldChunk k = new WorldChunk(w.getName(), x >> 4, z >> 4);
        chunkCache.invalidate(k);
        chunkCacheCombined.invalidate(k);
    }

    public byte[] getY0DataForChunk(Player player, int chunkX, int chunkZ) {
        if (!isPlayerReady(player)) return null;
        World world = player.getWorld();
        if (enabledWorlds == null || !enabledWorlds.contains(world.getName())) return null;

        WorldChunk k = new WorldChunk(world.getName(), chunkX, chunkZ);

        byte[] combined = chunkCacheCombined.getIfPresent(k);
        if (combined != null) return combined.length > 0 ? combined : null;

        ObjectArrayList<byte[]> cachedData = chunkCache.getIfPresent(k);
        if (cachedData != null) {
            byte[] cb = buildCombined(cachedData);
            if (cb != null) chunkCacheCombined.put(k, cb);
            return cb;
        }

        return null;
    }

    private byte[] buildCombined(ObjectArrayList<byte[]> sections) {
        if (sections.isEmpty()) return null;
        int totalLen = 0;
        for (int i = 0, l = sections.size(); i < l; i++) {
            totalLen += sections.get(i).length;
        }
        byte[] result = new byte[totalLen];
        int offset = 0;
        for (int i = 0, l = sections.size(); i < l; i++) {
            byte[] s = sections.get(i);
            System.arraycopy(s, 0, result, offset, s.length);
            offset += s.length;
        }
        return result;
    }

    private void storeCombined(@Nonnull WorldChunk k, ObjectArrayList<byte[]> sections) {
        byte[] cb = buildCombined(sections);
        if (cb != null) {
            chunkCacheCombined.put(k, cb);
        }
    }

    public void preCacheY0Data(Player player, int chunkX, int chunkZ) {
        if (!isPlayerReady(player)) return;
        World world = player.getWorld();
        if (enabledWorlds == null || !enabledWorlds.contains(world.getName())) return;

        WorldChunk k = new WorldChunk(world.getName(), chunkX, chunkZ);
        if (chunkCache.getIfPresent(k) != null) return;

        if (!world.isChunkLoaded(chunkX, chunkZ)) return;

        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);

        ExecutorService executor = chunkProcessor;
        if (executor == null || executor.isShutdown()) return;

        executor.submit(() -> {
            try {
                if (chunkCache.getIfPresent(k) != null) return;
                Object2ObjectOpenHashMap<BlockData, int[]> cvt = threadChunkData.get();
                cvt.clear();
                ObjectArrayList<byte[]> pp = new ObjectArrayList<>(4);

                for (int sy = -4; sy < 0; sy++) {
                    byte[] sectionData = createSectionPayload(snapshot, chunkX, chunkZ, sy, cvt);
                    if (sectionData != null) {
                        pp.add(sectionData);
                    }
                }

                chunkCache.put(k, pp);
                storeCombined(k, pp);
            } catch (Exception e) { debug("Exception while pre-caching chunk %s, %s for %s: %s".formatted(chunkX, chunkZ, player.getName(), e.getMessage())); }
        });
    }

    public void cacheChunkWithCallback(Player player, int chunkX, int chunkZ, Consumer<byte[]> callback) {
        if (!isPlayerReady(player)) {
            callback.accept(null);
            return;
        }
        World world = player.getWorld();
        if (enabledWorlds == null || !enabledWorlds.contains(world.getName())) {
            callback.accept(null);
            return;
        }

        WorldChunk k = new WorldChunk(world.getName(), chunkX, chunkZ);

        byte[] combined = chunkCacheCombined.getIfPresent(k);
        if (combined != null) {
            callback.accept(combined.length > 0 ? combined : null);
            return;
        }

        ObjectArrayList<byte[]> existing = chunkCache.getIfPresent(k);
        if (existing != null) {
            byte[] cb = buildCombined(existing);
            if (cb != null) chunkCacheCombined.put(k, cb);
            callback.accept(cb);
            return;
        }

        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            callback.accept(null);
            return;
        }

        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);

        ExecutorService executor = chunkProcessor;
        if (executor == null || executor.isShutdown()) {
            callback.accept(null);
            return;
        }

        executor.submit(() -> {
            try {
                byte[] cached = chunkCacheCombined.getIfPresent(k);
                if (cached != null) {
                    callback.accept(cached.length > 0 ? cached : null);
                    return;
                }
                Object2ObjectOpenHashMap<BlockData, int[]> cvt = threadChunkData.get();
                cvt.clear();
                ObjectArrayList<byte[]> pp = new ObjectArrayList<>(4);

                for (int sy = -4; sy < 0; sy++) {
                    byte[] sectionData = createSectionPayload(snapshot, chunkX, chunkZ, sy, cvt);
                    if (sectionData != null) {
                        pp.add(sectionData);
                    }
                }

                chunkCache.put(k, pp);
                byte[] cb = buildCombined(pp);
                if (cb != null) chunkCacheCombined.put(k, cb);
                callback.accept(cb);
            } catch (Exception e) {
                callback.accept(null);
            }
        });
    }

    public void handlePlayerQuit(PlayerQuitEvent event) {
        if (chunkInjector != null) {
            chunkInjector.eject(event.getPlayer());
        }
        readyPlayers.remove(event.getPlayer().getUniqueId());
    }

    public void handleChunkLoad(ChunkLoadEvent event) {
        if (enabledWorlds == null || !enabledWorlds.contains(event.getWorld().getName())) return;
        if (!hasReadyPlayersInWorld(event.getWorld())) return;

        Chunk chunk = event.getChunk();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();

        WorldChunk k = new WorldChunk(event.getWorld().getName(), chunkX, chunkZ);
        if (chunkCache.getIfPresent(k) != null) return;

        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);

        ExecutorService executor = chunkProcessor;
        if (executor == null || executor.isShutdown()) return;

        executor.submit(() -> {
            try {
                if (chunkCache.getIfPresent(k) != null) return;
                Object2ObjectOpenHashMap<BlockData, int[]> cvt = threadChunkData.get();
                cvt.clear();
                ObjectArrayList<byte[]> pp = new ObjectArrayList<>(4);

                for (int sy = -4; sy < 0; sy++) {
                    byte[] sectionData = createSectionPayload(snapshot, chunkX, chunkZ, sy, cvt);
                    if (sectionData != null) {
                        pp.add(sectionData);
                    }
                }

                chunkCache.put(k, pp);
                storeCombined(k, pp);
            } catch (Exception e) { debug("Exception while handling chunk load: "+e.getMessage()); }
        });
    }

    private byte[] createSectionPayload(ChunkSnapshot s, int x, int z, int sy, Object2ObjectOpenHashMap<BlockData, int[]> c) throws IOException {
        // Ensure thread-local buffer is exactly 12,288 bytes to prevent overflow
        byte[] bd = threadData.get();
        Arrays.fill(bd, (byte) 0);
        int idx = 0;
        boolean hasContent = false;
        int by = sy << 4;

        // Optimized Loop Order: Matches standard Minecraft internal memory layouts
        for (int xx = 0; xx < 16; xx++) {
            for (int zz = 0; zz < 16; zz++) {
                for (int y = 0; y < 16; y++) {
                    int wy = by + y;

                    BlockData blkData = s.getBlockData(xx, wy, zz);
                    int[] ld = c.get(blkData); // Fast map lookup

                    if (ld == null) { // Avoid getOrDefault overhead
                        ld = (viaIds != null) ? viaIds.toLegacy(blkData) : EMPTY_LEGACY;
                        c.put(blkData, ld);
                    }

                    // Bitwise packing
                    short lb = (short) ((ld[1] << 12) | (ld[0] & 0xFFF));
                    byte pl = (byte) ((s.getBlockSkyLight(xx, wy, zz) << 4) | s.getBlockEmittedLight(xx, wy, zz));

                    // Write sequence
                    int linear = ((y << 8) | (zz << 4) | xx) * 3;
                    bd[linear] = (byte) (lb >> 8);
                    bd[linear + 1] = (byte) lb;
                    bd[linear + 2] = pl;
                    if (linear + 3 > idx) idx = linear + 3;

                    if (lb != 0 || pl != 0) {
                        hasContent = true;
                    }
                }
            }
        }

        if (!hasContent) return null;

        ByteArrayOutputStream bout = threadOut.get();
        bout.reset();

        // DataOutputStream wrapper safely writes schema
        try (DataOutputStream out = new DataOutputStream(bout)) {
            out.writeUTF("chunk_data");
            out.writeInt(x);
            out.writeInt(z);
            out.writeInt(sy);
            out.write(bd, 0, idx);
        }
        return bout.toByteArray();
    }

    public void handleBlockBreak(BlockBreakEvent event) {
        if (event.getBlock().getY() < 0) {
            handleBlockChange(event.getBlock().getLocation(), event.getBlock().getBlockData(), Material.AIR.createBlockData());
            invalidateChunkCache(event.getBlock().getWorld(), event.getBlock().getX(), event.getBlock().getZ());
        }
    }

    public void handleBlockPlace(BlockPlaceEvent event) {
        if (event.getBlock().getY() < 0) {
            handleBlockChange(event.getBlock().getLocation(), event.getBlockReplacedState().getBlockData(), event.getBlock().getBlockData());
            invalidateChunkCache(event.getBlock().getWorld(), event.getBlock().getX(), event.getBlock().getZ());
        }
    }

    public void handleBlockPhysics(BlockPhysicsEvent event) {
        final Block block = event.getBlock();
        if (block.getY() < 0) {
            final Location loc = block.getLocation();
            final World world = loc.getWorld();
            SchedulerCompat.runRegionLater(plugin, loc, () -> {
                BlockData ud = world.getBlockData(loc);
                sendSingleBlockUpdate(loc, ud);
                invalidateChunkCache(world, loc.getBlockX(), loc.getBlockZ());
            }, 1L);
        }
    }

    public void handleBlockExplode(BlockExplodeEvent event) {
        final List<Block> btu = new ArrayList<>(event.blockList());
        for (Block block : btu) {
            if (block.getY() >= 0) continue;
            final Location loc = block.getLocation();
            SchedulerCompat.runRegionLater(plugin, loc, () -> {
                sendSingleBlockUpdate(loc, Material.AIR.createBlockData());
                invalidateChunkCache(loc.getWorld(), loc.getBlockX(), loc.getBlockZ());
            }, 1L);
        }
    }

    public void handleBlockFromTo(BlockFromToEvent event) {
        final Block block = event.getToBlock();
        if (block.getY() < 0) {
            SchedulerCompat.runRegionLater(plugin, block.getLocation(), () -> {
                sendSingleBlockUpdate(block.getLocation(), block.getBlockData());
                invalidateChunkCache(block.getWorld(), block.getX(), block.getZ());
            }, 1L);
        }
    }

    private void sendSingleBlockUpdate(Location loc, BlockData data) {
        try (ByteArrayOutputStream bout = new ByteArrayOutputStream(64);
             DataOutputStream out = new DataOutputStream(bout)) {

            out.writeUTF("block_update");
            out.writeInt(loc.getBlockX());
            out.writeInt(loc.getBlockY());
            out.writeInt(loc.getBlockZ());

            int[] ld = viaIds.toLegacy(data);
            out.writeShort((short) ((ld[1] << 12) | (ld[0] & 0xFFF)));
            byte[] py = bout.toByteArray();

            sendToNearbyPlayers(loc, py);

        } catch (IOException e) {
            severe("Failed to create single block update payload: " + e.getMessage());
        }
    }

    public static int getEmission(BlockData data) {
        if (getLightEmissionMethod != null) {
            try {
                return (int) getLightEmissionMethod.invoke(data);
            } catch (Exception e) {}
        }
        return legacy_light_map.getOrDefault(data.getMaterial(), 0);
    }

    private void handleBlockChange(Location loc, BlockData od, BlockData nd) {
        sendSingleBlockUpdate(loc, nd);
        boolean oe = getEmission(od) > 0;
        boolean ne = getEmission(nd) > 0;
        boolean oo = od.getMaterial().isOccluding();
        boolean no = nd.getMaterial().isOccluding();
        if (oe != ne || oo != no) {
            sendLightUpdate(loc);
        }
    }

    private void sendLightUpdate(Location loc) {
        ObjectOpenHashSet<Coords> stu = new ObjectOpenHashSet<>();
        World w = loc.getWorld();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int ny = by + dy;
                    if (ny < -64 || ny >= 0) continue;

                    stu.add(new Coords(
                        (bx + dx) >> 4,
                        ny >> 4,
                        (bz + dz) >> 4
                    ));
                }
            }
        }
        for (Coords sc : stu) {
            if (!w.isChunkLoaded(sc.x, sc.z)) continue;
            ChunkSnapshot s = w.getChunkAt(sc.x, sc.z).getChunkSnapshot(true, false, false);

            if (chunkProcessor != null && !chunkProcessor.isShutdown()) {
                chunkProcessor.submit(() -> {
                    try {
                        byte[] py = createLightPayload(s, sc);
                        sendToNearbyPlayers(loc, py);
                    } catch (IOException e) {
                        severe("Failed to create lighting payload: " + e.getMessage());
                    }
                });
            }
        }
    }

    private void sendToNearbyPlayers(Location loc, byte[] payload) {
        if (payload == null || loc.getWorld() == null || readyPlayers.isEmpty()) return;

        final World world = loc.getWorld();

        SchedulerCompat.runGlobal(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!player.isOnline() || !isPlayerReady(player)) continue;
                if (!world.equals(player.getWorld())) continue;
                if (player.getLocation().distanceSquared(loc) >= 4096) continue;
                SchedulerCompat.runEntity(player, plugin, () -> {
                    if (!player.isOnline() || !isPlayerReady(player)) return;
                    if (!world.equals(player.getWorld())) return;
                    if (player.getLocation().distanceSquared(loc) >= 4096) return;
                    player.sendPluginMessage(plugin, CHANNEL, payload);
                });
            }
        });
    }

    private byte[] createLightPayload(ChunkSnapshot s, Coords sc) throws IOException {
        try (ByteArrayOutputStream bout = new ByteArrayOutputStream(4120);
             DataOutputStream out = new DataOutputStream(bout)) {
            out.writeUTF("lighting_update");
            out.writeInt(sc.x);
            out.writeInt(sc.z);
            out.writeInt(sc.y);

            byte[] ld = new byte[4096];
            int by = sc.y * 16;
            int i = 0;

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int wy = by + y;
                        int bl = s.getBlockEmittedLight(x, wy, z);
                        int sl = s.getBlockSkyLight(x, wy, z);
                        ld[i++] = (byte) ((sl << 4) | bl);
                    }
                }
            }
            out.write(ld);
            return bout.toByteArray();
        }
    }

    private boolean hasReadyPlayersInWorld(World world) {
        for (UUID playerId : readyPlayers) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline() && world.equals(player.getWorld())) {
                return true;
            }
        }
        return false;
    }

}
