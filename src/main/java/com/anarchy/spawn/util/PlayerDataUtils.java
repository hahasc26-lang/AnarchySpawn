package com.anarchy.spawn.util;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.UUID;

/**
 * 玩家存档与数据文件检测工具类。
 * 用于检查玩家是否已在服务器或特定世界存在历史存档文件 (playerdata / stats)。
 */
public class PlayerDataUtils {

    /**
     * 检查是否存在该玩家的历史数据文件（playerdata/*.dat 或 stats/*.json）或已有游玩记录。
     *
     * @param player 目标玩家
     * @param world 所在或目标世界
     * @return 如果存在该玩家的数据文件或曾游玩过则返回 true
     */
    public static boolean hasExistingPlayerData(Player player, World world) {
        if (player == null) return false;

        // 1. 原版 API 判断：该玩家之前是否在此服务器游玩过（底层由 EntityPlayer 加载 playerdata 判定）
        if (player.hasPlayedBefore()) {
            return true;
        }

        UUID uuid = player.getUniqueId();
        String uuidStr = uuid.toString();

        // 2. 检查指定世界文件夹下的 playerdata/<uuid>.dat 与 stats/<uuid>.json
        if (world != null) {
            File worldDir = world.getWorldFolder();
            if (checkFileExists(new File(worldDir, "playerdata/" + uuidStr + ".dat"))) {
                return true;
            }
            if (checkFileExists(new File(worldDir, "stats/" + uuidStr + ".json"))) {
                return true;
            }
        }

        // 3. 检查默认主世界的 playerdata（大多数服务端将所有世界的 playerdata 存放在主世界目录下）
        if (!Bukkit.getWorlds().isEmpty()) {
            World defaultWorld = Bukkit.getWorlds().get(0);
            if (defaultWorld != null && defaultWorld != world) {
                File defaultDir = defaultWorld.getWorldFolder();
                if (checkFileExists(new File(defaultDir, "playerdata/" + uuidStr + ".dat"))) {
                    return true;
                }
                if (checkFileExists(new File(defaultDir, "stats/" + uuidStr + ".json"))) {
                    return true;
                }
            }
        }

        // 4. 检查服务端根目录下的 world/playerdata
        File container = Bukkit.getWorldContainer();
        if (container != null && container.isDirectory()) {
            File rootPlayerData = new File(container, "world/playerdata/" + uuidStr + ".dat");
            if (checkFileExists(rootPlayerData)) {
                return true;
            }
        }

        return false;
    }

    private static boolean checkFileExists(File file) {
        return file != null && file.exists() && file.isFile() && file.length() > 0;
    }
}
