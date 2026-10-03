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
        // Discard a cached point whose world is no longer loaded; otherwise the
        // respawn would force a synchronous chunk load on the main thread.
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
            FoliaScheduler.runForPlayer(plugin, player, () -> {
                if (player.isOnline() && !player.isDead()) {
                    plugin.getTeleportService().applyPostRespawnEffects(player, finalCachedLoc, settings);
                }
            });
            // Trigger background refill since we consumed a cached point
            FoliaScheduler.runAsync(plugin, () -> plugin.getCachePool().refillAllWorlds());
        } else {
            Location fallback = settings.getFallbackLocation(targetWorld);
            if (fallback != null) {
                event.setRespawnLocation(fallback);
            }
            if (plugin.getConfigManager().isTrackPerWorldPDC()) {
                PDCUtils.markSpawnedInWorld(player, settings.getWorldName());
            }
            FoliaScheduler.runForPlayerLater(plugin, player, () -> {
                if (player.isOnline() && !player.isDead()) {
                    plugin.getTeleportService().teleportToRandomSpawn(player, settings.getWorldName(), false);
                }
            }, 1L);
            // Cache is empty, trigger immediate async refill
            FoliaScheduler.runAsync(plugin, () -> plugin.getCachePool().refillAllWorlds());
        }
    }
}
