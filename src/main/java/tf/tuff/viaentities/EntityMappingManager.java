package tf.tuff.viaentities;

import java.io.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;

public class EntityMappingManager {
	private final List<String> modernEntities = new ArrayList<>();
	private final Map<String, Integer> entityToIndex = new HashMap<>();
	private final Set<String> modernEntitySet = new HashSet<>();
	private final Map<String, EntityInfo> entityInfoMap = new HashMap<>();

	public EntityMappingManager() {
		loadEntityMappingsFromJSON();
	}

	private void loadEntityMappingsFromJSON() {
		try (InputStream is = getClass().getClassLoader().getResourceAsStream("entity_mappings.json")) {
			if (is == null) {
				loadFallbackEntities();
				return;
			}

			ObjectMapper mapper = new ObjectMapper();
			JsonNode root = mapper.readTree(is);

			JsonNode modernEntitiesNode = root.get("modern_entities");
			JsonNode entitySizeNode = root.get("entity_size");

			if (modernEntitiesNode != null) {
				Iterator<Map.Entry<String, JsonNode>> fields = modernEntitiesNode.fields();
				while (fields.hasNext()) {
					Map.Entry<String, JsonNode> entry = fields.next();
					String entityType = entry.getKey();
					JsonNode info = entry.getValue();

					String addedVersion = info.has("added") ? info.get("added").asText() : "1.13";
					String model = info.has("model") && !info.get("model").isNull() ? info.get("model").asText() : null;
					boolean animated = info.has("animated") && info.get("animated").asBoolean();

					double width = 1.0;
					double height = 1.0;
					if (entitySizeNode != null && entitySizeNode.has(entityType)) {
						JsonNode sizeInfo = entitySizeNode.get(entityType);
						width = sizeInfo.has("width") ? sizeInfo.get("width").asDouble() : 1.0;
						height = sizeInfo.has("height") ? sizeInfo.get("height").asDouble() : 1.0;
					}

					addEntity(entityType, new EntityInfo(addedVersion, model, animated, width, height));
				}
			}

		} catch (Exception e) {
			loadFallbackEntities();
		}
	}

	private void loadFallbackEntities() {
		addEntity("minecraft:allay", new EntityInfo("1.19", "allay", true, 0.35, 0.6));
		addEntity("minecraft:axolotl", new EntityInfo("1.17", "axolotl", true, 0.75, 0.42));
		addEntity("minecraft:bee", new EntityInfo("1.15", "bee", true, 0.7, 0.6));
		addEntity("minecraft:camel", new EntityInfo("1.20", "camel", true, 1.7, 2.375));
		addEntity("minecraft:cat", new EntityInfo("1.14", "cat", true, 0.6, 0.7));
		addEntity("minecraft:fox", new EntityInfo("1.14", "fox", true, 0.6, 0.7));
		addEntity("minecraft:frog", new EntityInfo("1.19", "frog", true, 0.5, 0.5));
		addEntity("minecraft:goat", new EntityInfo("1.17", "goat", true, 0.9, 1.3));
		addEntity("minecraft:hoglin", new EntityInfo("1.16", "hoglin", true, 1.4, 1.4));
		addEntity("minecraft:piglin", new EntityInfo("1.16", "piglin", true, 0.6, 1.95));
		addEntity("minecraft:strider", new EntityInfo("1.16", "strider", true, 0.9, 1.7));
		addEntity("minecraft:warden", new EntityInfo("1.19", "warden", true, 0.9, 2.9));
		addEntity("minecraft:sniffer", new EntityInfo("1.20", "sniffer", true, 1.9, 1.75));
		addEntity("minecraft:breeze", new EntityInfo("1.21", "breeze", true, 0.6, 1.77));
		addEntity("minecraft:wind_charge", new EntityInfo("1.21", "wind_charge", false, 0.3125, 0.3125));
		addEntity("minecraft:breeze_wind_charge", new EntityInfo("1.21", "breeze_wind_charge", false, 0.3125, 0.3125));
		addEntity("minecraft:armadillo", new EntityInfo("1.20.5", "armadillo", true, 0.7, 0.65));
		addEntity("minecraft:bogged", new EntityInfo("1.21", "bogged", true, 0.6, 1.99));
		addEntity("minecraft:phantom", new EntityInfo("1.13", "phantom", true, 0.9, 0.5));
		addEntity("minecraft:dolphin", new EntityInfo("1.13", "dolphin", true, 0.9, 0.6));
		addEntity("minecraft:drowned", new EntityInfo("1.13", "drowned", true, 0.6, 1.95));
		addEntity("minecraft:cod", new EntityInfo("1.13", "cod", true, 0.5, 0.3));
		addEntity("minecraft:salmon", new EntityInfo("1.13", "salmon", true, 0.7, 0.4));
		addEntity("minecraft:tropical_fish", new EntityInfo("1.13", "tropical_fish", true, 0.5, 0.4));
		addEntity("minecraft:pufferfish", new EntityInfo("1.13", "pufferfish", true, 0.7, 0.7));
		addEntity("minecraft:turtle", new EntityInfo("1.13", "turtle", true, 1.2, 0.4));
		addEntity("minecraft:trident", new EntityInfo("1.13", "trident", false, 0.5, 0.5));
		addEntity("minecraft:thrown_trident", new EntityInfo("1.13", "trident", false, 0.5, 0.5));
		addEntity("minecraft:glow_squid", new EntityInfo("1.17", "glow_squid", true, 0.8, 0.8));
		addEntity("minecraft:glow_item_frame", new EntityInfo("1.17", "glow_item_frame", false, 0.5, 0.5));
		addEntity("minecraft:tadpole", new EntityInfo("1.19", "tadpole", true, 0.4, 0.3));
		addEntity("minecraft:chest_boat", new EntityInfo("1.19", "chest_boat", false, 1.375, 0.5625));
		addEntity("minecraft:piglin_brute", new EntityInfo("1.16", "piglin_brute", true, 0.6, 1.95));
		addEntity("minecraft:zoglin", new EntityInfo("1.16", "zoglin", true, 1.4, 1.4));
	}

	private void addEntity(String entityType, EntityInfo info) {
		if (!entityToIndex.containsKey(entityType)) {
			entityToIndex.put(entityType, modernEntities.size());
			modernEntities.add(entityType);
			modernEntitySet.add(entityType);
			entityInfoMap.put(entityType, info);
		}
	}

	public boolean isModernEntity(String entityType) {
		return modernEntitySet.contains(entityType);
	}

	public int getEntityIndex(String entityType) {
		return entityToIndex.getOrDefault(entityType, -1);
	}

	public String getEntityByIndex(int index) {
		if (index >= 0 && index < modernEntities.size()) {
			return modernEntities.get(index);
		}
		return null;
	}

	public EntityInfo getEntityInfo(String entityType) {
		return entityInfoMap.get(entityType);
	}

	public List<String> getAllModernEntities() {
		return Collections.unmodifiableList(modernEntities);
	}

	public int getModernEntityCount() {
		return modernEntities.size();
	}

	public static class EntityInfo {
		public final String addedVersion;
		public final String model;
		public final boolean animated;
		public final double width;
		public final double height;

		public EntityInfo(String addedVersion, String model, boolean animated, double width, double height) {
			this.addedVersion = addedVersion;
			this.model = model;
			this.animated = animated;
			this.width = width;
			this.height = height;
		}
	}
}
