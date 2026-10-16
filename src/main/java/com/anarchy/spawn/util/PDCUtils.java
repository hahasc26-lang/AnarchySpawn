package com.anarchy.spawn.util;

import com.anarchy.spawn.AnarchySpawn;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dual-track persistence utility:
 * - On Minecraft 1.14+ (Paper/Spigot/Folia): Uses native PersistentDataContainer (PDC) directly on Player entities.
 * - On legacy Minecraft 1.12.2 - 1.13.2: Automatically falls back to a thread-safe local file storage (data/players.yml).
 */
public class PDCUtils {

    private static final boolean HAS_PDC;
    private static final Map<UUID, Set<String>> LEGACY_CACHE = new ConcurrentHashMap<>();
    private static final Object FILE_LOCK = new Object();
    private static File legacyFile = null;
    private static YamlConfiguration legacyConfig = null;

    static {
        boolean pdc = false;
        try {
            Class.forName("org.bukkit.persistence.PersistentDataContainer");
            Player.class.getMethod("getPersistentDataContainer");
            pdc = true;
        } catch (Throwable t) {
            pdc = false;
        }
        HAS_PDC = pdc;

        if (!HAS_PDC) {
            initLegacyStorage();
        }
    }

    private static void initLegacyStorage() {
        try {
            AnarchySpawn plugin = AnarchySpawn.getInstance();
            File dataFolder = new File(plugin.getDataFolder(), "data");
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            legacyFile = new File(dataFolder, "players.yml");
            if (!legacyFile.exists()) {
                try {
                    legacyFile.createNewFile();
                } catch (IOException ignored) {
                }
            }
            legacyConfig = YamlConfiguration.loadConfiguration(legacyFile);

            for (String key : legacyConfig.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    List<String> worlds = legacyConfig.getStringList(key);
                    Set<String> set = ConcurrentHashMap.newKeySet();
                    for (String w : worlds) {
                        set.add(w.toLowerCase(Locale.ROOT));
                    }
                    LEGACY_CACHE.put(uuid, set);
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean hasSpawnedInWorld(Player player, String worldName) {
        if (player == null || worldName == null) return false;
        if (HAS_PDC) {
            return PdcNativeHandler.hasSpawned(player, worldName);
        } else {
            Set<String> set = LEGACY_CACHE.get(player.getUniqueId());
            return set != null && set.contains(worldName.toLowerCase(Locale.ROOT));
        }
    }

    public static void markSpawnedInWorld(Player player, String worldName) {
        if (player == null || worldName == null) return;
        if (HAS_PDC) {
            PdcNativeHandler.markSpawned(player, worldName);
        } else {
            Set<String> set = LEGACY_CACHE.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
            boolean added = set.add(worldName.toLowerCase(Locale.ROOT));
            if (added) {
                saveLegacyDataAsync(player.getUniqueId(), set);
            }
        }
    }

    public static void resetSpawnRecord(Player player, String worldName) {
        if (player == null || worldName == null) return;
        if (HAS_PDC) {
            PdcNativeHandler.resetSpawn(player, worldName);
        } else {
            Set<String> set = LEGACY_CACHE.get(player.getUniqueId());
            if (set != null && set.remove(worldName.toLowerCase(Locale.ROOT))) {
                saveLegacyDataAsync(player.getUniqueId(), set);
            }
        }
    }

    private static void saveLegacyDataAsync(UUID uuid, Set<String> worlds) {
        if (legacyFile == null || legacyConfig == null) return;
        FoliaScheduler.runAsync(AnarchySpawn.getInstance(), () -> {
            synchronized (FILE_LOCK) {
                try {
                    legacyConfig.set(uuid.toString(), new ArrayList<>(worlds));
                    legacyConfig.save(legacyFile);
                } catch (Exception ignored) {
                }
            }
        });
    }

    /**
     * Isolated native PDC handler.
     * Only loaded by JVM on 1.14+ runtimes, completely insulating 1.12.2 - 1.13.2 JVMs
     * from NoClassDefFoundError on PersistentDataContainer.
     */
    private static class PdcNativeHandler {
        private static final Map<String, NamespacedKey> KEY_CACHE = new ConcurrentHashMap<>();

        private static NamespacedKey createWorldKey(String worldName) {
            String cleanName = (worldName != null ? worldName : "world")
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9/._-]", "_");
            return KEY_CACHE.computeIfAbsent(cleanName, k -> new NamespacedKey(AnarchySpawn.getInstance(), "spawned_" + k));
        }

        static boolean hasSpawned(Player player, String worldName) {
            try {
                PersistentDataContainer pdc = player.getPersistentDataContainer();
                return pdc.has(createWorldKey(worldName), PersistentDataType.BYTE);
            } catch (Throwable t) {
                return false;
            }
        }

        static void markSpawned(Player player, String worldName) {
            try {
                PersistentDataContainer pdc = player.getPersistentDataContainer();
                pdc.set(createWorldKey(worldName), PersistentDataType.BYTE, (byte) 1);
            } catch (Throwable ignored) {
            }
        }

        static void resetSpawn(Player player, String worldName) {
            try {
                PersistentDataContainer pdc = player.getPersistentDataContainer();
                pdc.remove(createWorldKey(worldName));
            } catch (Throwable ignored) {
            }
        }
    }
}
