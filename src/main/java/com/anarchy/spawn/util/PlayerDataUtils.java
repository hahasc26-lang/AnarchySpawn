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

    /**
     * 检查玩家在切换到指定世界时，该目标世界自身是否已存在独立的历史玩家数据文件。
     * 避免因主世界已生成全局 playerdata 或 hasPlayedBefore() 为 true 而误拦截跨世界首次随机传送。
     *
     * @param player 目标玩家
     * @param world  切换进入的目标世界
     * @return 如果目标世界自身已存在该玩家的独立存档记录则返回 true
     */
    public static boolean hasExistingWorldPlayerData(Player player, World world) {
        if (player == null || world == null) return false;

        // 如果目标世界就是服务器默认主世界，则仅当玩家此前已在服务器游玩过时视为已有存档
        if (!Bukkit.getWorlds().isEmpty() && world.equals(Bukkit.getWorlds().get(0))) {
            return player.hasPlayedBefore();
        }

        // 对于非默认主世界（如下界、末地或多世界插件创建的独立世界），仅检查该世界独立目录下的 playerdata
        String uuidStr = player.getUniqueId().toString();
        File worldDir = world.getWorldFolder();
        return checkFileExists(new File(worldDir, "playerdata/" + uuidStr + ".dat"))
                || checkFileExists(new File(worldDir, "stats/" + uuidStr + ".json"));
    }

    private static boolean checkFileExists(File file) {
        return file != null && file.exists() && file.isFile() && file.length() > 0;
    }
}
