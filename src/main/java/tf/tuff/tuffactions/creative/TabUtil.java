package tf.tuff.tuffactions.creative;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Level;
import javax.annotation.Nullable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import tf.tuff.tuffactions.TuffActions;

public class TabUtil {
	private final TuffActions plugin;
	private final File mappingFile;
	private Map<String, String> creativeTabMap;

	public TabUtil(TuffActions plugin) {
		this.plugin = plugin;
		this.mappingFile = new File(plugin.plugin.getDataFolder(), "tab-mapping.json");

		setupMappingFile();
		
		loadMapping();
	}

	private void setupMappingFile() {
		if (!mappingFile.exists()) {
			plugin.info("Creative tab mapping not found, creating from resources...");
			plugin.plugin.saveResource("tab-mapping.json", false);
		}
	}

	private void loadMapping() {
		try {
			ObjectMapper mapper = new ObjectMapper();
			
			TypeReference<Map<String, String>> typeRef = new TypeReference<Map<String, String>>() {};
			
			this.creativeTabMap = mapper.readValue(mappingFile, typeRef);
			plugin.info("Successfully loaded " + creativeTabMap.size() + " creative tab mappings.");

		} catch (IOException e) {
			plugin.log(Level.SEVERE, "Failed to load creative tab mapping from file!", e);
			this.creativeTabMap = Collections.emptyMap();
		}
	}

	@Nullable
	public String getCreativeCategory(String material) {
		if (material == null || creativeTabMap.isEmpty()) {
			return null;
		}
		return creativeTabMap.get(material);
	}
}