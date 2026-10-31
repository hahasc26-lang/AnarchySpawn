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
        if (player != null && plugin.getDeathHotspotManager() != null && player.getLocation() != null && player.getLocation().getWorld() != null) {
            plugin.getDeathHotspotManager().recordDeath(player.getLocation());
        }
    }
}
