package tf.tuff.viaentities;

import io.netty.channel.ChannelHandler;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import tf.tuff.netty.BaseInjector;
import tf.tuff.util.SchedulerCompat;

public class EntityInjector extends BaseInjector {

	private final ViaEntitiesPlugin plugin;

	public EntityInjector(ViaEntitiesPlugin plugin) {
		super("viaentities_handler");
		this.plugin = plugin;
	}

	@Override
	protected ChannelHandler createHandler(Player player) {
		return new EntityDataHandler(plugin, player);
	}

	@Override
	protected void onPostInject(Player player) {
		SchedulerCompat.runEntity(player, plugin.plugin, () -> sendExistingEntities(player));
	}

	private void sendExistingEntities(Player player) {
		int viewDistance = player.getWorld().getViewDistance() * 16;

		for (Entity entity : player.getNearbyEntities(viewDistance, viewDistance, viewDistance)) {
			if (entity.equals(player)) continue;
			if (entity instanceof Player) continue;

			double distance = entity.getLocation().distance(player.getLocation());
			if (distance > viewDistance) continue;

			String entityType = entity.getType().getKey().toString();
			if (plugin.entityMappingManager.isModernEntity(entityType)) {
				sendEntityData(player, entity.getEntityId(), entityType, entity);
			}
		}
	}

	public void sendEntityData(Player player, int entityId, String entityType, Entity entity) {
		if (!plugin.isPlayerEnabled(player.getUniqueId())) return;

		int paletteIndex = plugin.entityMappingManager.getEntityIndex(entityType);
		if (paletteIndex == -1) return;

		com.google.common.io.ByteArrayDataOutput out = com.google.common.io.ByteStreams.newDataOutput();
		out.writeUTF("SPAWN_ENTITY");
		out.writeInt(entityId);
		out.writeShort(paletteIndex);
		out.writeDouble(entity.getLocation().getX());
		out.writeDouble(entity.getLocation().getY());
		out.writeDouble(entity.getLocation().getZ());
		out.writeFloat(entity.getLocation().getYaw());
		out.writeFloat(entity.getLocation().getPitch());

		SchedulerCompat.sendPluginMessage(plugin.plugin, player, ViaEntitiesPlugin.CLIENTBOUND_CHANNEL, out.toByteArray());
	}
}
