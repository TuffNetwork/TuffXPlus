package tf.tuff.tuffactions.restrictions;

import java.util.List;
import java.util.logging.Level;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import tf.tuff.TuffX;

public class RestrictionsCommand {
	private final TuffX tuffx;
	private final Restrictions restrictions;

	RestrictionsCommand(Restrictions restrictions, TuffX tuffx) {
		this.restrictions = restrictions;
		this.tuffx = tuffx;
	}
	
	private boolean error(CommandSender sender, String msg) {
		sender.sendMessage("\u00A7c%s".formatted(msg));
		return true;
	}
	private boolean noPermission(CommandSender sender) {
		return error(sender, "You do not have permission to use this command.");
	}
	private boolean invalidUsage(CommandSender sender, String msg) {
		return error(sender, "Invalid usage. %s Use: /restrictions <allow|disallow> <module>".formatted(msg));
	}
	private boolean unknownError(CommandSender sender, Exception e) {
		tuffx.getLogger().log(Level.SEVERE, "An unknown error occurred while executing a restrictions command", e);
		return error(sender, "An unknown error has occurred. Check server logs.");
	}

	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0) return invalidUsage(sender, "'allow' or 'disallow' required.");

		if (args[0].equalsIgnoreCase("disallow")) {
			if (!sender.hasPermission("tuffx.restrictions.command.disallow")) return noPermission(sender);
			if (args.length < 2) return invalidUsage(sender, "Module name required.");
			try {
				@SuppressWarnings("unchecked")
				List<String> config = (List<String>)tuffx.getConfig().getList("restrictions.disallow");
				config.add(args[1]);
				tuffx.getConfig().set("restrictions.disallow", config);
				tuffx.saveConfig();
				restrictions.loadConfig();
				restrictions.sendSingleUpdateToAll(args[1]);
			} catch(Exception e) {
				return unknownError(sender, e);
			}
			sender.sendMessage("\u00A7aModule '%s' added to disallow list.".formatted(args[1]));
			return true;
		} else if (args[0].equalsIgnoreCase("allow")) {
			if (!sender.hasPermission("tuffx.restrictions.command.allow")) return noPermission(sender);
			if (args.length < 2) return invalidUsage(sender, "Module name required.");
			try {
				@SuppressWarnings("unchecked")
				List<String> config = (List<String>)tuffx.getConfig().getList("restrictions.disallow");
				if (!config.contains(args[1])) return error(sender, "Module '%s' not in disallow list. No change.".formatted(args[1]));
				config.remove(args[1]);
				tuffx.getConfig().set("restrictions.disallow", config);
				tuffx.saveConfig();
				restrictions.loadConfig();
				restrictions.sendSingleUpdateToAll(args[1]);
			} catch(Exception e) {
				return unknownError(sender, e);
			}
			sender.sendMessage("\u00A7aModule '%s' removed from disallow list.".formatted(args[1]));
			return true;
		}
		return invalidUsage(sender, "Unknown option.");
	}
}
