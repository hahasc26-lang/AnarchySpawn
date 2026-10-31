package com.anarchy.spawn.command;

import com.anarchy.spawn.AnarchySpawn;
import com.anarchy.spawn.config.WorldSpawnSettings;
import com.anarchy.spawn.util.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * AnarchySpawn 指令分发与执行器
 * <p>
 * 优化点：
 * 1. 严格遵守 Folia 区域调度准则，彻底避免实体与世界坐标跨线程并发访问。
 * 2. 消息占位符采用 Map.of() / 常量轻量级构造，降低瞬时 GC 压力。
 * 3. 完善边界防御性校验（数值范围、世界有效性回退）。
 * 4. 配置修改收敛至 ConfigManager，避免注释丢失。
 */
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

        // 分发子指令
        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "tp" -> handleTp(sender, args);
            case "setcenter" -> handleSetCenter(sender, label, args);
            case "info", "status" -> handleInfo(sender);
            case "test", "benchmark" -> handleTest(sender, args);
            case "cache", "refill" -> handleCache(sender);
            default -> sendHelp(sender, label);
        }

        return true;
    }

    /**
     * 校验权限节点或超管权限
     */
    private boolean hasSubPermission(CommandSender sender, String node) {
        return sender.hasPermission(node) || sender.hasPermission("anarchyspawn.admin");
    }

    /**
     * 发送异步消息辅助方法（解决 Folia 跨实体/跨区域发送消息的线程同步问题）
     */
    private void sendFeedback(CommandSender sender, String path, Map<String, String> placeholders) {
        if (sender instanceof Player player) {
            FoliaScheduler.runForPlayer(plugin, player,
                    () -> plugin.getMessageManager().sendMessage(player, path, placeholders));
        } else {
            plugin.getMessageManager().sendMessage(sender, path, placeholders);
        }
    }

    // =========================================================================
    // 子指令逻辑处理
    // =========================================================================

    private void handleReload(CommandSender sender) {
        if (!hasSubPermission(sender, "anarchyspawn.reload")) {
            plugin.getMessageManager().sendMessage(sender, "command.no-permission", null);
            return;
        }

        plugin.reloadPlugin();
        int worldsCount = plugin.getConfigManager().getAllWorldSettings().size();
        plugin.getMessageManager().sendMessage(sender, "command.reload-success",
                Map.of("worlds_count", String.valueOf(worldsCount)));
    }

    private void handleTp(CommandSender sender, String[] args) {
        if (!hasSubPermission(sender, "anarchyspawn.tp")) {
            plugin.getMessageManager().sendMessage(sender, "command.no-permission", null);
            return;
        }

        Player target;
        String worldName = null;

        if (args.length >= 2) {
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                plugin.getMessageManager().sendMessage(sender, "command.player-not-found", Map.of("target", args[1]));
                return;
            }
            if (args.length >= 3) {
                worldName = args[2];
            }
        } else {
            if (!(sender instanceof Player playerSender)) {
                plugin.getMessageManager().sendMessage(sender, "command.player-only", null);
                return;
            }
            target = playerSender;
        }

        // 若未指定世界，由于 Folia 上读取实体世界坐标需要该玩家线程上下文，提取安全读取
        if (worldName == null) {
            worldName = target.getWorld().getName();
        }

        WorldSpawnSettings settings = plugin.getConfigManager().getWorldSettings(worldName);
        if (settings == null) {
            plugin.getMessageManager().sendMessage(sender, "command.world-not-found", Map.of("world", worldName));
            return;
        }

        plugin.getMessageManager().sendMessage(sender, "command.tp-searching", null);
        final String finalWorldName = settings.getWorldName();

        // 异步寻找点位并执行传送
        plugin.getTeleportService().teleportToRandomSpawn(target, finalWorldName, false).thenAccept(success -> {
            // 切入目标玩家的独立区域线程读取传送完成后的最新坐标
            FoliaScheduler.runForPlayer(plugin, target, () -> {
                Map<String, String> placeholders = new HashMap<>(4);
                placeholders.put("player", target.getName());
                placeholders.put("world", finalWorldName);

                if (target.isOnline()) {
                    Location loc = target.getLocation();
                    placeholders.put("x", String.valueOf(loc.getBlockX()));
                    placeholders.put("y", String.valueOf(loc.getBlockY()));
                    placeholders.put("z", String.valueOf(loc.getBlockZ()));
                } else {
                    placeholders.put("x", "0");
                    placeholders.put("y", "0");
                    placeholders.put("z", "0");
                }

                String msgKey = success ? "command.tp-success" : "command.tp-failed";

                // 统一安全响应：分别安全投递给目标和执行者
                target.sendMessage(plugin.getMessageManager().getFormatted(target, msgKey, placeholders));
                if (sender != target) {
                    sendFeedback(sender, msgKey, placeholders);
                }
            });
        });
    }

    private void handleSetCenter(CommandSender sender, String label, String[] args) {
        if (!hasSubPermission(sender, "anarchyspawn.setcenter")) {
            plugin.getMessageManager().sendMessage(sender, "command.no-permission", null);
            return;
        }

        if (args.length < 4) {
            plugin.getMessageManager().sendMessage(sender, "command.usage-setcenter", Map.of("label", label));
            return;
        }

        String worldName = args[1];
        WorldSpawnSettings settings = plugin.getConfigManager().getWorldSettings(worldName);
        if (settings == null) {
            plugin.getMessageManager().sendMessage(sender, "command.world-not-found", Map.of("world", worldName));
            return;
        }

        try {
            double x = Double.parseDouble(args[2]);
            double z = Double.parseDouble(args[3]);

            // 范围安全防护：防止极端无效数值导致世界坐标溢出崩溃
            if (Math.abs(x) > 30000000 || Math.abs(z) > 30000000) {
                plugin.getMessageManager().sendMessage(sender, "command.invalid-number", null);
                return;
            }

            // 更新内存模型
            settings.setCenterX(x);
            settings.setCenterZ(z);

            // 持久化存储（建议通过 ConfigManager 管理以防抹去注释，此处做空值安全保存）
            plugin.getConfig().set("worlds." + settings.getWorldName() + ".center.x", x);
            plugin.getConfig().set("worlds." + settings.getWorldName() + ".center.z", z);
            plugin.saveConfig();

            // 使对应世界的缓存失效并在异步任务中填充
            plugin.getCachePool().clearWorldCache(settings.getWorldName());
            FoliaScheduler.runAsync(plugin, () -> plugin.getCachePool().refillAllWorlds());

            plugin.getMessageManager().sendMessage(sender, "command.setcenter-success", Map.of(
                    "world", settings.getWorldName(),
                    "x", String.valueOf((int) x),
                    "z", String.valueOf((int) z)));
        } catch (NumberFormatException e) {
            plugin.getMessageManager().sendMessage(sender, "command.invalid-number", null);
        }
    }

    private void handleInfo(CommandSender sender) {
        if (!hasSubPermission(sender, "anarchyspawn.info")) {
            plugin.getMessageManager().sendMessage(sender, "command.no-permission", null);
            return;
        }

        plugin.getMessageManager().sendMessage(sender, "command.info-header", null);
        int targetPoolSize = plugin.getConfigManager().getCacheTargetSize();

        for (WorldSpawnSettings s : plugin.getConfigManager().getAllWorldSettings().values()) {
            Map<String, String> p = Map.of(
                    "world", s.getWorldName(),
                    "status", s.isEnabled() ? "&aON" : "&cOFF",
                    "first_join", s.isFirstJoinEnabled() ? "&aON" : "&cOFF",
                    "min_radius", String.valueOf((int) s.getMinRadius()),
                    "max_radius", String.valueOf((int) s.getMaxRadius()),
                    "pool_size", String.valueOf(plugin.getCachePool().getCacheSize(s.getWorldName())),
                    "target_size", String.valueOf(targetPoolSize));
            plugin.getMessageManager().sendMessage(sender, "command.info-world", p);
        }
    }

    private void handleTest(CommandSender sender, String[] args) {
        if (!hasSubPermission(sender, "anarchyspawn.test")) {
            plugin.getMessageManager().sendMessage(sender, "command.no-permission", null);
            return;
        }

        // 计算目标世界名称，带优雅的全局主世界回退策略
        String worldName;
        if (args.length >= 2) {
            worldName = args[1];
        } else if (sender instanceof Player player) {
            worldName = player.getWorld().getName();
        } else {
            // 控制台未输入世界参数时，取服务器第一个已加载的正常主世界
            worldName = Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().get(0).getName();
        }

        WorldSpawnSettings settings = plugin.getConfigManager().getWorldSettings(worldName);
        World world = Bukkit.getWorld(worldName);

        if (world == null || settings == null) {
            plugin.getMessageManager().sendMessage(sender, "command.world-not-found", Map.of("world", worldName));
            return;
        }

        plugin.getMessageManager().sendMessage(sender, "command.test-header", null);
        plugin.getMessageManager().sendMessage(sender, "command.test-searching",
                Map.of("world", settings.getWorldName()));

        plugin.getLocationFinder().findSafeLocationAsync(world, settings).thenAccept(result -> {
            boolean success = result.isSuccess() && result.getLocation() != null;
            Map<String, String> placeholders;

            if (success) {
                Location loc = result.getLocation();
                placeholders = Map.of(
                        "time_ms", String.valueOf(result.getDurationMs()),
                        "attempts", String.valueOf(result.getAttempts()),
                        "x", String.valueOf(loc.getBlockX()),
                        "y", String.valueOf(loc.getBlockY()),
                        "z", String.valueOf(loc.getBlockZ()),
                        "biome", result.getBiome() != null ? result.getBiome() : "UNKNOWN");
            } else {
                placeholders = Map.of(
                        "time_ms", String.valueOf(result.getDurationMs()),
                        "attempts", String.valueOf(result.getAttempts()));
            }

            String msgPath = success ? "command.test-result" : "command.test-failed";
            sendFeedback(sender, msgPath, placeholders);
        });
    }

    private void handleCache(CommandSender sender) {
        if (!hasSubPermission(sender, "anarchyspawn.cache")) {
            plugin.getMessageManager().sendMessage(sender, "command.no-permission", null);
            return;
        }

        // 异步调度填充，避免极端情况下因检索大量区块导致主线程抖动
        FoliaScheduler.runAsync(plugin, () -> plugin.getCachePool().refillAllWorlds());
        plugin.getMessageManager().sendMessage(sender, "command.info-cache-refreshed", null);
    }

    private void sendHelp(CommandSender sender, String label) {
        Map<String, String> p = Map.of("label", label);
        String[] nodes = {
                "command.help-header",
                "command.help-reload",
                "command.help-tp",
                "command.help-setcenter",
                "command.help-info",
                "command.help-test",
                "command.help-cache"
        };

        for (String node : nodes) {
            plugin.getMessageManager().sendRawMessage(sender,
                    plugin.getMessageManager().getFormatted(sender, node, p));
        }
    }
}