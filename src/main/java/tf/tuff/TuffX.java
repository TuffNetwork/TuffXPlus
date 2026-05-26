package tf.tuff;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.EntityToggleSwimEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;

import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import tf.tuff.netty.ChunkInjector;
import tf.tuff.tuffactions.TuffActions;
import tf.tuff.viablocks.ViaBlocksPlugin;
import tf.tuff.viaentities.ViaEntitiesPlugin;
import tf.tuff.y0.Y0Plugin;

public class TuffX extends JavaPlugin implements Listener, PluginMessageListener {

    public ServerRegistry serverRegistry;

    public Y0Plugin y0Plugin;
    public ViaBlocksPlugin viaBlocksPlugin;
    public TuffActions tuffActions;
    public ViaEntitiesPlugin viaEntitiesPlugin;
    public String latestAvailableVersion = null;

    private ChunkInjector chunkInjector;

    @Override
    public void onLoad() {
        this.y0Plugin = new Y0Plugin(this);
        this.viaBlocksPlugin = new ViaBlocksPlugin(this);
        this.tuffActions = new TuffActions(this);
        this.viaEntitiesPlugin = new ViaEntitiesPlugin(this);

        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings().reEncodeByDefault(false)
                .checkForUpdates(false);
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        PacketEvents.getAPI().init();

        y0Plugin.onTuffXEnable();
        tuffActions.onTuffXEnable();
        viaBlocksPlugin.onTuffXEnable();
        viaEntitiesPlugin.onTuffXEnable();

        chunkInjector = new ChunkInjector(viaBlocksPlugin.blockListener, y0Plugin);
        viaBlocksPlugin.blockListener.setChunkInjector(chunkInjector);
        y0Plugin.setChunkInjector(chunkInjector);

        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        PacketEvents.getAPI().getEventManager().registerListener(
            new NetworkListener(this), PacketListenerPriority.NORMAL
        );

        getServer().getPluginManager().registerEvents(this, this);

        setupRegistry();
        lfe();
    }

    private void setupRegistry() {
        if (getConfig().getBoolean("registry.enabled", false)) {
            String url = getConfig().getString("registry.server-url");
            String ws = getConfig().getString("registry.server");

            if (ws != null && !ws.isEmpty() && !ws.equals("wss://urserverip.net")) {
                serverRegistry = new ServerRegistry(this, url, ws);
                serverRegistry.connect();
            }
        }
    }

    @Override
    public void onDisable() {
        y0Plugin.onTuffXDisable();
        viaBlocksPlugin.onTuffXDisable();
        viaEntitiesPlugin.onTuffXDisable();

        if (serverRegistry != null) {
            serverRegistry.disconnect();
            serverRegistry = null;
        }

        PacketEvents.getAPI().terminate();

        getServer().getMessenger().unregisterIncomingPluginChannel(this);
    }

    public void reloadTuffX(){
        saveDefaultConfig();
        reloadConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        if (serverRegistry != null) {
            serverRegistry.disconnect();
            serverRegistry = null;
        }

        setupRegistry();

        viaBlocksPlugin.onTuffXReload();
        y0Plugin.onTuffXReload();
        tuffActions.onTuffXReload();
        viaEntitiesPlugin.onTuffXReload();

        getLogger().info("TuffX reloaded.");
    }

    public boolean TuffXCommand(CommandSender sender, Command command, String label, String[] args){
        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("reload")) {
                if (!(sender instanceof Player)) {
                    reloadTuffX();
                } else {
                    Player player = (Player) sender;
                    if (!player.hasPermission("tuffx.reload")) {
                        player.sendMessage("§cYou do not have permission to use this command.");
                        return false;
                    }
                    reloadTuffX();
                    player.sendMessage("TuffX reloaded.");
                }
            }
        }
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("tuffx")) return TuffXCommand(sender, command, label, args);
        if (command.getName().equalsIgnoreCase("viablocks")) return viaBlocksPlugin.onTuffXCommand(sender, command, label, args);
        if (command.getName().equalsIgnoreCase("restrictions")) return tuffActions.onTuffXCommand(sender, command, label, args);
        return true;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!player.isOnline()) return;

        if (channel.equals("eagler:below_y0")) y0Plugin.handlePacket(player,message);
        else if (channel.equals("viablocks:handshake")) viaBlocksPlugin.handlePacket(player,message);
        else if (channel.equals("eagler:tuffactions")) tuffActions.handlePacket(player,message);
        else if (channel.equals("entities:handshake")) viaEntitiesPlugin.handlePacket(player,message);
        else getLogger().warning("Received plugin message on unknown channel '%s' from %s".formatted(channel, player.getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangeWorld(PlayerChangedWorldEvent e) {
        y0Plugin.handlePlayerChangeWorld(e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent e) {
        viaBlocksPlugin.blockListener.handleBlockForm(e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent e) {
        viaBlocksPlugin.blockListener.handleBlockFade(e);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent e) {
        y0Plugin.handlePlayerJoin(e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent e) {
        viaBlocksPlugin.blockListener.handleBlockGrow(e);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        y0Plugin.handlePlayerQuit(e);
        tuffActions.handlePlayerQuit(e);
        viaBlocksPlugin.blockListener.handlePlayerQuit(e);
        viaEntitiesPlugin.handlePlayerQuit(e);
    }

    @EventHandler
    public void onToggleSwim(EntityToggleSwimEvent e) {
        tuffActions.handleToggleSwim(e);
    }

    @EventHandler
    public void onToggleGlide(EntityToggleGlideEvent e) {
        tuffActions.handleToggleGlide(e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent e) {
        viaBlocksPlugin.blockListener.handleBlockSpread(e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        viaBlocksPlugin.blockListener.handleBlockBreak(e);
        y0Plugin.handleBlockBreak(e);
    }

    @EventHandler
    public void onPlayerInventoryClick(InventoryClickEvent e) {
        tuffActions.handlePlayerInventoryClick(e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        viaBlocksPlugin.blockListener.handleBlockPlace(e);
        y0Plugin.handleBlockPlace(e);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent e) {
        y0Plugin.handleBlockPhysics(e);
        viaBlocksPlugin.blockListener.handleBlockPhysics(e);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent e) {
        y0Plugin.handleChunkLoad(e);
        viaBlocksPlugin.blockListener.handleChunkLoad(e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        viaBlocksPlugin.blockListener.handleBlockExplode(e);
        y0Plugin.handleBlockExplode(e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent e) {
        viaBlocksPlugin.blockListener.handleBlockFromTo(e);
        y0Plugin.handleBlockFromTo(e);
    }

    private void lfe() {
        getLogger().info("");
        getLogger().info("████████╗██╗   ██╗███████╗ ███████╗ ██╗  ██╗");
        getLogger().info("╚══██╔══╝██║   ██║██╔════╝ ██╔════╝ ╚██╗██╔╝");
        getLogger().info("   ██║   ██║   ██║██████╗  ██████╗   ╚███╔╝ ");
        getLogger().info("   ██║   ██║   ██║██╔═══╝  ██╔═══╝   ██╔██╗ ");
        getLogger().info("   ██║   ╚██████╔╝██║      ██║      ██╔╝╚██╗");
        getLogger().info("   ╚═╝    ╚═════╝ ╚═╝      ╚═╝      ╚═╝  ╚═╝");
        getLogger().info("");
        getLogger().info("CREDITS");
        getLogger().info("");
        getLogger().info("Y0 support:");
        getLogger().info("• Below y0 (client + plugin) programmed by Potato (@justatypicalpotato)");
        getLogger().info("• llucasandersen - plugin optimizations");
        getLogger().info("");
        getLogger().info("ViaBlocks:");
        getLogger().info("• ViaBlocks partial plugin and client rewrite by Potato");
        getLogger().info("• llucasandersen (Complex client models and texture fixes,");
        getLogger().info("      optimizations, PacketEvents migration and async safety fixes)");
        getLogger().info("• coleis1op, if ts is driving me crazy, im taking credit");
        getLogger().info("");
        getLogger().info("Other:");
        getLogger().info("• Swimming and creative items programmed by Potato (@justatypicalpotato)");
        getLogger().info("• shaded build, 1.14+ support (before merge) - llucasandersen");
        getLogger().info("• Restrictions - UplandJacob");
        getLogger().info("• Overall plugin merges by Potato");
    }
}
