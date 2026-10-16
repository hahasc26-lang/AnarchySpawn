package com.anarchy.spawn.listener;

import com.anarchy.spawn.AnarchySpawn;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listens for player disconnects to clean up active spawn protection tasks
 * and reset invulnerability, preventing combat-log permanent god-mode exploits.
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
