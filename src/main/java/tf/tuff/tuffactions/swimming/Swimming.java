package tf.tuff.tuffactions.swimming;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityToggleSwimEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import tf.tuff.tuffactions.TuffActionBase;
import tf.tuff.tuffactions.TuffActions;

public class Swimming extends TuffActionBase {

    private final Set<UUID> swimmingPlayers = ConcurrentHashMap.newKeySet();
    private BukkitTask swimStateTask;

    public Swimming(TuffActions plugin) {
        super(plugin, "Swimming", "swimming", true);
    }

    @Override
    protected void disable() {
        if (swimStateTask != null) {
            swimStateTask.cancel();
            swimStateTask = null;
        }
        swimmingPlayers.clear();
        super.disable();
    }

    @Override
    protected void enable(boolean wasEnabled) {
        if (swimStateTask == null) {
            swimStateTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::maintainSwimmingState, 1L, 1L);
        }
        super.enable(wasEnabled);
    }

    /*** CUSTOM, SERVER-BOUND PACKETS ***/
    public void handleSwimReady(Player player) {
        if (!isEnabled()) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (UUID swimmingPlayerId : swimmingPlayers) {
                Player swimmingPlayer = Bukkit.getPlayer(swimmingPlayerId);
                if (swimmingPlayer != null && swimmingPlayer.isOnline() && player.canSee(swimmingPlayer)) {
                    sendSwimState(player, swimmingPlayer, true);
                }
            }
        }, 20L);
    }

    public void handleSwimState(Player player, boolean isSwimming) {
        if (!isEnabled()) return;
        if (isSwimming) {
            swimmingPlayers.add(player.getUniqueId());
        } else {
            swimmingPlayers.remove(player.getUniqueId());
        }
        applySwimmingState(player, isSwimming);
        broadcastSwimState(player, isSwimming);
    }

    public void handleElytraState(Player player, boolean isGliding) {
        if (!isEnabled()) return;
        ItemStack chest = player.getInventory().getChestplate();
        if (chest != null && chest.getType() == Material.ELYTRA) player.setGliding(isGliding);
    }

    /*** EVENT HANDLERS ***/
    public void handleToggleSwim(EntityToggleSwimEvent event) {
        if (!isEnabled()) return;
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        if (!event.isSwimming() && swimmingPlayers.contains(player.getUniqueId())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (swimmingPlayers.contains(player.getUniqueId()) && player.isOnline()) {
                    applySwimmingState(player, true);
                }
            });
        }
    }

    public void handlePlayerQuit(PlayerQuitEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (swimmingPlayers.remove(player.getUniqueId())) {
            broadcastSwimState(player, false);
        }
    }

    /*** CUSTOM CLIENT-BOUND PACKETS ***/
    private void broadcastSwimState(Player subject, boolean isSwimming) {
        for (UUID otherUUID : TuffActions.tuffPlayers) {
            if (!otherUUID.equals(subject.getUniqueId())) {
                Player recipient = Bukkit.getPlayer(otherUUID);
                if (recipient != null && recipient.isOnline() && recipient.canSee(subject)) {
                    sendSwimState(recipient, subject, isSwimming);
                }
            }
        }
    }

    private void sendSwimState(Player recipient, Player subject, boolean isSwimming) {
        if (recipient == null || !recipient.isOnline()) return;
        try (ByteArrayOutputStream bout = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bout)) {
            out.writeUTF("update_other_swim");

            out.writeLong(subject.getUniqueId().getMostSignificantBits());
            out.writeLong(subject.getUniqueId().getLeastSignificantBits());
            out.writeBoolean(isSwimming);

            actsPlugin.sendPluginMessage(recipient, bout.toByteArray());
        } catch (IOException e) {
            debug("Failed to send swim state to " + recipient.getName(), e);
        }
    }

    private void maintainSwimmingState() {
        for (UUID playerId : swimmingPlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                swimmingPlayers.remove(playerId);
                continue;
            }
            if (!player.isInWater()) {
                swimmingPlayers.remove(playerId);
                applySwimmingState(player, false);
                broadcastSwimState(player, false);
                continue;
            }
            applySwimmingState(player, true);
        }
    }

    private void applySwimmingState(Player player, boolean swimming) {
        if (player == null || !player.isOnline()) return;
        if (swimming && !player.isInWater()) return;
        if (player.isSwimming() != swimming) {
            player.setSwimming(swimming);
        }
    }
}
