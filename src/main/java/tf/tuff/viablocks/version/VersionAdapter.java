package tf.tuff.viablocks.version;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import java.util.EnumSet;

public interface VersionAdapter {
	String getBlockDataString(Block block);
	String getMaterialKey(Material material);
	int getClientViewDistance(Player player);
	void giveCustomBlocks(Player player);
	EnumSet<Material> getModernMaterials();
}