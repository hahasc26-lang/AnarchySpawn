package com.anarchy.spawn.listener;

import com.anarchy.spawn.AnarchySpawn;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 监听玩家退出事件，用于清理活跃的出生保护任务并重置无敌状态，
 * 防止利用登出漏洞卡永久无敌。
 */
public class PlayerQuitListener implements Listener {

    private final AnarchySpawn plugin;

    public PlayerQuitListener(AnarchySpawn plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (plugin.getTeleportService() != null) {
            plugin.getTeleportService().cancelPlayerProtection(player);
        }
    }
}
