package com.anarchy.spawn.service;

import com.anarchy.spawn.AnarchySpawn;
import org.bukkit.Location;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Manages dynamic death and combat hotspot locations per world.
 * Automatically avoids placing respawning or newly spawning players
 * near recent PvP/death traps or bed-camping hotspot zones.
 */
public class DeathHotspotManager {

    private final AnarchySpawn plugin;
    private final Map<String, Queue<Hotspot>> hotspotsByWorld = new ConcurrentHashMap<>();

    public DeathHotspotManager(AnarchySpawn plugin) {
        this.plugin = plugin;
    }

    /**
     * Records a new death location if death hotspot avoidance is enabled.
     *
     * @param location Death location
     */
    public void recordDeath(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }

        if (!plugin.getConfigManager().isDeathHotspotEnabled()) {
            return;
        }

        String worldName = location.getWorld().getName().toLowerCase();
        Queue<Hotspot> queue = hotspotsByWorld.computeIfAbsent(worldName, k -> new ConcurrentLinkedQueue<>());

        long now = System.currentTimeMillis();
        long ttlMillis = plugin.getConfigManager().getDeathHotspotTtlSeconds() * 1000L;
        int maxHotspots = plugin.getConfigManager().getDeathHotspotMaxPerWorld();

        // Remove expired hotspots
        queue.removeIf(h -> (now - h.getTimestamp()) > ttlMillis);

        // If an existing hotspot is close (< 5 blocks), refresh timestamp instead of flooding queue
        for (Hotspot h : queue) {
            double dx = location.getX() - h.getX();
            double dz = location.getZ() - h.getZ();
            if ((dx * dx + dz * dz) <= 25.0) {
                h.setTimestamp(now);
                return;
            }
        }

        // Keep size within limit
        while (queue.size() >= maxHotspots) {
            queue.poll();
        }

        queue.add(new Hotspot(location.getX(), location.getZ(), now));

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info(String.format(
                    "[DeathHotspot] Recorded hotspot in world '%s' at X:%.1f Z:%.1f (Total active: %d)",
                    worldName, location.getX(), location.getZ(), queue.size()));
        }
    }

    /**
     * Checks if the given X, Z coordinate in a world is within the blacklisted radius
     * of any active death hotspot.
     *
     * @param worldName World name
     * @param x X coordinate
     * @param z Z coordinate
     * @return true if coordinate is inside a death hotspot zone, false otherwise
     */
    public boolean isNearHotspot(String worldName, double x, double z) {
        if (worldName == null || !plugin.getConfigManager().isDeathHotspotEnabled()) {
            return false;
        }

        Queue<Hotspot> queue = hotspotsByWorld.get(worldName.toLowerCase());
        if (queue == null || queue.isEmpty()) {
            return false;
        }

        long now = System.currentTimeMillis();
        long ttlMillis = plugin.getConfigManager().getDeathHotspotTtlSeconds() * 1000L;
        double radius = plugin.getConfigManager().getDeathHotspotRadius();
        double radiusSq = radius * radius;

        for (Hotspot h : queue) {
            if (now - h.getTimestamp() > ttlMillis) {
                continue;
            }

            double dx = x - h.getX();
            double dz = z - h.getZ();
            if ((dx * dx + dz * dz) <= radiusSq) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns the count of active (non-expired) hotspots for a specified world.
     */
    public int getActiveHotspotsCount(String worldName) {
        if (worldName == null) return 0;
        Queue<Hotspot> queue = hotspotsByWorld.get(worldName.toLowerCase());
        if (queue == null || queue.isEmpty()) return 0;

        long now = System.currentTimeMillis();
        long ttlMillis = plugin.getConfigManager().getDeathHotspotTtlSeconds() * 1000L;
        return (int) queue.stream().filter(h -> (now - h.getTimestamp()) <= ttlMillis).count();
    }

    /**
     * Clears all recorded hotspots for all worlds.
     */
    public void clearAllHotspots() {
        hotspotsByWorld.clear();
    }

    /**
     * Data holder for a single death hotspot location and time.
     */
    public static class Hotspot {
        private final double x;
        private final double z;
        private volatile long timestamp;

        public Hotspot(double x, double z, long timestamp) {
            this.x = x;
            this.z = z;
            this.timestamp = timestamp;
        }

        public double getX() {
            return x;
        }

        public double getZ() {
            return z;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
    }
}
