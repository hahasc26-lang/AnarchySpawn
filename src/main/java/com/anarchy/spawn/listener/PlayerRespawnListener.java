package com.anarchy.spawn.listener;

import com.anarchy.spawn.AnarchySpawn;
import com.anarchy.spawn.config.WorldSpawnSettings;
import com.anarchy.spawn.util.FoliaScheduler;
import com.anarchy.spawn.util.PDCUtils;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

public class PlayerRespawnListener implements Listener {

    private final AnarchySpawn plugin;

    public PlayerRespawnListener(AnarchySpawn plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (!plugin.getConfigManager().isRespawnEnabled())
            return;

        Player player = event.getPlayer();

        if (plugin.getConfigManager().isRespectBypassPermission() && player.hasPermission("anarchyspawn.bypass")) {
            return;
        }

        if (!player.hasPermission("anarchyspawn.use")) {
            return;
        }

        try {
            if (event.isBedSpawn() && !plugin.getConfigManager().isOverrideBed()) {
                return;
            }
        } catch (Throwable ignored) {
        }

        try {
            if (event.isAnchorSpawn() && !plugin.getConfigManager().isOverrideAnchor()) {
                return;
            }
        } catch (Throwable ignored) {
        }

        Location respawnLoc = event.getRespawnLocation();
        org.bukkit.World targetWorld = respawnLoc != null && respawnLoc.getWorld() != null ? respawnLoc.getWorld()
                : player.getWorld();
        if (targetWorld == null)
            return;

        String worldName = targetWorld.getName();
        WorldSpawnSettings settings = plugin.getConfigManager().getWorldSettings(worldName);
        if (settings == null || !settings.isEnabled()) {
            return;
        }

        Location cachedLoc = plugin.getCachePool().poll(settings.getWorldName());
        // 若缓存点所在世界已不再加载则丢弃；否则复活可能导致主线程强制同步加载区块。
        if (cachedLoc != null && (cachedLoc.getWorld() == null
                || org.bukkit.Bukkit.getWorld(cachedLoc.getWorld().getName()) == null)) {
            cachedLoc = null;
        }
        if (cachedLoc != null) {
            final Location finalCachedLoc = cachedLoc;
            event.setRespawnLocation(finalCachedLoc);
            if (plugin.getConfigManager().isTrackPerWorldPDC()) {
                PDCUtils.markSpawnedInWorld(player, settings.getWorldName());
            }
            FoliaScheduler.runForPlayerLater(plugin, player, () -> {
                if (player.isOnline() && !player.isDead()) {
                    plugin.getTeleportService().applyPostRespawnEffects(player, finalCachedLoc, settings);
                }
            }, 1L);
            // 消耗了缓存点，触发后台异步补充
            FoliaScheduler.runAsync(plugin, () -> plugin.getCachePool().refillAllWorlds());
        } else {
            Location fallback = settings.getFallbackLocation(targetWorld);
            if (fallback != null) {
                event.setRespawnLocation(fallback);
            }
            if (plugin.getConfigManager().isProtectionEnabled()) {
                try {
                    player.setNoDamageTicks(60); // 异步搜索完成前的临时 3 秒无伤保护
                } catch (Throwable ignored) {
                }
            }
            if (plugin.getConfigManager().isTrackPerWorldPDC()) {
                PDCUtils.markSpawnedInWorld(player, settings.getWorldName());
            }
            FoliaScheduler.runForPlayerLater(plugin, player, () -> {
                if (player.isOnline() && !player.isDead()) {
                    plugin.getTeleportService().teleportToRandomSpawn(player, settings.getWorldName(), false);
                }
            }, 1L);
            // 缓存池为空，触发立即异步补充
            FoliaScheduler.runAsync(plugin, () -> plugin.getCachePool().refillAllWorlds());
        }
    }
}
