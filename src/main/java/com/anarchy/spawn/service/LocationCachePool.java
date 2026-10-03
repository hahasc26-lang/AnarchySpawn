package com.anarchy.spawn.service;

import com.anarchy.spawn.AnarchySpawn;
import com.anarchy.spawn.config.WorldSpawnSettings;
import com.anarchy.spawn.util.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class LocationCachePool {

    private final AnarchySpawn plugin;
    private final Map<String, Queue<CachedLocation>> pool = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inFlightRequests = new ConcurrentHashMap<>();
    private final AtomicBoolean isRefilling = new AtomicBoolean(false);
    private BukkitTask refillTask;

    public LocationCachePool(AnarchySpawn plugin) {
        this.plugin = plugin;
    }

    public void startScheduler() {
        if (!plugin.getConfigManager().isCacheEnabled())
            return;

        long intervalTicks = plugin.getConfigManager().getCacheRefillIntervalSeconds() * 20L;
        this.refillTask = FoliaScheduler.runTimerGlobal(plugin, this::refillAllWorlds, 20L, intervalTicks);
    }

    public void restartScheduler() {
        shutdown();
        pool.clear();
        inFlightRequests.clear();
        startScheduler();
    }

    public void shutdown() {
        if (refillTask != null) {
            refillTask.cancel();
            refillTask = null;
        }
        pool.clear();
        inFlightRequests.clear();
    }

    public Location poll(String worldName) {
        if (worldName == null)
            return null;
        Queue<CachedLocation> queue = pool.get(worldName.toLowerCase());
        if (queue == null || queue.isEmpty())
            return null;

        long maxAgeMs = plugin.getConfigManager().getMaxCacheAgeSeconds() * 1000L;
        long now = System.currentTimeMillis();

        while (!queue.isEmpty()) {
            CachedLocation cached = queue.poll();
            if (cached != null && (now - cached.getTimestamp()) <= maxAgeMs) {
                return cached.getLocation();
            }
        }
        return null;
    }

    public int getCacheSize(String worldName) {
        if (worldName == null)
            return 0;
        Queue<CachedLocation> queue = pool.get(worldName.toLowerCase());
        return queue != null ? queue.size() : 0;
    }

    public int getTotalCacheSize() {
        int total = 0;
        for (Queue<CachedLocation> queue : pool.values()) {
            if (queue != null) {
                total += queue.size();
            }
        }
        return total;
    }

    public void clearWorldCache(String worldName) {
        if (worldName == null)
            return;
        Queue<CachedLocation> queue = pool.get(worldName.toLowerCase());
        if (queue != null) {
            queue.clear();
        }
        AtomicInteger inFlight = inFlightRequests.get(worldName.toLowerCase());
        if (inFlight != null) {
            inFlight.set(0);
        }
    }

    public void refillAllWorlds() {
        if (!plugin.getConfigManager().isCacheEnabled() || !plugin.isEnabled())
            return;
        // The guard is released only after every per-world region task has finished
        // dispatching its async searches, preventing overlapping refill rounds that
        // would otherwise overfill the cache queues.
        if (!isRefilling.compareAndSet(false, true))
            return;

        int targetSize = plugin.getConfigManager().getCacheTargetSize();

        try {
            for (Map.Entry<String, WorldSpawnSettings> entry : plugin.getConfigManager().getAllWorldSettings()
                    .entrySet()) {
                String worldKey = entry.getKey();
                WorldSpawnSettings settings = entry.getValue();
                if (!settings.isEnabled())
                    continue;

                World world = Bukkit.getWorld(settings.getWorldName());
                if (world == null)
                    continue;

                // World/chunk data must be accessed on the owning world's region thread
                // (Folia) or the main thread (Spigot/Paper). FoliaScheduler handles both.
                FoliaScheduler.runForRegion(plugin, world, () -> refillWorld(worldKey, settings, world, targetSize));
            }
        } finally {
            // On non-Folia runtimes runForRegion executes synchronously on the main
            // thread, so the guard can be released immediately. On Folia the region
            // tasks are queued asynchronously; release the guard on the next tick so
            // the in-flight counters (not this flag) throttle concurrent work.
            if (FoliaScheduler.isFolia()) {
                FoliaScheduler.runTimerGlobal(plugin, () -> isRefilling.set(false), 1L, -1L);
            } else {
                isRefilling.set(false);
            }
        }
    }

    /**
     * Refills the cache queue for a single world. Must be invoked on the owning
     * world's region thread (Folia) or the main thread (Spigot/Paper).
     */
    private void refillWorld(String worldKey, WorldSpawnSettings settings, World world, int targetSize) {
        if (!plugin.isEnabled())
            return;

        Queue<CachedLocation> queue = pool.computeIfAbsent(worldKey, k -> new ConcurrentLinkedQueue<>());
        AtomicInteger inFlight = inFlightRequests.computeIfAbsent(worldKey, k -> new AtomicInteger(0));

        long maxAgeMs = plugin.getConfigManager().getMaxCacheAgeSeconds() * 1000L;
        long now = System.currentTimeMillis();
        queue.removeIf(loc -> (now - loc.getTimestamp()) > maxAgeMs);

        int currentInFlight = inFlight.get();
        int maxInFlightForWorld = 3;
        if (currentInFlight >= maxInFlightForWorld) {
            return;
        }

        int needed = targetSize - (queue.size() + currentInFlight);
        if (needed > 0) {
            int toLaunch = Math.min(needed, maxInFlightForWorld - currentInFlight);
            for (int i = 0; i < toLaunch; i++) {
                inFlight.incrementAndGet();
                plugin.getLocationFinder().findSafeLocationAsync(world, settings).whenComplete((result, ex) -> {
                    inFlight.decrementAndGet();
                    if (result != null && result.isSuccess() && result.getLocation() != null
                            && plugin.isEnabled()) {
                        queue.offer(new CachedLocation(result.getLocation(), System.currentTimeMillis()));
                        if (plugin.getConfigManager().isDebug()) {
                            plugin.getLogger().info("[CachePool] Cached safe point for " + worldKey + " (Size: "
                                    + queue.size() + ")");
                        }
                    }
                });
            }
        }
    }

    public static class CachedLocation {
        private final Location location;
        private final long timestamp;

        public CachedLocation(Location location, long timestamp) {
            this.location = location;
            this.timestamp = timestamp;
        }

        public Location getLocation() {
            return location;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }
}
