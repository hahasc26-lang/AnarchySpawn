package com.anarchy.spawn.util;

import com.anarchy.spawn.AnarchySpawn;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PDCUtils {

    private static final Map<String, NamespacedKey> KEY_CACHE = new ConcurrentHashMap<>();

    private static NamespacedKey createWorldKey(String worldName) {
        String cleanName = (worldName != null ? worldName : "world")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9/._-]", "_");
        return KEY_CACHE.computeIfAbsent(cleanName, k -> new NamespacedKey(AnarchySpawn.getInstance(), "spawned_" + k));
    }

    public static boolean hasSpawnedInWorld(Player player, String worldName) {
        if (player == null || worldName == null) return false;
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        return pdc.has(createWorldKey(worldName), PersistentDataType.BYTE);
    }

    public static void markSpawnedInWorld(Player player, String worldName) {
        if (player == null || worldName == null) return;
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.set(createWorldKey(worldName), PersistentDataType.BYTE, (byte) 1);
    }

    public static void resetSpawnRecord(Player player, String worldName) {
        if (player == null || worldName == null) return;
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.remove(createWorldKey(worldName));
    }
}
