package com.anarchy.spawn.hook;

import com.anarchy.spawn.AnarchySpawn;
import com.anarchy.spawn.config.WorldSpawnSettings;
import com.anarchy.spawn.util.PDCUtils;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class PlaceholderAPIHook extends PlaceholderExpansion {

    private final AnarchySpawn plugin;
    private static PlaceholderAPIHook instance;

    public PlaceholderAPIHook(AnarchySpawn plugin) {
        this.plugin = plugin;
    }

    public static boolean registerHook(AnarchySpawn plugin) {
        instance = new PlaceholderAPIHook(plugin);
        return instance.register();
    }

    public static void unregisterHook() {
        if (instance != null) {
            instance.unregister();
            instance = null;
        }
    }

    @Override
    public @NotNull String getIdentifier() {
        return "anarchyspawn";
    }

    @Override
    public @NotNull String getAuthor() {
        return "AnarchyDev";
    }

    @Override
    @SuppressWarnings("deprecation")
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(org.bukkit.OfflinePlayer offlinePlayer, @NotNull String params) {
        String lower = params.toLowerCase();

        if (lower.equals("spawned") && offlinePlayer != null && offlinePlayer.isOnline()) {
            Player player = offlinePlayer.getPlayer();
            if (player != null) {
                return String.valueOf(PDCUtils.hasSpawnedInWorld(player, player.getWorld().getName()));
            }
        }

        if (lower.startsWith("spawned_") && offlinePlayer != null && offlinePlayer.isOnline()) {
            String worldName = lower.substring("spawned_".length());
            Player player = offlinePlayer.getPlayer();
            if (player != null) {
                return String.valueOf(PDCUtils.hasSpawnedInWorld(player, worldName));
            }
        }

        if (lower.equals("total_cached")) {
            return String.valueOf(plugin.getCachePool().getTotalCacheSize());
        }

        if (lower.startsWith("cache_size_")) {
            String worldName = lower.substring("cache_size_".length());
            return String.valueOf(plugin.getCachePool().getCacheSize(worldName));
        }

        if (lower.startsWith("min_radius_")) {
            String worldName = lower.substring("min_radius_".length());
            WorldSpawnSettings settings = plugin.getConfigManager().getWorldSettings(worldName);
            return settings != null ? String.valueOf((int) settings.getMinRadius()) : "0";
        }

        if (lower.startsWith("max_radius_")) {
            String worldName = lower.substring("max_radius_".length());
            WorldSpawnSettings settings = plugin.getConfigManager().getWorldSettings(worldName);
            return settings != null ? String.valueOf((int) settings.getMaxRadius()) : "0";
        }

        return null;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        return onRequest(player, params);
    }
}
