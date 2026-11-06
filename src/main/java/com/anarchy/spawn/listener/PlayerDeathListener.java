package com.anarchy.spawn.listener;

import com.anarchy.spawn.AnarchySpawn;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * 监听玩家死亡事件，动态记录死亡热点坐标以便在随机出生时进行规避过滤。
 */
public class PlayerDeathListener implements Listener {

    private final AnarchySpawn plugin;

    public PlayerDeathListener(AnarchySpawn plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (player == null) {
            return;
        }

        if (plugin.getDeathHotspotManager() != null && player.getLocation() != null && player.getLocation().getWorld() != null) {
            plugin.getDeathHotspotManager().recordDeath(player.getLocation());
        }

        // 提前预热复活缓存池：在玩家处于死亡界面等待点击重生的数秒时间窗口内，
        // 预先保证目标世界的安全点缓冲池水位，杜绝玩家复活瞬间因缓存击穿而产生二次传送闪烁。
        if (plugin.getConfigManager().isRespawnEnabled()
                && plugin.getConfigManager().isCacheEnabled()
                && player.hasPermission("anarchyspawn.use")
                && (!plugin.getConfigManager().isRespectBypassPermission() || !player.hasPermission("anarchyspawn.bypass"))) {
            org.bukkit.World currentWorld = player.getWorld();
            if (currentWorld != null) {
                String worldName = currentWorld.getName();
                com.anarchy.spawn.config.WorldSpawnSettings settings = plugin.getConfigManager().getWorldSettings(worldName);
                if (settings != null && settings.isEnabled()) {
                    plugin.getCachePool().ensureSupply(worldName);
                } else if (!org.bukkit.Bukkit.getWorlds().isEmpty()) {
                    String defaultWorldName = org.bukkit.Bukkit.getWorlds().get(0).getName();
                    plugin.getCachePool().ensureSupply(defaultWorldName);
                }
            }
        }
    }
}
