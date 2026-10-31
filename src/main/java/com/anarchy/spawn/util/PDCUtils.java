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
 * 双轨持久化工具类：
 * - 在 Minecraft 1.14+ (Paper/Spigot/Folia) 上：直接在玩家实体上使用原生的 PersistentDataContainer (PDC)。
 * - 在旧版本 Minecraft 1.12.2 - 1.13.2 上：自动回退到线程安全的本地文件存储 (data/players.yml)。
 */
public class PDCUtils {

    private static final boolean HAS_PDC;
    private static final Map<UUID, Set<String>> LEGACY_CACHE = new ConcurrentHashMap<>();
    private static final Object FILE_LOCK = new Object();
    private static volatile File legacyFile = null;
    private static volatile YamlConfiguration legacyConfig = null;

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
            ensureLegacyInitialized();
        }
    }

    private static void ensureLegacyInitialized() {
        if (legacyFile != null && legacyConfig != null) {
            return;
        }
        synchronized (FILE_LOCK) {
            if (legacyFile != null && legacyConfig != null) {
                return;
            }
            try {
                AnarchySpawn plugin = AnarchySpawn.getInstance();
                File dataFolder = new File(plugin.getDataFolder(), "data");
                if (!dataFolder.exists()) {
                    dataFolder.mkdirs();
                }
                File file = new File(dataFolder, "players.yml");
                if (!file.exists()) {
                    try {
                        file.createNewFile();
                    } catch (IOException ignored) {
                    }
                }
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

                for (String key : cfg.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(key);
                        List<String> worlds = cfg.getStringList(key);
                        Set<String> set = ConcurrentHashMap.newKeySet();
                        for (String w : worlds) {
                            set.add(w.toLowerCase(Locale.ROOT));
                        }
                        LEGACY_CACHE.put(uuid, set);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                legacyConfig = cfg;
                legacyFile = file;
            } catch (Throwable ignored) {
            }
        }
    }

    public static boolean hasSpawnedInWorld(Player player, String worldName) {
        if (player == null || worldName == null) return false;
        if (HAS_PDC) {
            return PdcNativeHandler.hasSpawned(player, worldName);
        } else {
            ensureLegacyInitialized();
            Set<String> set = LEGACY_CACHE.get(player.getUniqueId());
            return set != null && set.contains(worldName.toLowerCase(Locale.ROOT));
        }
    }

    public static void markSpawnedInWorld(Player player, String worldName) {
        if (player == null || worldName == null) return;
        if (HAS_PDC) {
            PdcNativeHandler.markSpawned(player, worldName);
        } else {
            ensureLegacyInitialized();
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
            ensureLegacyInitialized();
            Set<String> set = LEGACY_CACHE.get(player.getUniqueId());
            if (set != null && set.remove(worldName.toLowerCase(Locale.ROOT))) {
                saveLegacyDataAsync(player.getUniqueId(), set);
            }
        }
    }

    private static void saveLegacyDataAsync(UUID uuid, Set<String> worlds) {
        ensureLegacyInitialized();
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
     * 隔离的原生 PDC 处理器。
     * 仅在 1.14+ 运行时的 JVM 中加载，完全避免在 1.12.2 - 1.13.2 的 JVM 上抛出 PersistentDataContainer 的 NoClassDefFoundError。
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
