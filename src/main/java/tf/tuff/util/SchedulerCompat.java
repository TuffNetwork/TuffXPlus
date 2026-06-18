package tf.tuff.util;

import java.util.concurrent.TimeUnit;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import io.github.retrooper.packetevents.util.folia.FoliaScheduler;
import io.github.retrooper.packetevents.util.folia.TaskWrapper;

public final class SchedulerCompat {

	private SchedulerCompat() {
	}

	public static boolean isFolia() {
		return FoliaScheduler.isFolia();
	}

	public static void runGlobal(Plugin plugin, Runnable task) {
		FoliaScheduler.getGlobalRegionScheduler().execute(plugin, task);
	}

	public static TaskWrapper runGlobalLater(Plugin plugin, Runnable task, long delayTicks) {
		return FoliaScheduler.getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> task.run(), delayTicks);
	}

	public static TaskWrapper runGlobalTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
		return FoliaScheduler.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduledTask -> task.run(), delayTicks, periodTicks);
	}

	public static void runAsync(Plugin plugin, Runnable task) {
		FoliaScheduler.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
	}

	public static TaskWrapper runAsyncLater(Plugin plugin, Runnable task, long delayTicks) {
		return FoliaScheduler.getAsyncScheduler().runDelayed(plugin, scheduledTask -> task.run(), delayTicks * 50L, TimeUnit.MILLISECONDS);
	}

	public static void runRegion(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task) {
		FoliaScheduler.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, task);
	}

	public static TaskWrapper runRegionLater(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task, long delayTicks) {
		return FoliaScheduler.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, scheduledTask -> task.run(), delayTicks);
	}

	public static void runRegion(Plugin plugin, Location location, Runnable task) {
		FoliaScheduler.getRegionScheduler().execute(plugin, location, task);
	}

	public static TaskWrapper runRegionLater(Plugin plugin, Location location, Runnable task, long delayTicks) {
		return FoliaScheduler.getRegionScheduler().runDelayed(plugin, location, scheduledTask -> task.run(), delayTicks);
	}

	public static void runEntity(Entity entity, Plugin plugin, Runnable task) {
		FoliaScheduler.getEntityScheduler().execute(entity, plugin, task, () -> {
		}, 0L);
	}

	public static TaskWrapper runEntityLater(Entity entity, Plugin plugin, Runnable task, long delayTicks) {
		return FoliaScheduler.getEntityScheduler().runDelayed(entity, plugin, scheduledTask -> task.run(), () -> {
		}, delayTicks);
	}

	public static TaskWrapper runEntityTimer(Entity entity, Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
		return FoliaScheduler.getEntityScheduler().runAtFixedRate(entity, plugin, scheduledTask -> task.run(), () -> {
		}, delayTicks, periodTicks);
	}

	public static void sendPluginMessage(Plugin plugin, Player player, String channel, byte[] payload) {
		if (player == null || channel == null || payload == null || !player.isOnline()) return;
		runEntity(player, plugin, () -> {
			if (player.isOnline()) {
				player.sendPluginMessage(plugin, channel, payload);
			}
		});
	}
}
