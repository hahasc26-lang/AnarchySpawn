package com.anarchy.spawn.config;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

public class WorldSpawnSettings {

    private final String worldName;
    private final boolean enabled;
    private boolean firstJoin;
    private boolean ignoreExistingPlayerData;
    private double centerX;
    private double centerZ;
    private double minRadius;
    private double maxRadius;
    private int minY;
    private int maxY;
    private double fallbackX;
    private double fallbackY;
    private double fallbackZ;

    public WorldSpawnSettings(String worldName, ConfigurationSection section) {
        this.worldName = worldName;
        this.enabled = section.getBoolean("enabled", true);

        // First join random spawn option per world
        if (section.isConfigurationSection("first-join")) {
            ConfigurationSection fjSec = section.getConfigurationSection("first-join");
            this.firstJoin = fjSec != null && fjSec.getBoolean("enabled", true);
            this.ignoreExistingPlayerData = fjSec == null || fjSec.getBoolean("ignore-existing-playerdata",
                    fjSec.getBoolean("ignore-existing-player",
                    fjSec.getBoolean("ignore-existing-data", true)));
        } else if (section.isSet("first-join")) {
            this.firstJoin = section.getBoolean("first-join");
            this.ignoreExistingPlayerData = true;
        } else if (section.isSet("first-join-enabled")) {
            this.firstJoin = section.getBoolean("first-join-enabled");
            this.ignoreExistingPlayerData = true;
        } else if (section.isSet("first-join-random-spawn")) {
            this.firstJoin = section.getBoolean("first-join-random-spawn");
            this.ignoreExistingPlayerData = true;
        } else {
            this.firstJoin = true;
            this.ignoreExistingPlayerData = true;
        }

        // Center
        if (section.isSet("center.x") && section.isSet("center.z")) {
            this.centerX = section.getDouble("center.x", 0.0);
            this.centerZ = section.getDouble("center.z", 0.0);
        } else {
            World w = Bukkit.getWorld(worldName);
            if (w != null) {
                this.centerX = w.getSpawnLocation().getX();
                this.centerZ = w.getSpawnLocation().getZ();
            } else {
                this.centerX = 0.0;
                this.centerZ = 0.0;
            }
        }

        this.minRadius = Math.max(0, section.getDouble("min-radius", 1000.0));
        this.maxRadius = Math.max(minRadius + 100, section.getDouble("max-radius", 25000.0));
        this.minY = section.getInt("min-y", 60);
        this.maxY = Math.max(minY + 2, section.getInt("max-y", 310));

        // Fallback
        this.fallbackX = section.getDouble("fallback-location.x", 0.0);
        this.fallbackY = section.getDouble("fallback-location.y", 80.0);
        this.fallbackZ = section.getDouble("fallback-location.z", 0.0);
    }

    public String getWorldName() { return worldName; }
    public boolean isEnabled() { return enabled; }
    public boolean isFirstJoin() { return firstJoin; }
    public boolean isFirstJoinEnabled() { return firstJoin; }
    public void setFirstJoin(boolean firstJoin) { this.firstJoin = firstJoin; }
    public boolean isIgnoreExistingPlayerData() { return ignoreExistingPlayerData; }
    public void setIgnoreExistingPlayerData(boolean ignoreExistingPlayerData) { this.ignoreExistingPlayerData = ignoreExistingPlayerData; }
    public double getCenterX() { return centerX; }
    public void setCenterX(double centerX) { this.centerX = centerX; }
    public double getCenterZ() { return centerZ; }
    public void setCenterZ(double centerZ) { this.centerZ = centerZ; }
    public double getMinRadius() { return minRadius; }
    public double getMaxRadius() { return maxRadius; }
    public int getMinY() { return minY; }
    public int getMaxY() { return maxY; }
    public double getFallbackX() { return fallbackX; }
    public double getFallbackY() { return fallbackY; }
    public double getFallbackZ() { return fallbackZ; }

    public Location getFallbackLocation(World world) {
        if (world == null) return null;
        return new Location(world, fallbackX + 0.5, fallbackY, fallbackZ + 0.5);
    }
}
