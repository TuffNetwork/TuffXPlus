package tf.tuff.viaentities;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.bukkit.entity.Player;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import tf.tuff.util.SchedulerCompat;

public class EntityDataHandler extends ChannelOutboundHandlerAdapter {

    private final ViaEntitiesPlugin plugin;
    private final Player player;
    private final EntityMappingManager entityMappingManager;

    public EntityDataHandler(ViaEntitiesPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.entityMappingManager = plugin.entityMappingManager;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        String className = msg.getClass().getName();
        String simpleClassName = msg.getClass().getSimpleName();

        if (className.contains("Bundle") || simpleClassName.contains("Bundle")) {
            try {
                Iterable<?> packets = null;

                for (java.lang.reflect.Method m : msg.getClass().getMethods()) {
                    if (m.getName().equals("subPackets") && m.getParameterCount() == 0) {
                        Object result = m.invoke(msg);
                        if (result instanceof Iterable) {
                            packets = (Iterable<?>) result;
                            break;
                        }
                    }
                }

                if (packets == null) {
                    Class<?> clazz = msg.getClass();
                    outer:
                    while (clazz != null) {
                        for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                            f.setAccessible(true);
                            Object val = f.get(msg);
                            if (val instanceof Iterable) {
                                packets = (Iterable<?>) val;
                                break outer;
                            }
                        }
                        clazz = clazz.getSuperclass();
                    }
                }

                if (packets != null) {
                    for (Object subPacket : packets) {
                        String subClass = subPacket.getClass().getSimpleName();
                        String subFullClass = subPacket.getClass().getName();

                        if (subClass.contains("Add") || subClass.contains("Spawn") ||
                            subFullClass.contains("AddEntity") || subFullClass.contains("SpawnEntity")) {
                            handleSpawnPacket(subPacket);
                        }
                    }
                }
            } catch (Exception e) {
            }
        }

        boolean isSpawnPacket = className.contains("SpawnEntity") || className.contains("AddEntity") ||
            simpleClassName.equals("PacketPlayOutSpawnEntity") ||
            simpleClassName.equals("PacketPlayOutSpawnEntityLiving") ||
            simpleClassName.equals("ClientboundAddEntityPacket") ||
            simpleClassName.equals("ClientboundAddMobPacket");

        if (!isSpawnPacket) {
            try {
                java.lang.reflect.Method getTypeMethod = msg.getClass().getMethod("getType");
                java.lang.reflect.Method getIdMethod = msg.getClass().getMethod("getId");
                if (getTypeMethod != null && getIdMethod != null) {
                    Object typeResult = getTypeMethod.invoke(msg);
                    if (typeResult != null && typeResult.toString().contains("entity")) {
                        isSpawnPacket = true;
                    }
                }
            } catch (NoSuchMethodException e) {
            } catch (Exception e) {
            }
        }

        if (isSpawnPacket) {
            try {
                handleSpawnPacket(msg);
            } catch (Exception e) {
            }
        } else if (className.contains("EntityMetadata") || className.contains("SetEntityData") ||
                   simpleClassName.equals("PacketPlayOutEntityMetadata") ||
                   simpleClassName.equals("ClientboundSetEntityDataPacket")) {
            try {
                int entityId = getIntField(msg, "a", "id", "entityId");
                if (entityId != -1) {
                    sendEntityMetadata(entityId, null);
                }
            } catch (Exception e) {
            }
        } else if (className.contains("Animation") ||
                   simpleClassName.equals("PacketPlayOutAnimation") ||
                   simpleClassName.equals("ClientboundAnimatePacket")) {
            try {
                int entityId = getIntField(msg, "a", "id", "entityId");
                int animationType = getIntField(msg, "b", "action", "animationType");
                if (entityId != -1) {
                    sendEntityAnimation(entityId, animationType);
                }
            } catch (Exception e) {
            }
        } else if (className.contains("EntityDestroy") || className.contains("RemoveEntities") ||
                   simpleClassName.equals("PacketPlayOutEntityDestroy") ||
                   simpleClassName.equals("ClientboundRemoveEntitiesPacket")) {
            try {
                handleDestroyPacket(msg);
            } catch (Exception e) {
            }
        }

        super.write(ctx, msg, promise);
    }

    private void handleSpawnPacket(Object msg) throws Exception {
        int entityId = -1;
        Object entityTypeObj = null;
        double x = 0, y = 0, z = 0;
        float yaw = 0, pitch = 0;

        java.util.List<Double> doubles = new java.util.ArrayList<>();
        java.util.List<Byte> bytes = new java.util.ArrayList<>();

        for (java.lang.reflect.Field field : msg.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(msg);
            String typeName = field.getType().getName();

            if (value == null) continue;

            if (typeName.contains("EntityType") || typeName.contains("EntityTypes")) {
                entityTypeObj = value;
            } else if (value instanceof Integer && entityId == -1) {
                entityId = (Integer) value;
            } else if (value instanceof Double) {
                doubles.add((Double) value);
            } else if (value instanceof Byte) {
                bytes.add((Byte) value);
            }
        }

        if (doubles.size() >= 3) {
            x = doubles.get(0);
            y = doubles.get(1);
            z = doubles.get(2);
        }

        if (bytes.size() >= 2) {
            pitch = bytes.get(0) * 360.0f / 256.0f;
            yaw = bytes.get(1) * 360.0f / 256.0f;
        }

        if (entityId == -1) return;
        if (entityTypeObj == null) return;

        String entityTypeStr = entityTypeObj.toString();
        String entityTypeName = extractEntityTypeName(entityTypeStr);

        if (entityMappingManager.isModernEntity(entityTypeName)) {
            sendEntitySpawn(entityId, entityTypeName, x, y, z, yaw, pitch);
        }
    }

    private void handleDestroyPacket(Object msg) throws Exception {
        for (java.lang.reflect.Field field : msg.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(msg);
            if (value instanceof it.unimi.dsi.fastutil.ints.IntList) {
                it.unimi.dsi.fastutil.ints.IntList idList = (it.unimi.dsi.fastutil.ints.IntList) value;
                for (int i = 0; i < idList.size(); i++) {
                    sendEntityDestroy(idList.getInt(i));
                }
                return;
            } else if (value instanceof int[]) {
                for (int id : (int[]) value) {
                    sendEntityDestroy(id);
                }
                return;
            } else if (value instanceof java.util.List) {
                for (Object item : (java.util.List<?>) value) {
                    if (item instanceof Integer) {
                        sendEntityDestroy((Integer) item);
                    }
                }
                return;
            }
        }
    }

    private int getIntField(Object msg, String... fieldNames) {
        for (String name : fieldNames) {
            try {
                java.lang.reflect.Field field = msg.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(msg);
                if (value instanceof Integer) return (Integer) value;
                if (value instanceof Number) return ((Number) value).intValue();
            } catch (Exception ignored) {}
        }
        return -1;
    }

    private double getDoubleField(Object msg, String... fieldNames) {
        for (String name : fieldNames) {
            try {
                java.lang.reflect.Field field = msg.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(msg);
                if (value instanceof Double) return (Double) value;
                if (value instanceof Number) return ((Number) value).doubleValue();
            } catch (Exception ignored) {}
        }
        return 0.0;
    }

    private float getAngleField(Object msg, String... fieldNames) {
        for (String name : fieldNames) {
            try {
                java.lang.reflect.Field field = msg.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(msg);
                if (value instanceof Byte) return ((Byte) value) * 360.0f / 256.0f;
                if (value instanceof Float) return (Float) value;
                if (value instanceof Number) return ((Number) value).floatValue();
            } catch (Exception ignored) {}
        }
        return 0.0f;
    }

    private Object getField(Object msg, String... fieldNames) {
        for (String name : fieldNames) {
            try {
                java.lang.reflect.Field field = msg.getClass().getDeclaredField(name);
                field.setAccessible(true);
                return field.get(msg);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String extractEntityTypeName(String typeStr) {
        if (typeStr == null) return null;

        if (typeStr.startsWith("entity.minecraft.")) {
            String name = typeStr.substring("entity.minecraft.".length());
            return "minecraft:" + name;
        }

        if (typeStr.contains("ResourceKey[minecraft:entity_type / minecraft:")) {
            int start = typeStr.indexOf("minecraft:", typeStr.indexOf("minecraft:") + 10) + 10;
            int end = typeStr.indexOf("]", start);
            if (end > start) {
                return "minecraft:" + typeStr.substring(start, end);
            }
        }

        if (typeStr.contains("entity_type.minecraft.")) {
            int start = typeStr.indexOf("entity_type.minecraft.") + 22;
            int end = typeStr.length();
            for (int i = start; i < typeStr.length(); i++) {
                char c = typeStr.charAt(i);
                if (!Character.isLetterOrDigit(c) && c != '_') {
                    end = i;
                    break;
                }
            }
            return "minecraft:" + typeStr.substring(start, end);
        }

        if (typeStr.startsWith("minecraft:")) {
            return typeStr;
        }

        if (typeStr.contains("minecraft:")) {
            int start = typeStr.indexOf("minecraft:");
            int end = typeStr.length();
            for (int i = start + 10; i < typeStr.length(); i++) {
                char c = typeStr.charAt(i);
                if (!Character.isLetterOrDigit(c) && c != '_' && c != ':') {
                    end = i;
                    break;
                }
            }
            return typeStr.substring(start, end);
        }

        return typeStr;
    }

    private void sendEntitySpawn(int entityId, String entityType, double x, double y, double z, float yaw, float pitch) {
        if (!plugin.isPlayerEnabled(player.getUniqueId())) return;

        int paletteIndex = entityMappingManager.getEntityIndex(entityType);
        if (paletteIndex == -1) return;

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("SPAWN_ENTITY");
        out.writeInt(entityId);
        out.writeShort(paletteIndex);
        out.writeDouble(x);
        out.writeDouble(y);
        out.writeDouble(z);
        out.writeFloat(yaw);
        out.writeFloat(pitch);

        byte[] data = out.toByteArray();
        SchedulerCompat.sendPluginMessage(plugin.plugin, player, ViaEntitiesPlugin.CLIENTBOUND_CHANNEL, data);
    }

    private void sendEntityMetadata(int entityId, Object packedItems) {
        if (!plugin.isPlayerEnabled(player.getUniqueId())) return;

        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("ENTITY_METADATA");
            out.writeInt(entityId);

            if (packedItems instanceof java.util.List) {
                java.util.List<?> items = (java.util.List<?>) packedItems;
                out.writeInt(items.size());

                for (Object item : items) {
                    java.lang.reflect.Method getIdMethod = item.getClass().getMethod("id");
                    int metaId = (int) getIdMethod.invoke(item);
                    out.writeInt(metaId);

                    java.lang.reflect.Method getValueMethod = item.getClass().getMethod("value");
                    Object value = getValueMethod.invoke(item);

                    if (value instanceof Boolean) {
                        out.writeByte(0);
                        out.writeBoolean((Boolean) value);
                    } else if (value instanceof Integer) {
                        out.writeByte(1);
                        out.writeInt((Integer) value);
                    } else if (value instanceof Float) {
                        out.writeByte(2);
                        out.writeFloat((Float) value);
                    } else if (value instanceof String) {
                        out.writeByte(3);
                        out.writeUTF((String) value);
                    } else if (value instanceof Byte) {
                        out.writeByte(4);
                        out.writeByte((Byte) value);
                    } else {
                        out.writeByte(-1);
                    }
                }
            } else {
                out.writeInt(0);
            }

            byte[] data = out.toByteArray();
            SchedulerCompat.sendPluginMessage(plugin.plugin, player, ViaEntitiesPlugin.CLIENTBOUND_CHANNEL, data);
        } catch (Exception e) {
        }
    }

    private void sendEntityAnimation(int entityId, int animationType) {
        if (!plugin.isPlayerEnabled(player.getUniqueId())) return;

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("ENTITY_ANIMATION");
        out.writeInt(entityId);
        out.writeInt(animationType);

        byte[] data = out.toByteArray();
        SchedulerCompat.sendPluginMessage(plugin.plugin, player, ViaEntitiesPlugin.CLIENTBOUND_CHANNEL, data);
    }

    private void sendEntityDestroy(int entityId) {
        if (!plugin.isPlayerEnabled(player.getUniqueId())) return;

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("DESTROY_ENTITY");
        out.writeInt(entityId);

        byte[] data = out.toByteArray();
        SchedulerCompat.sendPluginMessage(plugin.plugin, player, ViaEntitiesPlugin.CLIENTBOUND_CHANNEL, data);
    }
}
