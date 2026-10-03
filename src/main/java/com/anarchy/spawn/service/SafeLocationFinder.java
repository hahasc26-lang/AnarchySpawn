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
 * Uses uniform annular area distribution: r = sqrt(u * (R_max^2 - R_min^2) +
 * R_min^2)
 */
public class SafeLocationFinder {

    private final AnarchySpawn plugin;
    private final Random random = new Random();

    public SafeLocationFinder(AnarchySpawn plugin) {
        this.plugin = plugin;
    }

    /**
     * Asynchronously finds a safe location, falling back to world spawn if max
     * attempts exceeded.
     */
    public CompletableFuture<SearchResult> findSafeLocationAsync(World world, WorldSpawnSettings settings) {
        CompletableFuture<SearchResult> future = new CompletableFuture<>();

        if (world == null || settings == null) {
            future.complete(new SearchResult(null, 0, 0, false));
            return future;
        }

        if (!plugin.getConfigManager().isAsyncSearch()) {
            // The synchronous search touches chunks/blocks, so it must run on the
            // owning world's region thread (Folia) or the main thread (Spigot/Paper).
            // This method may be invoked from an async thread (e.g. the cache pool),
            // so we always dispatch through FoliaScheduler instead of running inline.
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

        searchNextAttemptAsync(world, settings, 1, maxAttempts, startTime, future);
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
            long startTime, CompletableFuture<SearchResult> future) {
        if (!plugin.isEnabled()) {
            future.complete(new SearchResult(null, currentAttempt, 0, false));
            return;
        }

        if (currentAttempt > maxAttempts) {
            Location fallback = settings.getFallbackLocation(world);
            long duration = System.currentTimeMillis() - startTime;
            future.complete(new SearchResult(fallback, currentAttempt, duration, false));
            return;
        }

        double minR = settings.getMinRadius();
        double maxR = settings.getMaxRadius();
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
            FoliaScheduler.runForRegion(plugin, world, () -> {
                if (!plugin.isEnabled()) {
                    future.complete(new SearchResult(null, currentAttempt, 0, false));
                    return;
                }
                Location safeLoc = evaluateColumn(world, targetX, targetZ, settings);
                if (safeLoc != null) {
                    long duration = System.currentTimeMillis() - startTime;
                    String biomeName = "UNKNOWN";
                    try {
                        biomeName = safeLoc.getBlock().getBiome().name();
                    } catch (Throwable ignored) {
                    }
                    future.complete(new SearchResult(safeLoc, currentAttempt, duration, true, biomeName));
                } else {
                    if (plugin.isEnabled()) {
                        searchNextAttemptAsync(world, settings, currentAttempt + 1, maxAttempts, startTime, future);
                    } else {
                        future.complete(new SearchResult(null, currentAttempt, 0, false));
                    }
                }
            });
        }).exceptionally(ex -> {
            if (plugin.isEnabled()) {
                searchNextAttemptAsync(world, settings, currentAttempt + 1, maxAttempts, startTime, future);
            } else {
                future.complete(new SearchResult(null, currentAttempt, 0, false));
            }
            return null;
        });
    }

    /**
     * Synchronously finds a safe location.
     */
    public SearchResult findSafeLocationSync(World world, WorldSpawnSettings settings) {
        long startTime = System.currentTimeMillis();
        int maxAttempts = plugin.getConfigManager().getMaxSearchAttempts();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            double minR = settings.getMinRadius();
            double maxR = settings.getMaxRadius();
            double u = random.nextDouble();
            double radius = Math.sqrt(u * (maxR * maxR - minR * minR) + minR * minR);
            double angle = random.nextDouble() * 2 * Math.PI;

            int targetX = (int) Math.round(settings.getCenterX() + radius * Math.cos(angle));
            int targetZ = (int) Math.round(settings.getCenterZ() + radius * Math.sin(angle));

            Chunk chunk = world.getChunkAt(targetX >> 4, targetZ >> 4);
            if (!chunk.isLoaded()) {
                chunk.load();
            }

            Location safeLoc = evaluateColumn(world, targetX, targetZ, settings);
            if (safeLoc != null) {
                long duration = System.currentTimeMillis() - startTime;
                String biomeName = "UNKNOWN";
                try {
                    biomeName = safeLoc.getBlock().getBiome().name();
                } catch (Throwable ignored) {
                }
                return new SearchResult(safeLoc, attempt, duration, true, biomeName);
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        return new SearchResult(settings.getFallbackLocation(world), maxAttempts, duration, false, "UNKNOWN");
    }

    private Location evaluateColumn(World world, int x, int z, WorldSpawnSettings settings) {
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

        // Quick column biome pre-check (sample middle of column to skip blacklisted
        // ocean biomes fast)
        try {
            Biome sampleBiome = world.getBiome(x, (maxY + minY) / 2, z);
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

        for (int y = maxY; y >= minY; y--) {
            Block ground = world.getBlockAt(x, y, z);
            Block feet = ground.getRelative(BlockFace.UP);
            Block head = feet.getRelative(BlockFace.UP);

            if (SafetyValidator.isSafeFloor(plugin, ground)
                    && SafetyValidator.isPassable(feet)
                    && SafetyValidator.isPassable(head)
                    && SafetyValidator.isSafeBiome(plugin, ground.getBiome())
                    && SafetyValidator.isSafeSurroundings(feet)) {

                // In overworld, avoid spawning in pitch-black deep underground caves
                if (isOverworld) {
                    try {
                        if (feet.getLightFromSky() == 0 && y < 60) {
                            continue;
                        }
                    } catch (Throwable ignored) {
                    }
                }

                return new Location(world, x + 0.5, y + 1.0, z + 0.5);
            }
        }

        return null;
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
