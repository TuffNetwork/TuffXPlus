package tf.tuff.y0;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viaversion.viabackwards.api.BackwardsProtocol;
import com.viaversion.viabackwards.api.data.BackwardsMappingData;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolPathEntry;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import tf.tuff.TuffX;

public class ViaBlockIds {
    private final TuffX p;
    private final Y0Plugin plugin;
    private final String serverVersion;
    private final File mappingsFile;
    private Object2ObjectOpenHashMap<String, int[]> legacyMappings = new Object2ObjectOpenHashMap<>();

    public ViaBlockIds(TuffX pl) {
        p = pl;
        plugin = pl.y0Plugin;
        serverVersion = getServerMCVersion();
        mappingsFile = new File(pl.getDataFolder(), serverVersion + "-mappings.json");

        plugin.info("Server Minecraft Version: " + serverVersion);

        pl.foliaLib.getScheduler().runLater(t -> initializeMappings(), 1L);
    }

    private void initializeMappings() {
        if (Via.getAPI() == null) {
            plugin.severe("ViaVersion API not found! Is ViaVersion installed?");
            return;
        }

        if (!mappingsFile.exists()) {
            plugin.info("Mapping file not found, generating...");
            if (!p.getDataFolder().exists()) {
                p.getDataFolder().mkdirs();
            }
            generateMappings(mappingsFile);
        } else {
            plugin.info("Loading mappings from " + mappingsFile.getName());
            loadMappings(mappingsFile);
        }
    }

    private static final int[] DEFAULT_LEGACY = {1, 0};

    public int[] toLegacy(String k) {
        int[] result = legacyMappings.get(k);
        return result != null ? result : DEFAULT_LEGACY;
    }

    public int[] toLegacy(BlockData bd) {
        String k = bd.getAsString();
        if (k.startsWith("minecraft:")) {
            k = k.substring(10);
        }
        return toLegacy(k);
    }

    public int[] toLegacy(Block b) {
        return toLegacy(b.getBlockData());
    }

    private String getServerMCVersion() {
        String vs = Bukkit.getServer().getVersion();
        int mi = vs.indexOf("MC: ");
        if (mi != -1) {
            int ei = vs.indexOf(')', mi);
            return ei != -1 ? vs.substring(mi + 4, ei) : vs.substring(mi + 4);
        }
        plugin.log(Level.WARNING, "Could not detect Minecraft version. Defaulting to 1.21.");
        return "1.21";
    }

    private InputStream fmf() {
        String[] vp = serverVersion.split("\\.");

        int maj, min, pat;

        try {
            maj = Integer.parseInt(vp[0]);
            min = Integer.parseInt(vp[1]);
            pat = vp.length > 2 ? Integer.parseInt(vp[2]) : 0;
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            plugin.severe("Could not parse server version string: " + serverVersion);
            return p.getResource("mapping-" + serverVersion + ".json");
        }

        plugin.info("Searching for mappings, starting from " + serverVersion + " and going down.");

        // Direct lookups for the exact server version. The down-walk below assumes the legacy
        // "1.<16+>" scheme (its loop bound is m >= 16), so it can never reach a year-based
        // version like 26.1.2. Try the precise filenames first so a bundled mapping-26.x.json
        // is picked up.
        for (String fn : new String[]{
                "mapping-" + serverVersion + ".json",
                "mapping-" + maj + "." + min + ".json",
                "mapping-" + maj + ".json"}) {
            InputStream is = p.getResource(fn);
            if (is != null) {
                plugin.info((("mapping-" + serverVersion + ".json").equals(fn)
                    ? "Found exact mapping file: " : "Using fallback mapping file: ") + fn);
                return is;
            }
        }

        for (int m = min; m >= 16; m--) {
            int sp = (m == min) ? pat : 9;

            for (int pt = sp; pt >= 0; pt--) {
                String vtt = maj + "." + m + "." + pt;
                String fn = "mapping-" + vtt + ".json";

                InputStream is = p.getResource(fn);

                if (is != null) {
                    if (!vtt.equals(serverVersion)) {
                        plugin.info("Using fallback mapping file: " + fn);
                    } else {
                        plugin.info("Found exact mapping file: " + fn);
                    }
                    return is;
                }
            }

            String mvfn = "mapping-" + maj + "." + m + ".json";
            InputStream is = p.getResource(mvfn);
            if (is != null) {
                plugin.info("Using fallback mapping file: " + mvfn);
                return is;
            }
        }

        plugin.severe("Could not find any suitable mapping file after checking all versions down to " + maj + ".16.0");
        return null;
    }

    /** Fixed legacy ids for blocks whose mapping must not be derived from the palette. */
    private static int[] chestOverride(String blockStateKey) {
        String blockName = blockStateKey.contains("[")
            ? blockStateKey.substring(0, blockStateKey.indexOf("["))
            : blockStateKey;
        switch (blockName) {
            case "chest":         return new int[]{54, 0};
            case "ender_chest":   return new int[]{130, 0};
            case "trapped_chest": return new int[]{146, 0};
            default:              return null;
        }
    }

    private void generateMappings(File f) {
        try (InputStream is = fmf()) {
            if (is == null) {
                plugin.severe("Failed to find mapping-" + serverVersion + ".json in plugin resources!");
                return;
            }

            ObjectMapper m = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> r = m.readValue(is, Map.class);
            Object bs = r.get("blockstates");

            if (bs == null) {
                plugin.severe("'blockstates' key not found in JSON.");
                return;
            }

            Object2ObjectOpenHashMap<String, int[]> nlm = new Object2ObjectOpenHashMap<>();

            if (bs instanceof List) {
                // Raw ViaVersion dump: an ordered list of modern block-state strings where the
                // list index is the modern global block-state id. Convert each via ViaBackwards.
                @SuppressWarnings("unchecked")
                List<String> s = (List<String>) bs;
                plugin.info("Generating legacy mappings for " + s.size() + " block states...");
                for (int i = 0; i < s.size(); i++) {
                    String k = s.get(i).replace("minecraft:", "");
                    int[] override = chestOverride(k);
                    nlm.put(k, override != null ? override : ctl(i));
                }
            } else if (bs instanceof Map) {
                // Already-computed mapping (e.g. a bundled pre-computed cache): block-state
                // string -> [legacy id, meta]. Used directly; no palette conversion needed.
                @SuppressWarnings("unchecked")
                Map<String, List<Integer>> precomputed = (Map<String, List<Integer>>) bs;
                plugin.info("Loading " + precomputed.size() + " pre-computed legacy mappings...");
                for (Map.Entry<String, List<Integer>> e : precomputed.entrySet()) {
                    List<Integer> v = e.getValue();
                    if (v == null || v.size() != 2) continue;
                    String k = e.getKey().replace("minecraft:", "");
                    int[] override = chestOverride(k);
                    nlm.put(k, override != null ? override : new int[]{v.get(0), v.get(1)});
                }
            } else {
                plugin.severe("'blockstates' is neither a list nor a map; unrecognized mapping format.");
                return;
            }

            legacyMappings = nlm;

            Map<String, Object> o = new Object2ObjectOpenHashMap<>();
            o.put("blockstates", legacyMappings);

            f.getParentFile().mkdirs();
            m.writerWithDefaultPrettyPrinter().writeValue(f, o);
            plugin.info("Successfully wrote mappings to " + f.getName());

        } catch (Exception e) {
            plugin.log(Level.SEVERE, "Error generating legacy mappings.", e);
        }
    }

    private void loadMappings(File f) {
        try {
            ObjectMapper m = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> r = m.readValue(f, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, List<Integer>> rm = (Map<String, List<Integer>>) r.get("blockstates");

            if (rm == null) {
                plugin.severe("Invalid format in mappings file. Regenerating...");
                generateMappings(f);
                return;
            }

            legacyMappings = new Object2ObjectOpenHashMap<>();
            for (Map.Entry<String, List<Integer>> e : rm.entrySet()) {
                String fullKey = e.getKey();
                List<Integer> ll = e.getValue();

                if (ll != null && ll.size() == 2) {
                    int[] override = chestOverride(fullKey);
                    legacyMappings.put(fullKey, override != null ? override : new int[]{ll.get(0), ll.get(1)});
                }
            }
            plugin.info("Loaded " + legacyMappings.size() + " legacy mappings.");
        } catch (IOException e) {
            plugin.log(Level.SEVERE, "Failed to load mappings file.", e);
        }
    }

    public int[] ctl(int mbsi) {
        ProtocolVersion serverProto = Via.getAPI().getServerVersion().highestSupportedProtocolVersion();
        ProtocolVersion clientProto = ProtocolVersion.v1_12_2;

        List<ProtocolPathEntry> protoPath = Via.getManager()
            .getProtocolManager()
            .getProtocolPath(clientProto, serverProto);

        if (protoPath == null) {
            plugin.log(Level.WARNING, "Protocol path is null!");
            return new int[]{1, 0};
        }

        int csi = mbsi;

        for (int i = protoPath.size() - 1; i >= 0; i--) {
            ProtocolPathEntry e = protoPath.get(i);
            Protocol<?, ?, ?, ?> pr = e.protocol();

            if (pr instanceof BackwardsProtocol) {
                BackwardsMappingData md = ((BackwardsProtocol<?, ?, ?, ?>) pr).getMappingData();
                if (md != null && md.getBlockStateMappings() != null) {
                    int ni = md.getBlockStateMappings().getNewId(csi);

                    if (ni != -1) csi = ni;
                }
            }
        }
        int bi = csi >> 4;
        int mt = csi & 0xF;
        return new int[]{bi, mt};
    }
}