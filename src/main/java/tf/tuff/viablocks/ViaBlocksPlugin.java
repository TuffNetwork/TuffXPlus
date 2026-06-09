package tf.tuff.viablocks;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

import tf.tuff.viablocks.version.VersionAdapter;
import tf.tuff.viablocks.version.modern.ModernAdapter;
import tf.tuff.TuffX;

public final class ViaBlocksPlugin {

    public static final String CLIENTBOUND_CHANNEL = "viablocks:data";
    public static final String SERVERBOUND_CHANNEL = "viablocks:handshake";

    public final Set<UUID> viaBlocksEnabledPlayers = new HashSet<>();
    public CustomBlockListener blockListener;
    static ViaBlocksPlugin instance;

    private File playerDataFile;
    private FileConfiguration playerDataConfig;
    private final Set<UUID> joinedPlayersCache = new HashSet<>();

    private boolean enabled;
    private boolean debug;
    private boolean sendWelcomeBook;

    public VersionAdapter versionAdapter;

    public PaletteManager paletteManager;
    private long updateBatchDelayTicks = 1L;
    public ExecutorService chunkExecutor;
    public boolean isPaper = false;

    public TuffX plugin;

    public ViaBlocksPlugin(TuffX plugin){
        this.plugin = plugin;
    }   

    public void onTuffXReload() {
        loadSyncSettings();

        if (chunkExecutor != null) {
            chunkExecutor.shutdownNow();
        }
        this.chunkExecutor = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors()));

        if (playerDataFile == null) {
            playerDataFile = new File(plugin.getDataFolder(), "players.yml");
        }
        playerDataConfig = YamlConfiguration.loadConfiguration(playerDataFile);
        
        if (blockListener != null) {
            blockListener.clearCache();
        }
        
        info("ViaBlocks reloaded.");
    }

    public void onTuffXEnable() {
        instance = this;

        this.chunkExecutor = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors()));

        this.versionAdapter = new ModernAdapter();

        this.paletteManager = new PaletteManager(this.versionAdapter);

        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
            this.isPaper = true;
            info("Paper detected. Enabling optimized asynchronous scheduling.");
        } catch (ClassNotFoundException e) {
            this.isPaper = false;
            info("Running on Spigot/Bukkit. Using standard scheduler.");
        }

        plugin.saveDefaultConfig();
        loadSyncSettings();
        setupPlayerData();
           

        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CLIENTBOUND_CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, SERVERBOUND_CHANNEL, plugin);

        this.blockListener = new CustomBlockListener(this, this.versionAdapter, this.paletteManager);

        plugin.getCommand("viablocks").setExecutor(plugin);
        info("ViaBlocks has been enabled successfully and is listening for client handshakes.");
    }

    public void handlePacket(Player player, byte[] message) {
        if (!isPlayerEnabled(player) && isEnabled()) {
            debug("Received ViaBlocks handshake from player: " + player.getName() + ". Enabling custom blocks.");
            setPlayerEnabled(player, true);

            blockListener.onViaBlocksPlayerJoin(player);
        }
    }

    public PaletteManager getPaletteManager() {
        return this.paletteManager;
    }

    public long getUpdateBatchDelayTicks() {
        return this.updateBatchDelayTicks;
    }

    private void loadSyncSettings() {
        enabled = plugin.getConfig().getBoolean("viablocks.viablocks-enabled", false);
        debug = plugin.getConfig().getBoolean("viablocks.debug", false);
        sendWelcomeBook = plugin.getConfig().getBoolean("viablocks.send-welcome-book", true);

        String mode = plugin.getConfig().getString("viablocks.sync-mode", "normal");
        if (mode == null) {
            mode = "normal";
        }
        this.updateBatchDelayTicks = mode.equalsIgnoreCase("reduced") ? 10L : 1L;
    }

    public void onTuffXDisable(){
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CLIENTBOUND_CHANNEL); 
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, SERVERBOUND_CHANNEL); 

        if (chunkExecutor != null) {
            chunkExecutor.shutdownNow();
            chunkExecutor = null;
        }

        info("ViaBlocks has been disabled.");
    }
    private void setupPlayerData() {
        playerDataFile = new File(plugin.getDataFolder(), "players.yml");
        if (!playerDataFile.exists()) {
            try {
                playerDataFile.createNewFile();
            } catch (IOException e) {
                severe("Could not create players.yml!");
                e.printStackTrace();
            }
        }
        playerDataConfig = YamlConfiguration.loadConfiguration(playerDataFile);
        joinedPlayersCache.clear();
        for (String uuidStr : playerDataConfig.getStringList("joined-players")) {
            try {
                joinedPlayersCache.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }
    public boolean hasPlayerJoinedBefore(Player player) {
        return joinedPlayersCache.contains(player.getUniqueId());
    }
    public boolean isFirstJoin(Player player) {
        return !hasPlayerJoinedBefore(player);
    }
    public void markPlayerAsJoined(Player player) {
        UUID uuid = player.getUniqueId();
        if (joinedPlayersCache.add(uuid)) {
            List<String> joinedPlayers = playerDataConfig.getStringList("joined-players");
            joinedPlayers.add(uuid.toString());
            playerDataConfig.set("joined-players", joinedPlayers);
            try {
                playerDataConfig.save(playerDataFile);
            } catch (IOException e) {
                severe("Could not save to players.yml!");
                e.printStackTrace();
            }
        }
    }
    public void sendWelcomeGui(Player player) {
        if (!this.sendWelcomeBook) return;
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) return;
        meta.setTitle("ViaBlocks Information");
        meta.setAuthor("ViaBlocks");
        TextComponent welcome = new TextComponent("Welcome to ViaBlocks!");
        welcome.setColor(ChatColor.DARK_AQUA);
        welcome.setBold(true);
        TextComponent body = new TextComponent("\n\nThis feature is in active development!\n\nIf you find any visual bugs or issues, please report them on our ");
        body.setColor(ChatColor.BLACK);
        TextComponent link = new TextComponent("bug tracker");
        link.setColor(ChatColor.BLUE);
        link.setUnderlined(true);
        link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, "https://github.com/TuffNetwork/Tuff-Client-Builds/issues"));
        link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Click to open the bug tracker!").color(ChatColor.GRAY).create()));
        TextComponent disclaimer = new TextComponent("\n\n(Bamboo and kelp are noted.)");
        disclaimer.setColor(ChatColor.DARK_GRAY);
        disclaimer.setItalic(true);
        meta.spigot().addPage(new ComponentBuilder("").append(welcome).append(body).append(link).append(new TextComponent(".")).append(disclaimer).create());
        book.setItemMeta(meta);
        plugin.getServer().getScheduler().runTask(plugin, () -> player.openBook(book));
    }

    public boolean isEnabled() {
        return enabled;
    }
    
    public boolean isPlayerEnabled(Player player) {
        if (player == null) return false;
        return viaBlocksEnabledPlayers.contains(player.getUniqueId());
    }

    public void setPlayerEnabled(Player player, boolean enabled) {
        if (enabled && isEnabled()) {
            viaBlocksEnabledPlayers.add(player.getUniqueId());
        } else {
            viaBlocksEnabledPlayers.remove(player.getUniqueId());
        }
    }

    public CustomBlockListener getBlockListener() {
        return this.blockListener;
    }

    public boolean onTuffXCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be executed by a player.");
            return true;
        }
        Player player = (Player) sender;
        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("get")) {
                if (!player.hasPermission("tuffx.viablocks.command.get")) {
                    player.sendMessage("\u00A7cYou do not have permission to use this command.");
                    return true;
                }
                this.versionAdapter.giveCustomBlocks(player);
                player.sendMessage("\u00A7aYou have been given a set of custom blocks.");
                return true;
            } else if (args[0].equalsIgnoreCase("refresh")) {
                if (!player.hasPermission("tuffx.viablocks.command.refresh")) {
                    player.sendMessage("\u00A7cYou do not have permission to use this command.");
                    return true;
                }
                player.sendMessage("\u00A7aRefreshing modern blocks in your view distance...");
                World world = player.getWorld();
                int viewDistance = this.versionAdapter.getClientViewDistance(player);
                int playerChunkX = player.getLocation().getChunk().getX();
                int playerChunkZ = player.getLocation().getChunk().getZ();
                for (int x = -viewDistance; x <= viewDistance; x++) {
                    for (int z = -viewDistance; z <= viewDistance; z++) {
                        int chunkX = playerChunkX + x;
                        int chunkZ = playerChunkZ + z;
                        if (world.isChunkLoaded(chunkX, chunkZ)) {
                            blockListener.processChunkForSinglePlayer(world.getChunkAt(chunkX, chunkZ), player);
                        }
                    }
                }
                player.sendMessage("\u00A7aRefresh complete!");
                return true;
            }
        }
        player.sendMessage("\u00A7cInvalid usage. Use: /viablocks <get|refresh>");
        return true;
    }

    public boolean isDebug() {
        return debug;
    }
    public void debug(String message) {
        if (isDebug()) info(message);
    }

    public void log(Level level, String msg, Throwable e) {
        plugin.getLogger().log(level, "[ViaBlocks] "+msg, e);
    }
    public void log(Level level, String msg) {
        plugin.getLogger().log(level, "[ViaBlocks] "+msg);
    }
    public void info(String msg) {
        log(Level.INFO, msg);
    }
    public void severe(String msg) {
        log(Level.SEVERE, msg);
    }
}
