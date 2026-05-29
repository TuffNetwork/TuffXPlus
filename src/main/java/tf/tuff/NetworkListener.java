package tf.tuff;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import org.bukkit.World;
import org.bukkit.entity.Player;

public class NetworkListener implements PacketListener {

    private final TuffX plugin;

    public NetworkListener(TuffX plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            Player player = (Player) event.getPlayer();
            if (player == null) return;

            WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
            int chunkX = wrapper.getColumn().getX();
            int chunkZ = wrapper.getColumn().getZ();

            World world = player.getWorld();
            plugin.y0Plugin.chunkPacketListener.handleChunk(plugin, player, world, chunkX, chunkZ);
        }
    }
}
