package tf.tuff.y0;

import tf.tuff.TuffX;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;

import tf.tuff.util.SchedulerCompat;

public class ChunkPacketListener {

	public final Y0Plugin plugin;

	public ChunkPacketListener(Y0Plugin plugin) {
		this.plugin = plugin;
	}

	public void handleChunk(TuffX plugin, Player player, World world, int chunkX, int chunkZ){
		if (!this.plugin.isPlayerReady(player)) return;

		SchedulerCompat.runRegion(plugin, world, chunkX, chunkZ, () -> {
			if (player.isOnline() && world.isChunkLoaded(chunkX, chunkZ)) {
				Chunk chunk = world.getChunkAt(chunkX, chunkZ);
				this.plugin.processAndSendChunk(player, chunk);
			}
		});
	}
}
