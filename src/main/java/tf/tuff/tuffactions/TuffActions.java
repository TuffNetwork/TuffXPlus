package tf.tuff.tuffactions;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityToggleSwimEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage;

import tf.tuff.TuffX;
import tf.tuff.tuffactions.creative.CreativeMenu;
import tf.tuff.tuffactions.restrictions.Restrictions;
import tf.tuff.tuffactions.swimming.Swimming;

public class TuffActions {

	public static final String CHANNEL = "eagler:tuffactions";

	private Swimming swimmingManager;
	private CreativeMenu creativeManager;
	private Restrictions restrictions;
	
	public final TuffX plugin;

	public static final Set<UUID> tuffPlayers = ConcurrentHashMap.newKeySet();

	public TuffActions(TuffX plugin){
		this.plugin = plugin;
	}

	private void loadConfig() {
		info("TuffActions has been enabled");
		info("Enabling features...");

		swimmingManager.onConfigLoad();
		creativeManager.onConfigLoad();
		restrictions.onConfigLoad();
	}

	public void onTuffXReload() {
		loadConfig();

		info("Misc features reloaded.");
	}

	public void onTuffXEnable() {
		this.swimmingManager = new Swimming(this);
		this.creativeManager = new CreativeMenu(this);
		this.restrictions = new Restrictions(this);

		plugin.getConfig().options().copyDefaults(true);
		loadConfig();
		info("Finished enabling features.");

		plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "eagler:tuffactions");
		plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, "eagler:tuffactions", plugin);
	}

	public boolean onTuffXCommand(CommandSender sender, Command command, String label, String[] args) {
		if (command.getName().equalsIgnoreCase("restrictions")) return this.restrictions.onTuffXCommand(sender, command, label, args);
		return true;
	}

	public void handlePacket(Player player, byte[] message) {
		if (player == null || message == null || message.length < 13) return;
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
			in.readInt();
			in.readInt();
			in.readInt();
			int actionLength = in.readUnsignedByte();
			if (actionLength == 0 || in.available() < actionLength) return;
			byte[] actionBytes = new byte[actionLength];
			in.readFully(actionBytes);
			String action = new String(actionBytes, StandardCharsets.UTF_8);

			tuffPlayers.add(player.getUniqueId());

			if ("swimming_state".equals(action)) {
				swimmingManager.handleSwimState(player, in.readBoolean());
			} else if ("elytra_state".equals(action)) {
				swimmingManager.handleElytraState(player, in.readBoolean());
			} else if ("swim_ready".equals(action)){
				swimmingManager.handleSwimReady(player);
			} else if ("creative_ready".equals(action)){
				creativeManager.handleCreativeReady(player);
			} else if ("give_creative_item".equals(action)){
				if (!creativeManager.isEnabled()) return;
				if (player.getGameMode() != GameMode.CREATIVE) return;
				int itemLength = in.readUnsignedByte();
				if (in.available() < itemLength + Integer.BYTES) return;
				byte[] itemBytes = new byte[itemLength];
				in.readFully(itemBytes);
				String item = new String(itemBytes, StandardCharsets.UTF_8);
				int amount = in.readInt();
				creativeManager.handlePlaceholderTaken(player, item, amount);
			} else if ("pick_viablock".equals(action)){
				if (!creativeManager.isEnabled()) return;
				if (player.getGameMode() != GameMode.CREATIVE) return;
				int blockLength = in.readUnsignedByte();
				if (in.available() < blockLength + 1) return;
				byte[] blockBytes = new byte[blockLength];
				in.readFully(blockBytes);
				String blockName = new String(blockBytes, StandardCharsets.UTF_8);
				int hotbarSlot = in.readUnsignedByte();
				creativeManager.handlePickViablock(player, blockName, hotbarSlot);
			} else if ("restrictions_ready".equals(action)) {
				restrictions.handleRestrictionsReady(player);
			}
		} catch (IOException e) {
			log(Level.WARNING, "Failed to read a plugin message from " + player.getName(), e);
		}
	}

	public void sendPluginMessage(Player player, byte[] payload) {
		sendPluginMessage(player, CHANNEL, payload);
	}

	public void sendPluginMessage(Player player, String channel, byte[] payload) {
		if (player == null || payload == null || !player.isOnline() || PacketEvents.getAPI() == null || !PacketEvents.getAPI().isInitialized()) return;
		WrapperPlayServerPluginMessage packet = new WrapperPlayServerPluginMessage(channel, payload);
		PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
	}

	public void handlePlayerQuit(PlayerQuitEvent event) {
		swimmingManager.handlePlayerQuit(event);
		tuffPlayers.remove(event.getPlayer().getUniqueId());
	}

	public void handleToggleSwim(EntityToggleSwimEvent event) {
		swimmingManager.handleToggleSwim(event);
	}

	public void handlePlayerInventoryClick(InventoryClickEvent event) {
		creativeManager.onPlayerInventoryClick(event);
	}

	public void log(Level level, String msg, Throwable e) {
		plugin.getLogger().log(level, "[TuffActions] "+msg, e);
	}
	public void log(Level level, String msg) {
		plugin.getLogger().log(level, "[TuffActions] "+msg);
	}
	public void info(String msg) {
		log(Level.INFO, msg);
	}
}
