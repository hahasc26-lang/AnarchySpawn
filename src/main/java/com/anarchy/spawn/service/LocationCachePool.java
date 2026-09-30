package com.anarchy.spawn.service;

import com.anarchy.spawn.AnarchySpawn;
import com.anarchy.spawn.config.WorldSpawnSettings;
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
        if (!plugin.getConfigManager().isCacheEnabled()) return;

        long intervalTicks = plugin.getConfigManager().getCacheRefillIntervalSeconds() * 20L;
        this.refillTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refillAllWorlds, 20L, intervalTicks);
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
        if (worldName == null) return null;
        Queue<CachedLocation> queue = pool.get(worldName.toLowerCase());
        if (queue == null || queue.isEmpty()) return null;

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
        if (worldName == null) return 0;
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
        if (worldName == null) return;
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
        if (!plugin.getConfigManager().isCacheEnabled() || !plugin.isEnabled()) return;
        if (!isRefilling.compareAndSet(false, true)) return;

        try {
            int targetSize = plugin.getConfigManager().getCacheTargetSize();

            for (Map.Entry<String, WorldSpawnSettings> entry : plugin.getConfigManager().getAllWorldSettings().entrySet()) {
                String worldKey = entry.getKey();
                WorldSpawnSettings settings = entry.getValue();
                if (!settings.isEnabled()) continue;

                World world = Bukkit.getWorld(settings.getWorldName());
                if (world == null) continue;

                Queue<CachedLocation> queue = pool.computeIfAbsent(worldKey, k -> new ConcurrentLinkedQueue<>());
                AtomicInteger inFlight = inFlightRequests.computeIfAbsent(worldKey, k -> new AtomicInteger(0));

                long maxAgeMs = plugin.getConfigManager().getMaxCacheAgeSeconds() * 1000L;
                long now = System.currentTimeMillis();
                queue.removeIf(loc -> (now - loc.getTimestamp()) > maxAgeMs);

                int currentInFlight = inFlight.get();
                int maxInFlightForWorld = 3;
                if (currentInFlight >= maxInFlightForWorld) {
                    continue;
                }

                int needed = targetSize - (queue.size() + currentInFlight);
                if (needed > 0) {
                    int toLaunch = Math.min(needed, maxInFlightForWorld - currentInFlight);
                    for (int i = 0; i < toLaunch; i++) {
                        inFlight.incrementAndGet();
                        plugin.getLocationFinder().findSafeLocationAsync(world, settings).whenComplete((result, ex) -> {
                            inFlight.decrementAndGet();
                            if (result != null && result.isSuccess() && result.getLocation() != null && plugin.isEnabled()) {
                                queue.offer(new CachedLocation(result.getLocation(), System.currentTimeMillis()));
                                if (plugin.getConfigManager().isDebug()) {
                                    plugin.getLogger().info("[CachePool] Cached safe point for " + worldKey + " (Size: " + queue.size() + ")");
                                }
                            }
                        });
                    }
                }
            }
        } finally {
            isRefilling.set(false);
        }
    }

    public static class CachedLocation {
        private final Location location;
        private final long timestamp;

        public CachedLocation(Location location, long timestamp) {
            this.location = location;
            this.timestamp = timestamp;
        }

        public Location getLocation() { return location; }
        public long getTimestamp() { return timestamp; }
    }
}
