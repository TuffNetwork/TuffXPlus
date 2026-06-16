package tf.tuff.y0;

import tf.tuff.TuffX;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

public class ChunkPacketListener {

    public final Y0Plugin plugin;

    public ChunkPacketListener(Y0Plugin plugin) {
        this.plugin = plugin;
    }

    public void handleChunk(TuffX plugin, Player player, World world, int chunkX, int chunkZ){
        if (!this.plugin.isPlayerReady(player)) return;

        Location chunkLoc = new Location(world, chunkX << 4, 0, chunkZ << 4);
        plugin.foliaLib.getScheduler().runAtLocation(chunkLoc, t -> {
            if (player.isOnline() && world.isChunkLoaded(chunkX, chunkZ)) {
                Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                this.plugin.processAndSendChunk(player, chunk);
            }
        });
    }
}