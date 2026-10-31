package com.anarchy.spawn.service;

import com.anarchy.spawn.AnarchySpawn;
import com.anarchy.spawn.config.WorldSpawnSettings;
import com.anarchy.spawn.util.FoliaScheduler;
import com.anarchy.spawn.util.PaperCompatibility;
import com.anarchy.spawn.util.SafetyValidator;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * 在配置的世界半径范围内寻找安全位置。
 * 采用均匀环形面积分布算法：r = sqrt(u * (R_max^2 - R_min^2) + R_min^2)
 *
 * 实现两阶段搜索：
 * 1. 首要阶段：在遵循死亡热点规避的前提下搜索安全位置。
 * 2. 二级保底阶段：若因热点过滤导致首要阶段找不到位置，
 *    则忽略热点重新搜索，确保玩家依然能在荒野正常出生。
 */
public class SafeLocationFinder {

    private final AnarchySpawn plugin;
    private final Random random = new Random();

    public SafeLocationFinder(AnarchySpawn plugin) {
        this.plugin = plugin;
    }

    /**
     * 异步搜索安全位置；若首要的热点过滤搜索超过最大尝试次数，
     * 则自动回退到忽略热点的普通安全搜索。
     */
    public CompletableFuture<SearchResult> findSafeLocationAsync(World world, WorldSpawnSettings settings) {
        CompletableFuture<SearchResult> future = new CompletableFuture<>();

        if (world == null || settings == null) {
            future.complete(new SearchResult(null, 0, 0, false));
            return future;
        }

        if (!plugin.getConfigManager().isAsyncSearch()) {
            FoliaScheduler.runForRegion(plugin, world, () -> {
                try {
                    SearchResult syncResult = findSafeLocationSync(world, settings);
                    future.complete(syncResult);
                } catch (Throwable t) {
                    future.complete(new SearchResult(settings.getFallbackLocation(world), 0, 0, false));
                }
            });
            return future;
        }

        long startTime = System.currentTimeMillis();
        int maxAttempts = plugin.getConfigManager().getMaxSearchAttempts();

        // 阶段 1：启用死亡热点规避检查进行搜索
        searchNextAttemptAsync(world, settings, 1, 0, maxAttempts, true, startTime, future);
        return future;
    }

    private int getWorldMinHeight(World world) {
        try {
            return world.getMinHeight();
        } catch (NoSuchMethodError | NoSuchFieldError | Exception ignored) {
            return 0;
        }
    }

    private void searchNextAttemptAsync(World world, WorldSpawnSettings settings, int currentAttempt, int attemptOffset,
            int maxAttempts, boolean checkHotspots, long startTime, CompletableFuture<SearchResult> future) {
        if (!plugin.isEnabled()) {
            future.complete(new SearchResult(null, attemptOffset + Math.max(0, currentAttempt - 1), 0, false));
            return;
        }

        if (currentAttempt > maxAttempts) {
            int executedAttempts = attemptOffset + maxAttempts;
            // 若带有热点规避的首要搜索失败且存在活跃热点，则重试忽略热点的搜索
            boolean hasActiveHotspots = plugin.getDeathHotspotManager() != null
                    && plugin.getDeathHotspotManager().getActiveHotspotsCount(world.getName()) > 0;
            if (checkHotspots && plugin.getConfigManager().isDeathHotspotEnabled() && hasActiveHotspots) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info(String.format(
                        "[SafeLocationFinder] Hotspot-filtered search in '%s' exceeded max attempts (%d). Retrying normal spawn search ignoring hotspots...",
                        world.getName(), maxAttempts));
                }
                searchNextAttemptAsync(world, settings, 1, executedAttempts, maxAttempts, false, startTime, future);
                return;
            }

            // 两轮尝试均耗尽；返回紧急保底位置
            Location fallback = settings.getFallbackLocation(world);
            long duration = System.currentTimeMillis() - startTime;
            future.complete(new SearchResult(fallback, executedAttempts, duration, false));
            return;
        }

        double minR = Math.max(0, Math.min(settings.getMinRadius(), settings.getMaxRadius()));
        double maxR = Math.max(minR + 1.0, Math.max(settings.getMinRadius(), settings.getMaxRadius()));
        double u = random.nextDouble();
        double radius = Math.sqrt(u * (maxR * maxR - minR * minR) + minR * minR);
        double angle = random.nextDouble() * 2 * Math.PI;

        int targetX = (int) Math.round(settings.getCenterX() + radius * Math.cos(angle));
        int targetZ = (int) Math.round(settings.getCenterZ() + radius * Math.sin(angle));

        PaperCompatibility.getChunkAtAsync(world, targetX >> 4, targetZ >> 4).thenAccept(chunk -> {
            if (!plugin.isEnabled()) {
                future.complete(new SearchResult(null, attemptOffset + currentAttempt, 0, false));
                return;
            }
            FoliaScheduler.runForRegion(plugin, world, targetX >> 4, targetZ >> 4, () -> {
                try {
                    if (!plugin.isEnabled()) {
                        future.complete(new SearchResult(null, attemptOffset + currentAttempt, 0, false));
                        return;
                    }
                    Location safeLoc = evaluateColumn(world, targetX, targetZ, settings, checkHotspots);
                    if (safeLoc != null) {
                        long duration = System.currentTimeMillis() - startTime;
                        String biomeName = getBiomeName(safeLoc);
                        future.complete(new SearchResult(safeLoc, attemptOffset + currentAttempt, duration, true, biomeName));
                    } else {
                        if (plugin.isEnabled()) {
                            searchNextAttemptAsync(world, settings, currentAttempt + 1, attemptOffset, maxAttempts, checkHotspots, startTime, future);
                        } else {
                            future.complete(new SearchResult(null, attemptOffset + currentAttempt, 0, false));
                        }
                    }
                } catch (Throwable t) {
                    if (plugin.isEnabled()) {
                        searchNextAttemptAsync(world, settings, currentAttempt + 1, attemptOffset, maxAttempts, checkHotspots, startTime, future);
                    } else {
                        future.complete(new SearchResult(null, attemptOffset + currentAttempt, 0, false));
                    }
                }
            });
        }).exceptionally(ex -> {
            if (plugin.isEnabled()) {
                searchNextAttemptAsync(world, settings, currentAttempt + 1, attemptOffset, maxAttempts, checkHotspots, startTime, future);
            } else {
                future.complete(new SearchResult(null, attemptOffset + currentAttempt, 0, false));
            }
            return null;
        });
    }

    /**
     * 同步搜索安全位置，并在需要时通过忽略热点的二级机制保底。
     */
    public SearchResult findSafeLocationSync(World world, WorldSpawnSettings settings) {
        long startTime = System.currentTimeMillis();
        int maxAttempts = plugin.getConfigManager().getMaxSearchAttempts();
        int totalAttempts = maxAttempts;

        // 阶段 1：尝试在规避死亡热点的前提下寻找位置
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Location safeLoc = sampleAndEvaluateSync(world, settings, true);
            if (safeLoc != null) {
                long duration = System.currentTimeMillis() - startTime;
                return new SearchResult(safeLoc, attempt, duration, true, getBiomeName(safeLoc));
            }
        }

        // 阶段 2：若阶段 1 因热点规避失败且存在活跃热点，则忽略热点重试搜索
        boolean hasActiveHotspots = plugin.getDeathHotspotManager() != null
                && plugin.getDeathHotspotManager().getActiveHotspotsCount(world.getName()) > 0;
        if (plugin.getConfigManager().isDeathHotspotEnabled() && hasActiveHotspots) {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info(String.format(
                    "[SafeLocationFinder] Sync hotspot-filtered search in '%s' failed. Fallback: Searching ignoring hotspots...",
                    world.getName()));
            }
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                Location safeLoc = sampleAndEvaluateSync(world, settings, false);
                if (safeLoc != null) {
                    long duration = System.currentTimeMillis() - startTime;
                    return new SearchResult(safeLoc, maxAttempts + attempt, duration, true, getBiomeName(safeLoc));
                }
            }
            totalAttempts = maxAttempts * 2;
        }

        long duration = System.currentTimeMillis() - startTime;
        return new SearchResult(settings.getFallbackLocation(world), totalAttempts, duration, false, "UNKNOWN");
    }

    private Location sampleAndEvaluateSync(World world, WorldSpawnSettings settings, boolean checkHotspots) {
        double minR = Math.max(0, Math.min(settings.getMinRadius(), settings.getMaxRadius()));
        double maxR = Math.max(minR + 1.0, Math.max(settings.getMinRadius(), settings.getMaxRadius()));
        double u = random.nextDouble();
        double radius = Math.sqrt(u * (maxR * maxR - minR * minR) + minR * minR);
        double angle = random.nextDouble() * 2 * Math.PI;

        int targetX = (int) Math.round(settings.getCenterX() + radius * Math.cos(angle));
        int targetZ = (int) Math.round(settings.getCenterZ() + radius * Math.sin(angle));

        Chunk chunk = world.getChunkAt(targetX >> 4, targetZ >> 4);
        if (!chunk.isLoaded()) {
            chunk.load();
        }

        return evaluateColumn(world, targetX, targetZ, settings, checkHotspots);
    }

    private String getBiomeName(Location loc) {
        if (loc == null) return "UNKNOWN";
        return PaperCompatibility.getBiomeNameAt(loc);
    }

    private Location evaluateColumn(World world, int x, int z, WorldSpawnSettings settings, boolean checkHotspots) {
        if (world == null || settings == null) {
            return null;
        }

        // 死亡热点检查：避免生成在近期的 PvP / 死亡陷阱区域（若启用了 checkHotspots）
        if (checkHotspots && plugin.getDeathHotspotManager() != null && plugin.getDeathHotspotManager().isNearHotspot(world.getName(), x, z)) {
            return null;
        }

        int worldMin = getWorldMinHeight(world);
        int worldMax = world.getMaxHeight();
        int maxY = Math.min(worldMax - 2, settings.getMaxY());
        int minY = Math.max(worldMin + 2, settings.getMinY());

        // 地狱环境保护：绝不生成在基岩天花板上方 (> 127)
        try {
            if (world.getEnvironment() == World.Environment.NETHER) {
                maxY = Math.min(maxY, 120);
            }
        } catch (Throwable ignored) {
        }

        if (world.getWorldBorder() != null && !world.getWorldBorder().isInside(new Location(world, x, minY, z))) {
            return null;
        }

        // 针对整列进行快速生物群系预检
        try {
            Biome sampleBiome = PaperCompatibility.getBiomeAt(world, x, (maxY + minY) / 2, z);
            if (!SafetyValidator.isSafeBiome(plugin, sampleBiome)) {
                return null;
            }
        } catch (Throwable ignored) {
        }

        boolean isOverworld = false;
        try {
            isOverworld = world.getEnvironment() == World.Environment.NORMAL;
        } catch (Throwable ignored) {
        }

        // --- 地表快速直达优化（提速 98%+） ---
        // 在主世界维度中，直接查询地表高度图以跳过逐格扫描 250+ 垂直高度的消耗
        if (isOverworld) {
            try {
                int surfaceY = world.getHighestBlockYAt(x, z);
                if (surfaceY >= minY && surfaceY <= maxY + 1) {
                    // 在 1.15+ 中，getHighestBlockYAt 返回最高非空气方块自身（例如草方块）。
                    // 在 1.12.2-1.14.4 中（或上方有高草丛/覆雪时），surfaceY 可能是可通过的方块，因此回退到 surfaceY - 1。
                    Block ground = world.getBlockAt(x, Math.min(maxY, surfaceY), z);
                    if (!ground.getType().isSolid() && surfaceY > worldMin) {
                        ground = world.getBlockAt(x, surfaceY - 1, z);
                    }
                    if (isSafeSpawnPoint(world, ground, isOverworld)) {
                        float yaw = (float) (random.nextFloat() * 360.0f - 180.0f);
                        return new Location(world, x + 0.5, ground.getY() + 1.0, z + 0.5, yaw, 0.0f);
                    }

                    // 在地表邻近层级进行定点扫描（地表 +6 至 -32 格，以穿透高大的丛林/橡树树冠）
                    int scanTop = Math.min(maxY, surfaceY + 6);
                    int scanBottom = Math.max(minY, surfaceY - 32);
                    for (int y = scanTop; y >= scanBottom; y--) {
                        Block candidate = world.getBlockAt(x, y, z);
                        if (isSafeSpawnPoint(world, candidate, isOverworld)) {
                            float yaw = (float) (random.nextFloat() * 360.0f - 180.0f);
                            return new Location(world, x + 0.5, y + 1.0, z + 0.5, yaw, 0.0f);
                        }
                    }
                }
                // 在主世界中，若地表高度超出 maxY 或判定不安全，不要回退到地下洞穴扫描
                return null;
            } catch (Throwable ignored) {
            }
        }

        // 针对地狱、末地或非地表维度的完整垂直列扫描
        for (int y = maxY; y >= minY; y--) {
            Block ground = world.getBlockAt(x, y, z);
            if (isSafeSpawnPoint(world, ground, isOverworld)) {
                float yaw = (float) (random.nextFloat() * 360.0f - 180.0f);
                return new Location(world, x + 0.5, y + 1.0, z + 0.5, yaw, 0.0f);
            }
        }

        return null;
    }

    /**
     * 检查给定的脚下方块及其上方 2 格站立空间是否构成有效且安全的出生点。
     */
    public boolean isSafeSpawnPoint(World world, Block ground, boolean isOverworld) {
        if (ground == null) return false;
        Block feet = ground.getRelative(BlockFace.UP);
        Block head = feet.getRelative(BlockFace.UP);

        Biome groundBiome = PaperCompatibility.getBiomeAt(world, ground.getX(), ground.getY(), ground.getZ());

        return SafetyValidator.isSafeFloor(plugin, ground)
                && SafetyValidator.isPassable(feet)
                && SafetyValidator.isPassable(head)
                && SafetyValidator.isSafeBiome(plugin, groundBiome)
                && SafetyValidator.isSafeSurroundings(feet);
    }

    /**
     * 若预缓存出生点所在的区块当前已加载在内存中，则重新校验其安全性。
     */
    public boolean isLocationStillSafeIfLoaded(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        World world = loc.getWorld();
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        try {
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                return true;
            }
            Block ground = world.getBlockAt(loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());
            boolean isOverworld = world.getEnvironment() == World.Environment.NORMAL;
            return isSafeSpawnPoint(world, ground, isOverworld);
        } catch (Throwable ignored) {
            // 在 Folia 上，若在非对应区域线程中被调用，则推迟校验至传送所在的线程
            return true;
        }
    }

    public static class SearchResult {
        private final Location location;
        private final int attempts;
        private final long durationMs;
        private final boolean success;
        private final String biome;

        public SearchResult(Location location, int attempts, long durationMs, boolean success) {
            this(location, attempts, durationMs, success, "UNKNOWN");
        }

        public SearchResult(Location location, int attempts, long durationMs, boolean success, String biome) {
            this.location = location;
            this.attempts = attempts;
            this.durationMs = durationMs;
            this.success = success;
            this.biome = biome != null ? biome : "UNKNOWN";
        }

        public Location getLocation() {
            return location;
        }

        public int getAttempts() {
            return attempts;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getBiome() {
            return biome;
        }
    }
}
