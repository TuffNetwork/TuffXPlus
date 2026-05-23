package tf.tuff.tuffactions.restrictions;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import tf.tuff.tuffactions.TuffActionBase;
import tf.tuff.tuffactions.TuffActions;

public class Restrictions extends TuffActionBase {
    private RestrictionsCommand commandHandler;
	private Set<String> disallowed = ConcurrentHashMap.newKeySet();

	private static final List<String> example = List.of("clientbrand");

	public Restrictions(TuffActions plugin) {
		super(plugin, "Restrictions", "restrictions", true);
        this.commandHandler = new RestrictionsCommand(this, plugin.plugin);
		plugin.plugin.getConfig().addDefault("restrictions.disallow", example);
	}

	public void loadConfig() {
		disallowed.clear();
		plugin.info("Loading Restrictions config...");
		List<?> config = plugin.plugin.getConfig().getList("restrictions.disallow");
		for (Object val : config) {
			if (val instanceof String) disallowed.add((String)val);
		}
	}

	@Override
	protected void enable(boolean wasEnabled) {
		loadConfig();
		super.enable(wasEnabled);
	}
	@Override
	protected void disable() {
		disallowed.clear();
		super.disable();
	}

	/*** CUSTOM SERVER-BOUND PACKETS ***/
	public void handleRestrictionsReady(Player player) {
		if (!isEnabled()) return;
		debug("Sending restrictions to %s".formatted(player.getName()));
		try (ByteArrayOutputStream bout = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bout)) {
			out.writeUTF("client_features_all");
			out.writeInt(disallowed.size());

			for (String key : disallowed) {
				out.writeUTF(key);
				out.writeBoolean(false);
			}

			plugin.sendPluginMessage(player, bout.toByteArray());
		} catch (IOException e) {
			debug("Failed to send Restrictions to " + player.getName(), e);
		}
	}

	/*** CUSTOM CLIENT-BOUND PACKETS ***/
	public void sendSingleUpdateToAll(String key) {
		for (UUID uuid : TuffActions.tuffPlayers) {
			Player player = Bukkit.getPlayer(uuid);
			handleSingleUpdate(player, key);
		}
	}

	private void handleSingleUpdate(Player player, String key) {
		if (!isEnabled()) return;
		debug("Sending restriction update for '%s' to %s".formatted(key, player.getName()));
		try (ByteArrayOutputStream bout = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bout)) {
			out.writeUTF("client_feature");
			out.writeUTF(key);
			out.writeBoolean(!disallowed.contains(key));

			plugin.sendPluginMessage(player, bout.toByteArray());
		} catch (IOException e) {
			plugin.log(Level.WARNING, "Failed to send Restriction "+key+" to " + player.getName(), e);
		}
	}

	/*** COMMANDS ***/
	public boolean onTuffXCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!isEnabled()) {
			sender.sendMessage("Restrictions are currently disabled.");
			return true;
		}
		return commandHandler.onCommand(sender, command, label, args);
	}

}