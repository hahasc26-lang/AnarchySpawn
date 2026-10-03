package com.anarchy.spawn.listener;

import com.anarchy.spawn.AnarchySpawn;
import com.anarchy.spawn.config.WorldSpawnSettings;
import com.anarchy.spawn.util.FoliaScheduler;
import com.anarchy.spawn.util.PDCUtils;
import com.anarchy.spawn.util.PlayerDataUtils;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerJoinListener implements Listener {

    private final AnarchySpawn plugin;

    public PlayerJoinListener(AnarchySpawn plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!plugin.getConfigManager().isFirstJoinEnabled())
            return;

        Player player = event.getPlayer();
        String worldName = player.getWorld().getName();

        WorldSpawnSettings settings = plugin.getConfigManager().getWorldSettings(worldName);
        if (settings == null || !settings.isEnabled() || !settings.isFirstJoinEnabled()) {
            return;
        }

        if (plugin.getConfigManager().isRespectBypassPermission() && player.hasPermission("anarchyspawn.bypass")) {
            return;
        }

        // 如果配置了检测到玩家文件则不实行随机传送
        if (settings.isIgnoreExistingPlayerData() && PlayerDataUtils.hasExistingPlayerData(player, player.getWorld())) {
            if (plugin.getConfigManager().isTrackPerWorldPDC()) {
                PDCUtils.markSpawnedInWorld(player, worldName);
            }
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("Player " + player.getName()
                        + " has existing playerdata file; skipping first-join random spawn for world " + worldName);
            }
            return;
        }

        if (plugin.getConfigManager().isTrackPerWorldPDC()) {
            if (PDCUtils.hasSpawnedInWorld(player, worldName)) {
                return;
            }
        } else {
            if (player.hasPlayedBefore()) {
                return;
            }
        }

        int delay = plugin.getConfigManager().getFirstJoinDelayTicks();
        FoliaScheduler.runForPlayerLater(plugin, player, () -> {
            if (player.isOnline() && !player.isDead()) {
                WorldSpawnSettings currentSettings = plugin.getConfigManager().getWorldSettings(worldName);
                if (currentSettings == null || !currentSettings.isEnabled() || !currentSettings.isFirstJoinEnabled()) {
                    return;
                }
                if (currentSettings.isIgnoreExistingPlayerData()
                        && PlayerDataUtils.hasExistingPlayerData(player, player.getWorld())) {
                    return;
                }
                if (plugin.getConfigManager().isTrackPerWorldPDC() && PDCUtils.hasSpawnedInWorld(player, worldName)) {
                    return;
                }
                plugin.getTeleportService().teleportToRandomSpawn(player, worldName, true);
            }
        }, delay);
    }
}
