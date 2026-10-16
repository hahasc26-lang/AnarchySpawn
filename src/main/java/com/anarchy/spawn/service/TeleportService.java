package com.anarchy.spawn.service;

import com.anarchy.spawn.AnarchySpawn;
import com.anarchy.spawn.config.WorldSpawnSettings;
import com.anarchy.spawn.util.FoliaScheduler;
import com.anarchy.spawn.util.PDCUtils;
import com.anarchy.spawn.util.PaperCompatibility;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class TeleportService {

    private final AnarchySpawn plugin;
    private final Map<UUID, BukkitTask> activeProtectionTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> activeProtectionSessions = new ConcurrentHashMap<>();

    public TeleportService(AnarchySpawn plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<Boolean> teleportToRandomSpawn(Player player, String worldName, boolean isFirstJoin) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        if (player == null || !player.isOnline() || player.isDead()) {
            future.complete(false);
            return future;
        }

        WorldSpawnSettings settings = plugin.getConfigManager()
                .getWorldSettings(worldName != null ? worldName : player.getWorld().getName());
        if (settings == null) {
            future.complete(false);
            return future;
        }

        org.bukkit.World targetWorld = Bukkit.getWorld(settings.getWorldName());
        if (targetWorld == null) {
            targetWorld = player.getWorld();
        }

        Location cachedLoc = plugin.getCachePool().poll(settings.getWorldName());
        // A cached location is only usable when its world is currently loaded; a stale
        // cache entry referencing an unloaded world would make teleportAsync fail.
        if (cachedLoc != null && cachedLoc.getWorld() != null
                && Bukkit.getWorld(cachedLoc.getWorld().getName()) == null) {
            cachedLoc = null;
        }
        if (cachedLoc != null) {
            final Location finalCachedLoc = cachedLoc;
            FoliaScheduler.runForPlayer(plugin, player, () -> {
                if (player == null || !player.isOnline() || player.isDead()) {
                    future.complete(false);
                    return;
                }
                PaperCompatibility.teleportPlayerAsync(player, finalCachedLoc).thenAccept(success -> {
                    if (success) {
                        FoliaScheduler.runForPlayer(plugin, player, () -> {
                            applyEffectsAndFeedback(player, finalCachedLoc, settings, isFirstJoin);
                        });
                        // Trigger background refill since we consumed a cached point
                        FoliaScheduler.runAsync(plugin, () -> plugin.getCachePool().refillAllWorlds());
                    }
                    future.complete(success);
                });
            });
            return future;
        }

        final org.bukkit.World finalWorld = targetWorld;
        plugin.getLocationFinder().findSafeLocationAsync(finalWorld, settings).thenAccept(result -> {
            FoliaScheduler.runForPlayer(plugin, player, () -> {
                if (player == null || !player.isOnline() || player.isDead()) {
                    future.complete(false);
                    return;
                }
                boolean isFallback = !result.isSuccess();
                Location targetLoc = isFallback ? settings.getFallbackLocation(finalWorld) : result.getLocation();
                PaperCompatibility.teleportPlayerAsync(player, targetLoc).thenAccept(success -> {
                    if (success) {
                        FoliaScheduler.runForPlayer(plugin, player, () -> {
                            applyEffectsAndFeedback(player, targetLoc, settings, isFirstJoin);
                            if (isFallback && plugin.getConfigManager().isClientFallbackWarningEnabled()
                                    && plugin.getMessageManager().hasMessage(player, "spawn.fallback-warning")) {
                                Map<String, String> warnP = new HashMap<>();
                                warnP.put("player", player.getName());
                                String wName = (targetLoc != null && targetLoc.getWorld() != null)
                                        ? targetLoc.getWorld().getName() : settings.getWorldName();
                                warnP.put("world", wName);
                                warnP.put("x", targetLoc != null ? String.valueOf(targetLoc.getBlockX()) : "0");
                                warnP.put("y", targetLoc != null ? String.valueOf(targetLoc.getBlockY()) : "0");
                                warnP.put("z", targetLoc != null ? String.valueOf(targetLoc.getBlockZ()) : "0");
                                plugin.getMessageManager().sendMessage(player, "spawn.fallback-warning", warnP);
                            }
                        });
                    }
                    future.complete(success);
                });
            });
        });

        return future;
    }

    public void applyPostRespawnEffects(Player player, Location location, WorldSpawnSettings settings) {
        if (player == null || !player.isOnline() || location == null || settings == null)
            return;
        FoliaScheduler.runForPlayer(plugin, player, () -> applyEffectsAndFeedback(player, location, settings, false));
    }

    private void applyEffectsAndFeedback(Player player, Location location, WorldSpawnSettings settings,
            boolean isFirstJoin) {
        if (player == null || !player.isOnline() || location == null || location.getWorld() == null)
            return;

        if (plugin.getConfigManager().isTrackPerWorldPDC()) {
            PDCUtils.markSpawnedInWorld(player, settings.getWorldName());
        }

        boolean protectionEnabled = plugin.getConfigManager().isProtectionEnabled();
        int invulSecs = protectionEnabled ? plugin.getConfigManager().getInvulnerableSeconds() : 0;
        if (invulSecs > 0) {
            try {
                player.setInvulnerable(true);
            } catch (Throwable ignored) {
            }
            player.setNoDamageTicks(invulSecs * 20);

            // Generate a unique session token for this protection window
            long sessionId = System.currentTimeMillis();
            activeProtectionSessions.put(player.getUniqueId(), sessionId);

            // Cancel any previous protection task
            BukkitTask previousTask = activeProtectionTasks.remove(player.getUniqueId());
            if (previousTask != null) {
                previousTask.cancel();
            }

            BukkitTask protectionTask = FoliaScheduler.runForPlayerLater(plugin, player, () -> {
                Long currentSession = activeProtectionSessions.get(player.getUniqueId());
                if (currentSession == null || currentSession != sessionId) {
                    return; // Protection was cancelled or superseded by a newer spawn
                }
                activeProtectionSessions.remove(player.getUniqueId());
                activeProtectionTasks.remove(player.getUniqueId());
                if (player.isOnline()) {
                    try {
                        player.setInvulnerable(false);
                    } catch (Throwable ignored) {
                    }
                    if (plugin.getConfigManager().isClientProtectionMsgEnabled()) {
                        plugin.getMessageManager().sendMessage(player, "spawn.protection-ended", null);
                    }
                }
            }, invulSecs * 20L);

            if (protectionTask != null) {
                activeProtectionTasks.put(player.getUniqueId(), protectionTask);
            }
        }

        if (protectionEnabled) {
            applyPotionEffects(player);
        }

        if (plugin.getConfigManager().isSoundEnabled()) {
            PaperCompatibility.playSoundSafely(player, location,
                    plugin.getConfigManager().getSoundName(),
                    plugin.getConfigManager().getSoundVolume(),
                    plugin.getConfigManager().getSoundPitch());
        }

        if (plugin.getConfigManager().isParticleEnabled()) {
            Location particleLoc = location.clone().add(0, 1, 0);
            PaperCompatibility.spawnParticleSafely(player.getWorld(),
                    plugin.getConfigManager().getParticleType(),
                    particleLoc,
                    plugin.getConfigManager().getParticleCount(),
                    0.5, 0.5, 0.5,
                    plugin.getConfigManager().getParticleSpeed());
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        placeholders.put("world", location.getWorld().getName());
        placeholders.put("x", String.valueOf(location.getBlockX()));
        placeholders.put("y", String.valueOf(location.getBlockY()));
        placeholders.put("z", String.valueOf(location.getBlockZ()));
        placeholders.put("exact_x", String.format(Locale.ROOT, "%.1f", location.getX()));
        placeholders.put("exact_y", String.format(Locale.ROOT, "%.1f", location.getY()));
        placeholders.put("exact_z", String.format(Locale.ROOT, "%.1f", location.getZ()));
        int dist = (int) Math.hypot(location.getX() - settings.getCenterX(), location.getZ() - settings.getCenterZ());
        placeholders.put("distance", String.valueOf(dist));
        placeholders.put("seconds", String.valueOf(invulSecs));
        placeholders.put("center_x", String.valueOf(settings.getCenterX()));
        placeholders.put("center_z", String.valueOf(settings.getCenterZ()));
        placeholders.put("yaw", String.valueOf((int) location.getYaw()));
        placeholders.put("pitch", String.valueOf((int) location.getPitch()));

        String rawBiome = PaperCompatibility.getBiomeNameAt(location);
        placeholders.put("biome_raw", rawBiome);
        placeholders.put("biome", plugin.getMessageManager().getLocalizedBiome(player, rawBiome));
        placeholders.put("direction", plugin.getMessageManager().getLocalizedDirection(player, location.getYaw()));
        placeholders.put("prefix", plugin.getMessageManager().getPrefix(player));

        // 1. 发送主要聊天消息 (若客户端聊天提示开启)
        if (plugin.getConfigManager().isClientChatEnabled()) {
            String chatPath = isFirstJoin ? "spawn.first-join-chat" : "spawn.respawn-chat";
            plugin.getMessageManager().sendMessage(player, chatPath, placeholders);
        }

        // 2. 发送详细位置信息卡片 (若客户端位置卡片开启且语言文件中配置了)
        if (plugin.getConfigManager().isClientLocationCardEnabled()) {
            String infoPath = isFirstJoin ? "spawn.first-join-location-info" : "spawn.respawn-location-info";
            if (plugin.getMessageManager().hasMessage(player, infoPath)) {
                plugin.getMessageManager().sendMessage(player, infoPath, placeholders);
            }
        }

        // 3. 出生保护提示 (若客户端保护提示开启)
        if (invulSecs > 0 && plugin.getConfigManager().isClientProtectionMsgEnabled()) {
            plugin.getMessageManager().sendMessage(player, "spawn.protection-started", placeholders);
        }

        // 4. 标题与副标题
        String titlePath = isFirstJoin ? "spawn.title-first-join" : "spawn.title-respawn";
        String subtitlePath = isFirstJoin ? "spawn.subtitle-first-join" : "spawn.subtitle-respawn";
        plugin.getMessageManager().sendTitle(player, titlePath, subtitlePath, placeholders,
                plugin.getConfigManager().getTitleFadeIn(),
                plugin.getConfigManager().getTitleStay(),
                plugin.getConfigManager().getTitleFadeOut());

        // 5. 动作栏提示 (优先专属项，回退到通用项)
        String actionbarPath = isFirstJoin ? "spawn.actionbar-first-join" : "spawn.actionbar-respawn";
        if (!plugin.getMessageManager().hasMessage(player, actionbarPath)) {
            actionbarPath = "spawn.actionbar";
        }
        plugin.getMessageManager().sendActionBar(player, actionbarPath, placeholders);

        // 6. 全服广播 (如果配置了且非空)
        String broadcastPath = isFirstJoin ? "spawn.broadcast-first-join" : "spawn.broadcast-respawn";
        if (plugin.getMessageManager().hasMessage(null, broadcastPath)) {
            plugin.getMessageManager().broadcastMessage(broadcastPath, placeholders);
        }

        // 7. 管理员重生监控提示 (如果配置了且非空)
        if (!isFirstJoin && plugin.getMessageManager().hasMessage(null, "spawn.admin-notify-respawn")) {
            plugin.getMessageManager().sendPermissionMessage("anarchyspawn.admin", "spawn.admin-notify-respawn",
                    placeholders);
        }

        // 8. 服务端控制台日志提示 (若服务端配置开启)
        boolean shouldLogServer = isFirstJoin
                ? plugin.getConfigManager().isServerLogFirstJoin()
                : plugin.getConfigManager().isServerLogRespawn();
        if (shouldLogServer) {
            String consolePath = isFirstJoin ? "spawn.server-log-first-join" : "spawn.server-log-respawn";
            Map<String, String> serverPlaceholders = new HashMap<>(placeholders);
            serverPlaceholders.put("biome", plugin.getMessageManager().getLocalizedBiome(null, rawBiome));
            serverPlaceholders.put("direction",
                    plugin.getMessageManager().getLocalizedDirection(null, location.getYaw()));

            if (plugin.getMessageManager().hasMessage(null, consolePath)) {
                String consoleMsg = plugin.getMessageManager().getFormatted(null, consolePath, serverPlaceholders);
                plugin.getLogger().info(net.md_5.bungee.api.ChatColor.stripColor(consoleMsg));
            } else {
                String localizedBiome = plugin.getMessageManager().getLocalizedBiome(null, rawBiome);
                if (isFirstJoin) {
                    plugin.getLogger()
                            .info(String.format(
                                    "Player %s initial spawn at %s (X: %d, Y: %d, Z: %d, Biome: %s, Distance: %d)",
                                    player.getName(), location.getWorld().getName(), location.getBlockX(),
                                    location.getBlockY(), location.getBlockZ(), localizedBiome, dist));
                } else {
                    plugin.getLogger()
                            .info(String.format(
                                    "Player %s respawned at %s (X: %d, Y: %d, Z: %d, Biome: %s, Distance: %d)",
                                    player.getName(), location.getWorld().getName(), location.getBlockX(),
                                    location.getBlockY(), location.getBlockZ(), localizedBiome, dist));
                }
            }
        }

    }

    private void applyPotionEffects(Player player) {
        for (String entry : plugin.getConfigManager().getPotionEffects()) {
            try {
                String[] parts = entry.split(":");
                if (parts.length >= 3) {
                    String effectName = parts[0].trim().toUpperCase(Locale.ROOT).replace(" ", "_");
                    PotionEffectType type = resolvePotionEffectType(effectName);
                    int durationTicks = Integer.parseInt(parts[1].trim()) * 20;
                    int amplifier = Math.max(0, Integer.parseInt(parts[2].trim()) - 1);

                    if (type != null && durationTicks > 0) {
                        player.addPotionEffect(new PotionEffect(type, durationTicks, amplifier, false, false, true));
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static final Map<String, String[]> POTION_ALIASES = new HashMap<>();
    static {
        addPotionAlias("RESISTANCE", "DAMAGE_RESISTANCE", "resistance");
        addPotionAlias("STRENGTH", "INCREASE_DAMAGE", "strength");
        addPotionAlias("SLOWNESS", "SLOW", "slowness");
        addPotionAlias("HASTE", "FAST_DIGGING", "haste");
        addPotionAlias("MINING_FATIGUE", "SLOW_DIGGING", "mining_fatigue");
        addPotionAlias("INSTANT_HEALTH", "HEAL", "instant_health");
        addPotionAlias("INSTANT_DAMAGE", "HARM", "instant_damage");
        addPotionAlias("JUMP_BOOST", "JUMP", "jump_boost");
        addPotionAlias("NAUSEA", "CONFUSION", "nausea");
        addPotionAlias("REGENERATION", "REGENERATION", "regeneration");
        addPotionAlias("SPEED", "SPEED", "speed");
        addPotionAlias("FIRE_RESISTANCE", "FIRE_RESISTANCE", "fire_resistance");
        addPotionAlias("WATER_BREATHING", "WATER_BREATHING", "water_breathing");
        addPotionAlias("INVISIBILITY", "INVISIBILITY", "invisibility");
        addPotionAlias("NIGHT_VISION", "NIGHT_VISION", "night_vision");
        addPotionAlias("WEAKNESS", "WEAKNESS", "weakness");
        addPotionAlias("POISON", "POISON", "poison");
        addPotionAlias("WITHER", "WITHER", "wither");
        addPotionAlias("ABSORPTION", "ABSORPTION", "absorption");
        addPotionAlias("SATURATION", "SATURATION", "saturation");
    }

    private static void addPotionAlias(String modernEnum, String legacyEnum, String key) {
        String[] arr = new String[]{modernEnum, legacyEnum, key};
        POTION_ALIASES.put(modernEnum, arr);
        POTION_ALIASES.put(legacyEnum, arr);
    }

    @SuppressWarnings("deprecation")
    private PotionEffectType resolvePotionEffectType(String name) {
        if (name == null || name.isEmpty())
            return null;
        String clean = name.trim().toUpperCase(Locale.ROOT).replace(" ", "_");

        // 1. Try getByName with alias options (1.12.2 legacy compatibility)
        String[] aliases = POTION_ALIASES.get(clean);
        if (aliases != null) {
            for (String alias : aliases) {
                try {
                    PotionEffectType type = PotionEffectType.getByName(alias);
                    if (type != null) return type;
                } catch (Throwable ignored) {
                }
            }
        }

        try {
            PotionEffectType type = PotionEffectType.getByName(clean);
            if (type != null) return type;
        } catch (Throwable ignored) {
        }

        // 2. Try getByKey for 1.13+ NamespacedKey lookup
        String standardKey = aliases != null && aliases.length >= 3 ? aliases[2] : clean.toLowerCase(Locale.ROOT);
        try {
            PotionEffectType type = PotionEffectType.getByKey(NamespacedKey.minecraft(standardKey));
            if (type != null) return type;
        } catch (Throwable ignored) {
        }

        return null;
    }

    public void cancelPlayerProtection(Player player) {
        if (player == null) return;
        activeProtectionSessions.remove(player.getUniqueId());
        BukkitTask task = activeProtectionTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        try {
            player.setInvulnerable(false);
        } catch (Throwable ignored) {
        }
    }

    public void cancelAllProtectionTasks() {
        activeProtectionSessions.clear();
        for (BukkitTask task : activeProtectionTasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        activeProtectionTasks.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p != null && p.isOnline()) {
                try {
                    p.setInvulnerable(false);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
