package com.anarchy.spawn.service;

import com.anarchy.spawn.AnarchySpawn;
import org.bukkit.Location;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 管理各世界动态死亡和战斗热点坐标。
 * 自动避免将复活或新出生的玩家生成在近期 PvP/死亡陷阱或堵床蹲点热点区域附近。
 */
public class DeathHotspotManager {

    private final AnarchySpawn plugin;
    private final Map<String, Queue<Hotspot>> hotspotsByWorld = new ConcurrentHashMap<>();

    public DeathHotspotManager(AnarchySpawn plugin) {
        this.plugin = plugin;
    }

    /**
     * 若启用了死亡热点规避，则记录新的死亡位置。
     *
     * @param location 死亡位置
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

        // 移除已过期的热点
        queue.removeIf(h -> (now - h.getTimestamp()) > ttlMillis);

        // 若已有热点非常接近（< 5 格），则刷新其时间戳而非重复塞入队列
        for (Hotspot h : queue) {
            double dx = location.getX() - h.getX();
            double dz = location.getZ() - h.getZ();
            if ((dx * dx + dz * dz) <= 25.0) {
                h.setTimestamp(now);
                return;
            }
        }

        // 保持队列大小在上限以内
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
     * 检查某个世界中的指定 X、Z 坐标是否处于任何活跃死亡热点的黑名单半径之内。
     *
     * @param worldName 世界名称
     * @param x X 坐标
     * @param z Z 坐标
     * @return 若坐标位于死亡热点区域内则返回 true，否则返回 false
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
     * 获取指定世界中活跃（未过期）的热点数量。
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
     * 清空所有世界记录的死亡热点。
     */
    public void clearAllHotspots() {
        hotspotsByWorld.clear();
    }

    /**
     * 单个死亡热点位置及时间戳的数据包装类。
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
