package tf.tuff.viablocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import tf.tuff.util.SchedulerCompat;
import tf.tuff.viablocks.version.VersionAdapter;

public class PaletteManager {
    
    ViaBlocksPlugin plugin;

    private final CopyOnWriteArrayList<String> palette = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> stateToIdMap = new ConcurrentHashMap<>();
    private volatile List<String> paletteSnapshot;

    public PaletteManager(VersionAdapter versionAdapter) {
        plugin = ViaBlocksPlugin.instance;
        generate(versionAdapter);
    }

    private void generate(VersionAdapter versionAdapter) {
        plugin.info("Generating ViaBlocks Pre-Defined Palette...");
        
        List<String> initialPalette = new ArrayList<>();

        for (Material material : versionAdapter.getModernMaterials()) {
            if (material.isBlock()) {
                addEntryInternal(initialPalette, versionAdapter.getMaterialKey(material));
            }
        }

        String[] thickness = {"tip", "frustum", "middle", "base"};
        String[] direction = {"up", "down"};
        String[] bools = {"true", "false"};
        String[] facings = {"north", "south", "east", "west"};
        String[] tilts = {"none", "unbalanced", "partial", "full"};

        for (String d : direction) {
            for (String t : thickness) {
                for (String w : bools) {
                    addEntryInternal(initialPalette, "minecraft:pointed_dripstone[thickness=" + t + ",vertical_direction=" + d + ",waterlogged=" + w + "]");
                }
            }
        }

        for (String f : facings) {
            for (String t : tilts) {
                for (String w : bools) {
                    addEntryInternal(initialPalette, "minecraft:big_dripleaf[facing=" + f + ",tilt=" + t + ",waterlogged=" + w + "]");
                }
            }
        }

        for (String f : facings) {
            for (String h : new String[]{"lower", "upper"}) {
                addEntryInternal(initialPalette, "minecraft:small_dripleaf[facing=" + f + ",half=" + h + ",waterlogged=false]");
            }
        }
        
        for (String b : bools) {
            addEntryInternal(initialPalette, "minecraft:cave_vines[age=0,berries=" + b + "]");
            addEntryInternal(initialPalette, "minecraft:cave_vines_plant[berries=" + b + "]");
        }

        palette.addAll(initialPalette);
        paletteSnapshot = new ArrayList<>(palette);
        plugin.info("Palette initialized with " + palette.size() + " entries.");
    }

    private void addEntryInternal(List<String> localPalette, String state) {
        if (!stateToIdMap.containsKey(state)) {
            stateToIdMap.put(state, localPalette.size());
            localPalette.add(state);
        }
    }

    public int getOrCreateId(String state) {
        Integer id = stateToIdMap.get(state);
        if (id != null) {
            return id;
        }

        synchronized (this) {
             id = stateToIdMap.get(state);
             if (id != null) return id;

             int newId = palette.size();
             palette.add(state);
             stateToIdMap.put(state, newId);
             paletteSnapshot = null;

             broadcastNewPaletteEntry(state);
             return newId;
        }
    }

    private void broadcastNewPaletteEntry(String state) {
        if (plugin == null || !plugin.plugin.isEnabled() || !plugin.isEnabled()) return;
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("NEW_PALETTE_ENTRY");
        out.writeUTF(state);
        byte[] data = out.toByteArray();

        SchedulerCompat.runGlobal(plugin.plugin, () -> {
            if (!plugin.plugin.isEnabled() || !plugin.isEnabled()) return;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (plugin.isPlayerEnabled(player)) {
                    SchedulerCompat.sendPluginMessage(plugin.plugin, player, ViaBlocksPlugin.CLIENTBOUND_CHANNEL, data);
                }
            }
        });
    }

    public synchronized int getId(String state) {
        return stateToIdMap.getOrDefault(state, -1);
    }

    public synchronized List<String> getPalette() {
        List<String> snapshot = paletteSnapshot;
        if (snapshot == null) {
            snapshot = new ArrayList<>(palette);
            paletteSnapshot = snapshot;
        }
        return snapshot;
    }
}
