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
 * Searches for a safe location within the configured world radius.
 * Uses uniform annular area distribution: r = sqrt(u * (R_max^2 - R_min^2) + R_min^2)
 *
 * Implements two-phase search:
 * 1. Primary phase: Searches for safe locations respecting Death Hotspot avoidance.
 * 2. Secondary fallback phase: If primary phase fails to find a location due to hotspots,
 *    retries search ignoring hotspots so player can still spawn normally in the wilderness.
 */
public class SafeLocationFinder {

    private final AnarchySpawn plugin;
    private final Random random = new Random();

    public SafeLocationFinder(AnarchySpawn plugin) {
        this.plugin = plugin;
    }

    /**
     * Asynchronously finds a safe location, falling back to normal spawn ignoring hotspots
     * if primary hotspot-filtered search exceeds max attempts.
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

        // Phase 1: Search with hotspot avoidance check enabled
        searchNextAttemptAsync(world, settings, 1, maxAttempts, true, startTime, future);
        return future;
    }

    private int getWorldMinHeight(World world) {
        try {
            return world.getMinHeight();
        } catch (NoSuchMethodError | NoSuchFieldError | Exception ignored) {
            return 0;
        }
    }

    private void searchNextAttemptAsync(World world, WorldSpawnSettings settings, int currentAttempt, int maxAttempts,
            boolean checkHotspots, long startTime, CompletableFuture<SearchResult> future) {
        if (!plugin.isEnabled()) {
            future.complete(new SearchResult(null, currentAttempt, 0, false));
            return;
        }

        if (currentAttempt > maxAttempts) {
            // If primary search with hotspot avoidance failed and there were active hotspots, retry ignoring hotspots
            boolean hasActiveHotspots = plugin.getDeathHotspotManager() != null
                    && plugin.getDeathHotspotManager().getActiveHotspotsCount(world.getName()) > 0;
            if (checkHotspots && plugin.getConfigManager().isDeathHotspotEnabled() && hasActiveHotspots) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info(String.format(
                        "[SafeLocationFinder] Hotspot-filtered search in '%s' exceeded max attempts (%d). Retrying normal spawn search ignoring hotspots...",
                        world.getName(), maxAttempts));
                }
                searchNextAttemptAsync(world, settings, 1, maxAttempts, false, startTime, future);
                return;
            }

            // Both passes exhausted; return emergency fallback location
            Location fallback = settings.getFallbackLocation(world);
            long duration = System.currentTimeMillis() - startTime;
            future.complete(new SearchResult(fallback, currentAttempt, duration, false));
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
                future.complete(new SearchResult(null, currentAttempt, 0, false));
                return;
            }
            FoliaScheduler.runForRegion(plugin, world, targetX >> 4, targetZ >> 4, () -> {
                try {
                    if (!plugin.isEnabled()) {
                        future.complete(new SearchResult(null, currentAttempt, 0, false));
                        return;
                    }
                    Location safeLoc = evaluateColumn(world, targetX, targetZ, settings, checkHotspots);
                    if (safeLoc != null) {
                        long duration = System.currentTimeMillis() - startTime;
                        String biomeName = getBiomeName(safeLoc);
                        future.complete(new SearchResult(safeLoc, currentAttempt, duration, true, biomeName));
                    } else {
                        if (plugin.isEnabled()) {
                            searchNextAttemptAsync(world, settings, currentAttempt + 1, maxAttempts, checkHotspots, startTime, future);
                        } else {
                            future.complete(new SearchResult(null, currentAttempt, 0, false));
                        }
                    }
                } catch (Throwable t) {
                    if (plugin.isEnabled()) {
                        searchNextAttemptAsync(world, settings, currentAttempt + 1, maxAttempts, checkHotspots, startTime, future);
                    } else {
                        future.complete(new SearchResult(null, currentAttempt, 0, false));
                    }
                }
            });
        }).exceptionally(ex -> {
            if (plugin.isEnabled()) {
                searchNextAttemptAsync(world, settings, currentAttempt + 1, maxAttempts, checkHotspots, startTime, future);
            } else {
                future.complete(new SearchResult(null, currentAttempt, 0, false));
            }
            return null;
        });
    }

    /**
     * Synchronously finds a safe location with secondary fallback ignoring hotspots if needed.
     */
    public SearchResult findSafeLocationSync(World world, WorldSpawnSettings settings) {
        long startTime = System.currentTimeMillis();
        int maxAttempts = plugin.getConfigManager().getMaxSearchAttempts();

        // Phase 1: Try finding a location with death hotspot avoidance
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Location safeLoc = sampleAndEvaluateSync(world, settings, true);
            if (safeLoc != null) {
                long duration = System.currentTimeMillis() - startTime;
                return new SearchResult(safeLoc, attempt, duration, true, getBiomeName(safeLoc));
            }
        }

        // Phase 2: If Phase 1 failed due to hotspots and active hotspots exist, retry search ignoring hotspots
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
        }

        long duration = System.currentTimeMillis() - startTime;
        return new SearchResult(settings.getFallbackLocation(world), maxAttempts, duration, false, "UNKNOWN");
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

        // Death Hotspot check: avoid recent PvP / death trap areas (if checkHotspots is enabled)
        if (checkHotspots && plugin.getDeathHotspotManager() != null && plugin.getDeathHotspotManager().isNearHotspot(world.getName(), x, z)) {
            return null;
        }

        int worldMin = getWorldMinHeight(world);
        int worldMax = world.getMaxHeight();
        int maxY = Math.min(worldMax - 2, settings.getMaxY());
        int minY = Math.max(worldMin + 2, settings.getMinY());

        // Nether environment protection: never spawn on bedrock ceiling (> 127)
        try {
            if (world.getEnvironment() == World.Environment.NETHER) {
                maxY = Math.min(maxY, 120);
            }
        } catch (Throwable ignored) {
        }

        if (world.getWorldBorder() != null && !world.getWorldBorder().isInside(new Location(world, x, minY, z))) {
            return null;
        }

        // Quick column biome pre-check
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

        // --- Surface Direct Penetration Optimization (98%+ speedup) ---
        // In Overworld dimensions, directly query surface heightmap to bypass scanning 250+ vertical blocks
        if (isOverworld) {
            try {
                int surfaceY = world.getHighestBlockYAt(x, z);
                if (surfaceY >= minY && surfaceY <= maxY + 1) {
                    Block ground = world.getBlockAt(x, surfaceY > worldMin ? surfaceY - 1 : surfaceY, z);
                    if (!ground.getType().isSolid()) {
                        ground = world.getBlockAt(x, surfaceY, z);
                    }
                    if (isSafeSpawnPoint(world, ground, isOverworld)) {
                        float yaw = (float) (random.nextFloat() * 360.0f - 180.0f);
                        return new Location(world, x + 0.5, ground.getY() + 1.0, z + 0.5, yaw, 0.0f);
                    }

                    // Targeted narrow scan within surface neighborhood (surface +6 down to -12 blocks)
                    int scanTop = Math.min(maxY, surfaceY + 6);
                    int scanBottom = Math.max(minY, surfaceY - 12);
                    for (int y = scanTop; y >= scanBottom; y--) {
                        Block candidate = world.getBlockAt(x, y, z);
                        if (isSafeSpawnPoint(world, candidate, isOverworld)) {
                            float yaw = (float) (random.nextFloat() * 360.0f - 180.0f);
                            return new Location(world, x + 0.5, y + 1.0, z + 0.5, yaw, 0.0f);
                        }
                    }
                    return null;
                }
            } catch (Throwable ignored) {
            }
        }

        // Full vertical scan for Nether, End, or non-surface dimensions
        for (int y = maxY; y >= minY; y--) {
            Block ground = world.getBlockAt(x, y, z);
            if (isSafeSpawnPoint(world, ground, isOverworld)) {
                float yaw = (float) (random.nextFloat() * 360.0f - 180.0f);
                return new Location(world, x + 0.5, y + 1.0, z + 0.5, yaw, 0.0f);
            }
        }

        return null;
    }

    private boolean isSafeSpawnPoint(World world, Block ground, boolean isOverworld) {
        if (ground == null) return false;
        Block feet = ground.getRelative(BlockFace.UP);
        Block head = feet.getRelative(BlockFace.UP);

        Biome groundBiome = PaperCompatibility.getBiomeAt(world, ground.getX(), ground.getY(), ground.getZ());

        if (SafetyValidator.isSafeFloor(plugin, ground)
                && SafetyValidator.isPassable(feet)
                && SafetyValidator.isPassable(head)
                && SafetyValidator.isSafeBiome(plugin, groundBiome)
                && SafetyValidator.isSafeSurroundings(feet)) {
            return true;
        }
        return false;
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
