package com.anarchy.spawn.command;

import com.anarchy.spawn.AnarchySpawn;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class AnarchySpawnTabCompleter implements TabCompleter {

    private final AnarchySpawn plugin;
    private final List<String> subCommands = Arrays.asList("reload", "tp", "setcenter", "info", "test", "cache");

    public AnarchySpawnTabCompleter(AnarchySpawn plugin) {
        this.plugin = plugin;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            return subCommands.stream()
                    .filter(sub -> sender.hasPermission("anarchyspawn." + sub) || sender.hasPermission("anarchyspawn.admin"))
                    .filter(sub -> sub.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("tp") && (sender.hasPermission("anarchyspawn.tp") || sender.hasPermission("anarchyspawn.admin"))) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                        completions.add(p.getName());
                    }
                }
            } else if ((sub.equals("setcenter") || sub.equals("test")) && (sender.hasPermission("anarchyspawn." + sub) || sender.hasPermission("anarchyspawn.admin"))) {
                for (String wName : plugin.getConfigManager().getAllWorldSettings().keySet()) {
                    if (wName.toLowerCase().startsWith(args[1].toLowerCase())) {
                        completions.add(wName);
                    }
                }
            }
        }

        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("tp") && (sender.hasPermission("anarchyspawn.tp") || sender.hasPermission("anarchyspawn.admin"))) {
                for (String wName : plugin.getConfigManager().getAllWorldSettings().keySet()) {
                    if (wName.toLowerCase().startsWith(args[2].toLowerCase())) {
                        completions.add(wName);
                    }
                }
            } else if (args[0].equalsIgnoreCase("setcenter") && (sender.hasPermission("anarchyspawn.setcenter") || sender.hasPermission("anarchyspawn.admin"))) {
                if (sender instanceof Player) {
                    completions.add(String.valueOf(((Player) sender).getLocation().getBlockX()));
                } else {
                    completions.add("0");
                }
            }
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("setcenter") && (sender.hasPermission("anarchyspawn.setcenter") || sender.hasPermission("anarchyspawn.admin"))) {
            if (sender instanceof Player) {
                completions.add(String.valueOf(((Player) sender).getLocation().getBlockZ()));
            } else {
                completions.add("0");
            }
        }

        return completions;
    }
}
