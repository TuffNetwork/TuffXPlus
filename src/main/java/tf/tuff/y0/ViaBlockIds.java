package tf.tuff.y0;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import javax.annotation.Nonnull;

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
import tf.tuff.util.SchedulerCompat;

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

		SchedulerCompat.runGlobalLater(pl, this::initializeMappings, 1L);
	}

	private void initializeMappings() {
		try {
			if (Via.getAPI() == null) {
				plugin.severe("ViaVersion API not found! Is ViaVersion installed?");
				return;
			}
		} catch (IllegalArgumentException e) {
			plugin.severe("ViaVersion API not found! Is ViaVersion installed?");
			return;
		}

		if (!mappingsFile.exists()) {
			plugin.info("Mapping file not found, generating...");
			if (!p.getDataFolder().exists()) {
				p.getDataFolder().mkdirs();
			}
			generateMappings();
		} else {
			plugin.info("Loading mappings from " + mappingsFile.getName());
			loadMappings();
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

	public static record MappingFile (String version, InputStream stream) {}

	public @Nonnull MappingFile findMappingFile(String serverVers) {
		String[] vp = serverVers.split("\\.");

		int maj, min, pat;
		try {
			maj = Integer.parseInt(vp[0]);
			min = Integer.parseInt(vp[1]);
			pat = vp.length > 2 ? Integer.parseInt(vp[2]) : 0;
		} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
			plugin.severe("Could not parse server version string: " + serverVers);
			return new MappingFile(serverVers, p.getResource("mapping-" + serverVers + ".json"));
		}

		plugin.info("Searching for mappings, starting from " + serverVers + " and going down.");

		for (int m = min; m >= 0; m--) {
			int sp = (m == min) ? pat : 11; // if on correct minor version, start from patch, otherwise start from highest possible patch

			for (int pt = sp; pt >= 0; pt--) {
				String vtt = maj + "." + m + "." + pt;
				String fileName = "mapping-" + vtt + ".json";

				InputStream inpStream = p.getResource(fileName);

				if (inpStream != null) {
					if (!vtt.equals(serverVers)) {
						plugin.info("Using fallback mapping file: " + fileName);
					} else {
						plugin.info("Found exact mapping file: " + fileName);
					}
					return new MappingFile(vtt, inpStream);
				}
			}

        	// Check for the major.min version without patch
			String fileName = "mapping-" + maj + "." + m + ".json";
			InputStream inpStream = p.getResource(fileName);
			if (inpStream != null) {
				plugin.info("Using fallback mapping file: " + fileName);
				return  new MappingFile(maj + "." + m, inpStream);
			}

			// Switch to 1.21.x versions after 26.0
			if (maj >= 26 && m == 0) {
				maj = 1;
				m = 22; // will be 21 in the next iteration
			}
		}

		plugin.severe("Could not find any suitable mapping file after checking all versions down to 1.0.0");
		return new MappingFile(serverVers, null);
	}

	private void generateMappings() {
		try {
			MappingFile mapFile = findMappingFile(serverVersion);
			if (mapFile.stream == null) {
				plugin.severe("Failed to find mapping file for " + serverVersion + " in plugin resources!");
				return;
			}

			ObjectMapper mapper = new ObjectMapper();
			@SuppressWarnings("unchecked")
			Map<String, Object> r = mapper.readValue(mapFile.stream, Map.class);
			mapFile.stream.close();
			@SuppressWarnings("unchecked")
			List<String> states = (List<String>) r.get("blockstates");

			if (states == null) {
				plugin.severe("'blockstates' key not found in JSON.");
				return;
			}

			Object2ObjectOpenHashMap<String, int[]> newLegacyMappings = new Object2ObjectOpenHashMap<>();
			plugin.info("Generating legacy mappings for " + states.size() + " block states...");

			ProtocolVersion serverProto = ProtocolVersion.getClosest(mapFile.version); // start from base mappings file version
			ProtocolVersion clientProto = ProtocolVersion.v1_12_2;

			List<ProtocolPathEntry> protoPath = Via.getManager()
				.getProtocolManager()
				.getProtocolPath(clientProto, serverProto);

			if (protoPath == null) {
				plugin.log(Level.SEVERE, "Protocol path is null!");
				return;
			}

			for (int i = 0; i < states.size(); i++) {
				String k = states.get(i).replace("minecraft:", "");
				String blockName = k.contains("[") ? k.substring(0, k.indexOf("[")) : k;

				int[] legacy;

				switch (blockName) {
					case "chest":
						legacy = new int[]{54, 0};
						break;
					case "ender_chest":
						legacy = new int[]{130, 0};
						break;
					case "trapped_chest":
						legacy = new int[]{146, 0};
						break;
					default:
						legacy = convertToLegacy(protoPath, i);
						break;
				}

				newLegacyMappings.put(k, legacy);
			}

			legacyMappings = newLegacyMappings;

			Map<String, Object> outputMap = new Object2ObjectOpenHashMap<>();
			outputMap.put("blockstates", legacyMappings);

			mappingsFile.getParentFile().mkdirs();
			mapper.writerWithDefaultPrettyPrinter().writeValue(mappingsFile, outputMap);
			plugin.info("Successfully wrote mappings to " + mappingsFile.getName());

		} catch (Exception e) {
			plugin.log(Level.SEVERE, "Error generating legacy mappings.", e);
		}
	}

	private void loadMappings() {
		try {
			ObjectMapper mapper = new ObjectMapper();
			@SuppressWarnings("unchecked")
			Map<String, Object> r = mapper.readValue(mappingsFile, Map.class);
			@SuppressWarnings("unchecked")
			Map<String, List<Integer>> readMap = (Map<String, List<Integer>>) r.get("blockstates");

			if (readMap == null) {
				plugin.severe("Invalid format in mappings file. Regenerating...");
				generateMappings();
				return;
			}

			legacyMappings = new Object2ObjectOpenHashMap<>();
			for (Map.Entry<String, List<Integer>> e : readMap.entrySet()) {
				String fullKey = e.getKey();
				List<Integer> ll = e.getValue();

				if (ll != null && ll.size() == 2) {
					String blockName = fullKey.contains("[") ? fullKey.substring(0, fullKey.indexOf("[")) : fullKey;
					int[] finalId;

					switch (blockName) {
						case "chest":
							finalId = new int[]{54, 0};
							break;
						case "ender_chest":
							finalId = new int[]{130, 0};
							break;
						case "trapped_chest":
							finalId = new int[]{146, 0};
							break;
						default:
							finalId = new int[]{ll.get(0), ll.get(1)};
							break;
					}
					legacyMappings.put(fullKey, finalId);
				}
			}
			plugin.info("Loaded " + legacyMappings.size() + " legacy mappings.");
		} catch (IOException e) {
			plugin.log(Level.SEVERE, "Failed to load mappings file.", e);
		}
	}

	public int[] convertToLegacy(List<ProtocolPathEntry> protoPath, int stateId) {
		for (int i = protoPath.size() - 1; i >= 0; i--) {
			ProtocolPathEntry entry = protoPath.get(i);
			Protocol<?, ?, ?, ?> protocol = entry.protocol();

			if (protocol instanceof BackwardsProtocol) {
				BackwardsMappingData mappingData = ((BackwardsProtocol<?, ?, ?, ?>) protocol).getMappingData();
				if (mappingData != null && mappingData.getBlockStateMappings() != null) {
					int newStateId = mappingData.getBlockStateMappings().getNewId(stateId);

					if (newStateId != -1) stateId = newStateId;
				}
			}
		}
		int blockId = stateId >> 4;
		int blockMetadata = stateId & 0xF;
		return new int[]{blockId, blockMetadata};
	}
}
