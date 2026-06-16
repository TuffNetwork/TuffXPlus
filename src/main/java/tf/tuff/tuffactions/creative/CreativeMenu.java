package tf.tuff.tuffactions.creative;

import tf.tuff.tuffactions.TuffActionBase;
import tf.tuff.tuffactions.TuffActions;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CreativeMenu extends TuffActionBase {
    private final Set<String> itemMapping = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ItemStack> playerHoldingPlaceholder = new ConcurrentHashMap<>();

    private final TabUtil tabUtil;

    public CreativeMenu(TuffActions actsPlugin) {
        super(actsPlugin, "Creative Items", "creative-items", true);
        this.tabUtil = new TabUtil(actsPlugin);
    }

    @Override
    protected void enable(boolean wasEnabled) {
        if (!wasEnabled) {
            if (itemMapping.isEmpty()) initializeMappings();
        }
        super.enable(wasEnabled);
    }
    @Override
    protected void disable() {
        itemMapping.clear();
        super.disable();
    }

    public void initializeMappings() {
        for (Material m : Material.values()){
            if (m.isItem()){
                itemMapping.add(m.name());
            }
        }
    }

    /*** CUSTOM SERVER-BOUND PACKETS ***/
    public void handleCreativeReady(Player player) {
        if (!isEnabled()) return;
        try (ByteArrayOutputStream bout = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bout)) {
            out.writeUTF("creative_items");
            out.writeInt(itemMapping.size());

            for (String item : itemMapping) {
                out.writeUTF(item);
                String category = this.tabUtil.getCreativeCategory(item);
                out.writeUTF(category != null ? category : "");
            }

            actsPlugin.sendPluginMessage(player, bout.toByteArray());
        } catch (IOException e) {
            debug("Failed to send creative items to " + player.getName(), e);
        }
    }

    public void handlePlaceholderTaken(Player player, String realItemName, int amount) {
        Material realMaterial = Material.getMaterial(realItemName.toUpperCase(Locale.ROOT));
        if (realMaterial != null) {
            ItemStack realItemStack = new ItemStack(realMaterial, amount);
            playerHoldingPlaceholder.put(player.getUniqueId(), realItemStack);
        }
    }

    public void handlePickViablock(Player player, String blockName, int hotbarSlot) {
        Material material = Material.getMaterial(blockName.toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem()) {
            return;
        }
        if (hotbarSlot < 0 || hotbarSlot > 8) {
            hotbarSlot = 0;
        }
        player.getInventory().setItem(hotbarSlot, new ItemStack(material, 1));
    }

    /*** EVENT HANDLERS ***/
    public void onPlayerInventoryClick(InventoryClickEvent event) {
        if (!isEnabled()) return;
        Player player = (Player) event.getWhoClicked();
        UUID playerUUID = player.getUniqueId();

        if (playerHoldingPlaceholder.containsKey(playerUUID)) {
            InventoryAction action = event.getAction();
            if (action == InventoryAction.PLACE_ALL || action == InventoryAction.PLACE_ONE || action == InventoryAction.SWAP_WITH_CURSOR) {

                plugin.foliaLib.getScheduler().runAtEntityLater(player, t -> {
                    ItemStack realItemStack = playerHoldingPlaceholder.get(playerUUID);

                    if (realItemStack != null && event.getClickedInventory() != null) {
                        event.getClickedInventory().setItem(event.getSlot(), realItemStack);
                    }

                    playerHoldingPlaceholder.remove(playerUUID);
                }, 1L);
            }
        }
    }
}
