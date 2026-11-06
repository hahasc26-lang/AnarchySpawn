package com.anarchy.spawn.service;

import com.anarchy.spawn.AnarchySpawn;
import com.anarchy.spawn.config.WorldSpawnSettings;
import com.anarchy.spawn.util.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

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
    private Object refillTaskHandle;
    private volatile boolean isRunning = false;

    public LocationCachePool(AnarchySpawn plugin) {
        this.plugin = plugin;
    }

    public void startScheduler() {
        if (!plugin.getConfigManager().isCacheEnabled())
            return;

        this.isRunning = true;
        long intervalTicks = plugin.getConfigManager().getCacheRefillIntervalSeconds() * 20L;
        this.refillTaskHandle = FoliaScheduler.runTimerGlobalTask(plugin, this::refillAllWorlds, 20L, intervalTicks);
    }

    public void restartScheduler() {
        shutdown();
        pool.clear();
        inFlightRequests.clear();
        startScheduler();
    }

    public void shutdown() {
        this.isRunning = false;
        if (refillTaskHandle != null) {
            FoliaScheduler.cancelTask(refillTaskHandle);
            refillTaskHandle = null;
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
                Location loc = cached.getLocation();
                if (loc == null || loc.getWorld() == null) {
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info(String.format(
                                "[CachePool] Discarded cached point in '%s' because the world is unloaded or invalid.",
                                worldName));
                    }
                    continue;
                }
                if (plugin.getDeathHotspotManager() != null &&
                        plugin.getDeathHotspotManager().isNearHotspot(worldName, loc.getX(), loc.getZ())) {
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info(String.format(
                                "[CachePool] Discarded cached point in '%s' (X:%.1f, Z:%.1f) because it became a death hotspot.",
                                worldName, loc.getX(), loc.getZ()));
                    }
                    continue;
                }
                if (plugin.getLocationFinder() != null && !plugin.getLocationFinder().isLocationStillSafeIfLoaded(loc)) {
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info(String.format(
                                "[CachePool] Discarded cached point in '%s' (X:%.1f, Y:%.1f, Z:%.1f) because terrain is no longer safe.",
                                worldName, loc.getX(), loc.getY(), loc.getZ()));
                    }
                    continue;
                }
                return loc;
            }
        }
        return null;
    }

    /**
     * 主动保证指定世界的缓存水位。当缓存存量低于目标容量时，立即异步调度进行补满（如玩家死亡等待复活期间预热）。
     *
     * @param worldName 目标世界名
     */
    public void ensureSupply(String worldName) {
        if (!isRunning || !plugin.getConfigManager().isCacheEnabled() || !plugin.isEnabled() || worldName == null) {
            return;
        }
        WorldSpawnSettings settings = plugin.getConfigManager().getWorldSettings(worldName);
        if (settings == null || !settings.isEnabled()) {
            return;
        }
        World world = Bukkit.getWorld(settings.getWorldName());
        if (world == null) {
            return;
        }
        int targetSize = plugin.getConfigManager().getCacheTargetSize();
        Queue<CachedLocation> queue = pool.get(worldName.toLowerCase());
        int currentSize = queue != null ? queue.size() : 0;
        if (currentSize < targetSize) {
            FoliaScheduler.runForRegion(plugin, world, () -> refillWorld(worldName.toLowerCase(), settings, world, targetSize));
        }
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
        if (!isRunning || !plugin.getConfigManager().isCacheEnabled() || !plugin.isEnabled())
            return;
        // 防重入标记将在所有世界的任务进入队列后的下一 tick 释放，
        // 防止多轮补充任务重叠，并通过正在进行的计数器控制并发度。
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

                // 世界/区块数据必须在归属世界的区域线程（Folia）或主线程（Spigot/Paper）上访问。FoliaScheduler 会统一处理。
                FoliaScheduler.runForRegion(plugin, world, () -> refillWorld(worldKey, settings, world, targetSize));
            }
        } finally {
            FoliaScheduler.runTimerGlobal(plugin, () -> isRefilling.set(false), 1L, -1L);
        }
    }

    /**
     * 补充单个世界的缓存队列。必须在归属世界的区域线程（Folia）或主线程（Spigot/Paper）上调用。
     */
    private void refillWorld(String worldKey, WorldSpawnSettings settings, World world, int targetSize) {
        if (!isRunning || !plugin.isEnabled())
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
                    int remainingInFlight = inFlight.decrementAndGet();
                    if (!isRunning || !plugin.isEnabled()) {
                        return;
                    }
                    if (result != null && result.isSuccess() && result.getLocation() != null) {
                        queue.offer(new CachedLocation(result.getLocation(), System.currentTimeMillis()));
                        if (plugin.getConfigManager().isDebug()) {
                            plugin.getLogger().info("[CachePool] Cached safe point for " + worldKey + " (Size: "
                                     + queue.size() + "/" + targetSize + ")");
                        }
                        // 若缓存池仍未达到目标容量，则链式触发下一批次查找
                        if (queue.size() + remainingInFlight < targetSize) {
                            FoliaScheduler.runForRegion(plugin, world,
                                    () -> refillWorld(worldKey, settings, world, targetSize));
                        }
                    }
                });
            }
        }
    }

    /**
     * 线程安全且对垃圾回收友好的缓存坐标载体。
     * 绝不直接强引用 org.bukkit.World 或 org.bukkit.Location，杜绝多世界卸载时引起的严重地图内存泄漏。
     */
    public static class CachedLocation {
        private final String worldName;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final float pitch;
        private final long timestamp;

        public CachedLocation(Location location, long timestamp) {
            this.worldName = (location != null && location.getWorld() != null) ? location.getWorld().getName() : "";
            this.x = location != null ? location.getX() : 0.0;
            this.y = location != null ? location.getY() : 0.0;
            this.z = location != null ? location.getZ() : 0.0;
            this.yaw = location != null ? location.getYaw() : 0.0f;
            this.pitch = location != null ? location.getPitch() : 0.0f;
            this.timestamp = timestamp;
        }

        public Location getLocation() {
            if (worldName == null || worldName.isEmpty()) {
                return null;
            }
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return null;
            }
            return new Location(world, x, y, z, yaw, pitch);
        }

        public String getWorldName() {
            return worldName;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getZ() {
            return z;
        }

        public float getYaw() {
            return yaw;
        }

        public float getPitch() {
            return pitch;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }
}
