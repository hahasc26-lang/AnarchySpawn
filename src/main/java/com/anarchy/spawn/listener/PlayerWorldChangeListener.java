package com.anarchy.spawn.listener;

import com.anarchy.spawn.AnarchySpawn;
import com.anarchy.spawn.config.WorldSpawnSettings;
import com.anarchy.spawn.util.PDCUtils;
import com.anarchy.spawn.util.PlayerDataUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监听玩家切换世界事件。
 * 当玩家初次进入某个启用了初次进入随机传送的世界时执行安全传送。
 */
public class PlayerWorldChangeListener implements Listener {

    private final AnarchySpawn plugin;
    private final Set<UUID> pendingTeleports = ConcurrentHashMap.newKeySet();

    public PlayerWorldChangeListener(AnarchySpawn plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (!plugin.getConfigManager().isFirstJoinEnabled()) {
            return;
        }

        // 仅当启用按世界持久化跟踪时才会在跨世界时触发各世界各自的初次出生
        if (!plugin.getConfigManager().isTrackPerWorldPDC()) {
            return;
        }

        Player player = event.getPlayer();
        String worldName = player.getWorld().getName();

        WorldSpawnSettings settings = plugin.getConfigManager().getWorldSettings(worldName);
        if (settings == null || !settings.isEnabled() || !settings.isFirstJoinEnabled()) {
            return;
        }

        if (plugin.getConfigManager().isRespectBypassPermission() && player.hasPermission("anarchyspawn.bypass")) {
            return;
        }

        if (PDCUtils.hasSpawnedInWorld(player, worldName)) {
            return;
        }

        // 如果配置了检测到玩家文件则不实行随机传送
        if (settings.isIgnoreExistingPlayerData() && PlayerDataUtils.hasExistingPlayerData(player, player.getWorld())) {
            PDCUtils.markSpawnedInWorld(player, worldName);
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("Player " + player.getName() + " has existing playerdata file; skipping world-change first-join random spawn for world " + worldName);
            }
            return;
        }

        UUID uuid = player.getUniqueId();
        if (!pendingTeleports.add(uuid)) {
            return;
        }

        int delay = plugin.getConfigManager().getFirstJoinDelayTicks();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                if (player.isOnline() && !player.isDead() && player.getWorld().getName().equalsIgnoreCase(worldName)) {
                    WorldSpawnSettings currentSettings = plugin.getConfigManager().getWorldSettings(worldName);
                    if (currentSettings == null || !currentSettings.isEnabled() || !currentSettings.isFirstJoinEnabled()) {
                        return;
                    }
                    if (currentSettings.isIgnoreExistingPlayerData() && PlayerDataUtils.hasExistingPlayerData(player, player.getWorld())) {
                        return;
                    }
                    if (!PDCUtils.hasSpawnedInWorld(player, worldName)) {
                        plugin.getTeleportService().teleportToRandomSpawn(player, worldName, true);
                    }
                }
            } finally {
                pendingTeleports.remove(uuid);
            }
        }, delay);
    }
}
