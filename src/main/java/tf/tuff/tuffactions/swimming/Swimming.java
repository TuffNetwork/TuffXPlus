package tf.tuff.tuffactions.swimming;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.EntityToggleSwimEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import tf.tuff.tuffactions.TuffActionBase;
import tf.tuff.tuffactions.TuffActions;

public class Swimming extends TuffActionBase {

    private final Set<UUID> swimmingPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> glidingPlayers = ConcurrentHashMap.newKeySet();

    public Swimming(TuffActions plugin) {
        super(plugin, "Swimming", "swimming", true);
    }

    @Override
    protected void disable() {
        swimmingPlayers.clear();
        glidingPlayers.clear();
        super.disable();
    }

    /*** CUSTOM, SERVER-BOUND PACKETS ***/
    public void handleSwimReady(Player player) {
        if (!isEnabled()) return;
        Player newPlayer = player;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (UUID swimmingPlayerId : swimmingPlayers) {
                Player swimmingPlayer = Bukkit.getPlayer(swimmingPlayerId);
                if (swimmingPlayer != null && swimmingPlayer.isOnline()) {
                    sendSwimState(newPlayer, swimmingPlayer, true);
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
        player.setSwimming(isSwimming);
        broadcastSwimState(player, isSwimming);
    }

    public void handleElytraState(Player player, boolean isGliding) {
        if (!isEnabled()) return;
        if (isGliding) {
            glidingPlayers.add(player.getUniqueId());
        } else {
            glidingPlayers.remove(player.getUniqueId());
        }
        player.setGliding(isGliding);
    }

    /*** EVENT HANDLERS ***/
    public void handleToggleSwim(EntityToggleSwimEvent event) {
        if (!isEnabled()) return;
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        if (!event.isSwimming() && swimmingPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    public void handleToggleGlide(EntityToggleGlideEvent event) {
        if (!isEnabled()) return;
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        if (!event.isGliding() && glidingPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    public void handleSwimQuit(PlayerQuitEvent event) {
        if (!isEnabled()) return;
        Player player = event.getPlayer();
        if (swimmingPlayers.remove(player.getUniqueId())) {
            broadcastSwimState(player, false);
        }
        glidingPlayers.remove(player.getUniqueId());
        TuffActions.tuffPlayers.remove(player.getUniqueId());
    }

    /*** CUSTOM CLIENT-BOUND PACKETS ***/
    private void broadcastSwimState(Player subject, boolean isSwimming) {
        for (UUID otherUUID : TuffActions.tuffPlayers) {
            if (!otherUUID.equals(subject.getUniqueId())) {
                Player recipient = Bukkit.getPlayer(otherUUID);
                if (recipient != null && recipient.isOnline()) {
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
}
