package com.anarchy.spawn.command;

import com.anarchy.spawn.AnarchySpawn;
import com.anarchy.spawn.config.WorldSpawnSettings;
import com.anarchy.spawn.util.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class AnarchySpawnCommand implements CommandExecutor {

    private final AnarchySpawn plugin;

    public AnarchySpawnCommand(AnarchySpawn plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "reload":
                handleReload(sender);
                break;
            case "tp":
                handleTp(sender, args);
                break;
            case "setcenter":
                handleSetCenter(sender, label, args);
                break;
            case "info":
                handleInfo(sender);
                break;
            case "test":
                handleTest(sender, args);
                break;
            case "cache":
                handleCache(sender);
                break;
            default:
                sendHelp(sender, label);
                break;
        }

        return true;
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("anarchyspawn.reload")) {
            plugin.getMessageManager().sendMessage(sender, "command.no-permission", null);
            return;
        }
        plugin.reloadPlugin();
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("worlds_count", String.valueOf(plugin.getConfigManager().getAllWorldSettings().size()));
        plugin.getMessageManager().sendMessage(sender, "command.reload-success", placeholders);
    }

    private void handleTp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("anarchyspawn.tp")) {
            plugin.getMessageManager().sendMessage(sender, "command.no-permission", null);
            return;
        }

        Player target;
        String worldName = null;

        if (args.length >= 2) {
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                Map<String, String> p = new HashMap<>();
                p.put("target", args[1]);
                plugin.getMessageManager().sendMessage(sender, "command.player-not-found", p);
                return;
            }
            if (args.length >= 3) {
                worldName = args[2];
            }
        } else {
            if (!(sender instanceof Player)) {
                plugin.getMessageManager().sendMessage(sender, "command.player-only", null);
                return;
            }
            target = (Player) sender;
        }

        if (worldName == null) {
            worldName = target.getWorld().getName();
        }

        WorldSpawnSettings settings = plugin.getConfigManager().getWorldSettings(worldName);
        if (settings == null) {
            Map<String, String> p = new HashMap<>();
            p.put("world", worldName);
            plugin.getMessageManager().sendMessage(sender, "command.world-not-found", p);
            return;
        }

        plugin.getMessageManager().sendMessage(sender, "command.tp-searching", null);
        final String finalWorldName = worldName;
        plugin.getTeleportService().teleportToRandomSpawn(target, worldName, false).thenAccept(success -> {
            FoliaScheduler.runForPlayer(plugin, target, () -> {
                Map<String, String> p = new HashMap<>();
                p.put("player", target.getName());
                p.put("world", finalWorldName);
                if (target.isOnline()) {
                    p.put("x", String.valueOf(target.getLocation().getBlockX()));
                    p.put("y", String.valueOf(target.getLocation().getBlockY()));
                    p.put("z", String.valueOf(target.getLocation().getBlockZ()));
                } else {
                    p.put("x", "0");
                    p.put("y", "0");
                    p.put("z", "0");
                }
                if (success) {
                    plugin.getMessageManager().sendMessage(sender, "command.tp-success", p);
                } else {
                    plugin.getMessageManager().sendMessage(sender, "command.tp-failed", p);
                }
            });
        });
    }

    private void handleSetCenter(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("anarchyspawn.setcenter")) {
            plugin.getMessageManager().sendMessage(sender, "command.no-permission", null);
            return;
        }

        if (args.length < 4) {
            Map<String, String> p = new HashMap<>();
            p.put("label", label);
            plugin.getMessageManager().sendMessage(sender, "command.usage-setcenter", p);
            return;
        }

        String worldName = args[1];
        WorldSpawnSettings settings = plugin.getConfigManager().getWorldSettings(worldName);
        if (settings == null) {
            Map<String, String> p = new HashMap<>();
            p.put("world", worldName);
            plugin.getMessageManager().sendMessage(sender, "command.world-not-found", p);
            return;
        }

        try {
            double x = Double.parseDouble(args[2]);
            double z = Double.parseDouble(args[3]);
            settings.setCenterX(x);
            settings.setCenterZ(z);

            plugin.getConfig().set("worlds." + settings.getWorldName() + ".center.x", x);
            plugin.getConfig().set("worlds." + settings.getWorldName() + ".center.z", z);
            plugin.saveConfig();

            // Invalidate existing cached points for this world and trigger refill
            plugin.getCachePool().clearWorldCache(settings.getWorldName());
            FoliaScheduler.runAsync(plugin, () -> plugin.getCachePool().refillAllWorlds());

            Map<String, String> p = new HashMap<>();
            p.put("world", worldName);
            p.put("x", String.valueOf(x));
            p.put("z", String.valueOf(z));
            plugin.getMessageManager().sendMessage(sender, "command.setcenter-success", p);
        } catch (NumberFormatException e) {
            plugin.getMessageManager().sendMessage(sender, "command.invalid-number", null);
        }
    }

    private void handleInfo(CommandSender sender) {
        if (!sender.hasPermission("anarchyspawn.info")) {
            plugin.getMessageManager().sendMessage(sender, "command.no-permission", null);
            return;
        }

        plugin.getMessageManager().sendMessage(sender, "command.info-header", null);
        for (WorldSpawnSettings s : plugin.getConfigManager().getAllWorldSettings().values()) {
            Map<String, String> p = new HashMap<>();
            p.put("world", s.getWorldName());
            p.put("status", s.isEnabled() ? "&aON" : "&cOFF");
            p.put("first_join", s.isFirstJoinEnabled() ? "&aON" : "&cOFF");
            p.put("min_radius", String.valueOf((int) s.getMinRadius()));
            p.put("max_radius", String.valueOf((int) s.getMaxRadius()));
            p.put("pool_size", String.valueOf(plugin.getCachePool().getCacheSize(s.getWorldName())));
            p.put("target_size", String.valueOf(plugin.getConfigManager().getCacheTargetSize()));
            plugin.getMessageManager().sendMessage(sender, "command.info-world", p);
        }
    }

    private void handleTest(CommandSender sender, String[] args) {
        if (!sender.hasPermission("anarchyspawn.test")) {
            plugin.getMessageManager().sendMessage(sender, "command.no-permission", null);
            return;
        }

        String worldName = args.length >= 2 ? args[1]
                : (sender instanceof Player ? ((Player) sender).getWorld().getName() : "world");
        World world = Bukkit.getWorld(worldName);
        WorldSpawnSettings settings = plugin.getConfigManager().getWorldSettings(worldName);

        if (world == null || settings == null) {
            Map<String, String> p = new HashMap<>();
            p.put("world", worldName);
            plugin.getMessageManager().sendMessage(sender, "command.world-not-found", p);
            return;
        }

        plugin.getMessageManager().sendMessage(sender, "command.test-header", null);
        Map<String, String> searchP = new HashMap<>();
        searchP.put("world", worldName);
        plugin.getMessageManager().sendMessage(sender, "command.test-searching", searchP);

        plugin.getLocationFinder().findSafeLocationAsync(world, settings).thenAccept(result -> {
            FoliaScheduler.runForRegion(plugin, world, () -> {
                Map<String, String> p = new HashMap<>();
                p.put("time_ms", String.valueOf(result.getDurationMs()));
                p.put("attempts", String.valueOf(result.getAttempts()));
                if (result.isSuccess() && result.getLocation() != null) {
                    p.put("x", String.valueOf(result.getLocation().getBlockX()));
                    p.put("y", String.valueOf(result.getLocation().getBlockY()));
                    p.put("z", String.valueOf(result.getLocation().getBlockZ()));
                    p.put("biome", result.getBiome());
                    plugin.getMessageManager().sendMessage(sender, "command.test-result", p);
                } else {
                    plugin.getMessageManager().sendMessage(sender, "command.test-failed", p);
                }
            });
        });
    }

    private void handleCache(CommandSender sender) {
        if (!sender.hasPermission("anarchyspawn.cache")) {
            plugin.getMessageManager().sendMessage(sender, "command.no-permission", null);
            return;
        }
        plugin.getCachePool().refillAllWorlds();
        plugin.getMessageManager().sendMessage(sender, "command.info-cache-refreshed", null);
    }

    private void sendHelp(CommandSender sender, String label) {
        Map<String, String> p = new HashMap<>();
        p.put("label", label);

        plugin.getMessageManager().sendRawMessage(sender,
                plugin.getMessageManager().getFormatted(sender, "command.help-header", p));
        plugin.getMessageManager().sendRawMessage(sender,
                plugin.getMessageManager().getFormatted(sender, "command.help-reload", p));
        plugin.getMessageManager().sendRawMessage(sender,
                plugin.getMessageManager().getFormatted(sender, "command.help-tp", p));
        plugin.getMessageManager().sendRawMessage(sender,
                plugin.getMessageManager().getFormatted(sender, "command.help-setcenter", p));
        plugin.getMessageManager().sendRawMessage(sender,
                plugin.getMessageManager().getFormatted(sender, "command.help-info", p));
        plugin.getMessageManager().sendRawMessage(sender,
                plugin.getMessageManager().getFormatted(sender, "command.help-test", p));
        plugin.getMessageManager().sendRawMessage(sender,
                plugin.getMessageManager().getFormatted(sender, "command.help-cache", p));
    }
}
