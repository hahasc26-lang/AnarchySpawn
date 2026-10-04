package com.anarchy.spawn.config;

import com.anarchy.spawn.AnarchySpawn;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.*;

public class ConfigManager {

    private final AnarchySpawn plugin;
    private FileConfiguration config;

    // General
    private boolean debug;
    private boolean asyncSearch;
    private int maxSearchAttempts;

    // Language
    private String serverLanguage;
    private String clientDefaultLanguage;
    private boolean autoLanguage;

    // First Join
    private boolean firstJoinEnabled;
    private int firstJoinDelayTicks;
    private boolean trackPerWorldPDC;

    // Respawn
    private boolean respawnEnabled;
    private boolean overrideBed;
    private boolean overrideAnchor;
    private boolean respectBypassPermission;

    // Cache Pool
    private boolean cacheEnabled;
    private int cacheTargetSize;
    private int cacheRefillIntervalSeconds;
    private int maxCacheAgeSeconds;

    // Spawn Effects
    private boolean protectionEnabled;
    private int invulnerableSeconds;
    private List<String> potionEffects = new ArrayList<>();
    private boolean soundEnabled;
    private String soundName;
    private float soundVolume;
    private float soundPitch;
    private boolean particleEnabled;
    private String particleType;
    private int particleCount;
    private double particleSpeed;
    private boolean titleEnabled;
    private int titleFadeIn;
    private int titleStay;
    private int titleFadeOut;
    private boolean actionbarEnabled;

    // Notifications (Client & Server)
    private boolean clientChatEnabled;
    private boolean clientLocationCardEnabled;
    private boolean clientProtectionMsgEnabled;
    private boolean clientFallbackWarningEnabled;
    private boolean serverLogFirstJoin;
    private boolean serverLogRespawn;

    // Safety Filter
    private boolean enableSafeSpawnCheck;
    private final Set<Material> unsafeFloorMaterials = EnumSet.noneOf(Material.class);
    private final Set<String> biomeBlacklist = new HashSet<>();

    // Multi-World Settings
    private final Map<String, WorldSpawnSettings> worldSettings = new HashMap<>();

    public ConfigManager(AnarchySpawn plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.saveDefaultConfig();
        }
        plugin.reloadConfig();
        this.config = plugin.getConfig();

        // General
        this.debug = config.getBoolean("general.debug", false);
        this.asyncSearch = config.getBoolean("general.async-search", true);
        this.maxSearchAttempts = Math.max(5, config.getInt("general.max-search-attempts", 35));

        // Language
        this.serverLanguage = config.getString("language.server-language",
                config.getString("language.default-language", "zh_CN"));
        this.clientDefaultLanguage = config.getString("language.client-default-language",
                config.getString("language.default-language", "zh_CN"));
        this.autoLanguage = config.getBoolean("language.auto-language", true);

        // First Join
        this.firstJoinEnabled = config.getBoolean("first-join.enabled", true);
        this.firstJoinDelayTicks = Math.max(1, config.getInt("first-join.teleport-delay-ticks", 5));
        this.trackPerWorldPDC = config.getBoolean("first-join.track-per-world-pdc", true);

        // Respawn
        this.respawnEnabled = config.getBoolean("respawn.enabled", true);
        this.overrideBed = config.getBoolean("respawn.override-bed", true);
        this.overrideAnchor = config.getBoolean("respawn.override-anchor", true);
        this.respectBypassPermission = config.getBoolean("respawn.respect-bypass-permission", true);

        // Cache Pool
        this.cacheEnabled = config.getBoolean("cache-pool.enabled", true);
        this.cacheTargetSize = Math.max(5, config.getInt("cache-pool.target-size", 25));
        this.cacheRefillIntervalSeconds = Math.max(3, config.getInt("cache-pool.refill-interval-seconds", 10));
        this.maxCacheAgeSeconds = Math.max(60, config.getInt("cache-pool.max-cache-age-seconds", 1800));

        // Spawn Effects
        this.protectionEnabled = config.getBoolean("spawn-effects.protection.enabled", true);
        this.invulnerableSeconds = Math.max(0, config.getInt("spawn-effects.protection.invulnerable-seconds",
                config.getInt("spawn-effects.invulnerable-seconds", 15)));
        this.potionEffects = config.getStringList("spawn-effects.potion-effects");
        this.soundEnabled = config.getBoolean("spawn-effects.sound.enabled", true);
        this.soundName = config.getString("spawn-effects.sound.name", "ENTITY_PLAYER_LEVELUP");
        this.soundVolume = (float) config.getDouble("spawn-effects.sound.volume", 1.0);
        this.soundPitch = (float) config.getDouble("spawn-effects.sound.pitch", 1.2);
        this.particleEnabled = config.getBoolean("spawn-effects.particles.enabled", true);
        this.particleType = config.getString("spawn-effects.particles.type", "PORTAL");
        this.particleCount = config.getInt("spawn-effects.particles.count", 60);
        this.particleSpeed = config.getDouble("spawn-effects.particles.speed", 0.5);
        this.titleEnabled = config.getBoolean("spawn-effects.title.enabled", true);
        this.titleFadeIn = config.getInt("spawn-effects.title.fadeIn", 10);
        this.titleStay = config.getInt("spawn-effects.title.stay", 50);
        this.titleFadeOut = config.getInt("spawn-effects.title.fadeOut", 20);
        this.actionbarEnabled = config.getBoolean("spawn-effects.actionbar.enabled", true);

        // Notifications (Client & Server)
        this.clientChatEnabled = config.getBoolean("notifications.client.chat", true);
        this.clientLocationCardEnabled = config.getBoolean("notifications.client.location-card", true);
        this.clientProtectionMsgEnabled = config.getBoolean("notifications.client.protection", true);
        this.clientFallbackWarningEnabled = config.getBoolean("notifications.client.fallback-warning", true);
        this.serverLogFirstJoin = config.getBoolean("notifications.server.console-log-first-join", true);
        this.serverLogRespawn = config.getBoolean("notifications.server.console-log-respawn", true);

        // Safety Filter Materials
        this.enableSafeSpawnCheck = config.getBoolean("safety-filter.enforce-safe-spawn", true);

        this.unsafeFloorMaterials.clear();
        List<String> rawMaterials = config.getStringList("safety-filter.unsafe-floor-materials");
        for (String matName : rawMaterials) {
            try {
                Material mat = Material.matchMaterial(matName.toUpperCase());
                if (mat != null) {
                    this.unsafeFloorMaterials.add(mat);
                }
            } catch (Exception ignored) {}
        }
        // Always safeguard basic lethal blocks
        this.unsafeFloorMaterials.add(Material.LAVA);
        this.unsafeFloorMaterials.add(Material.FIRE);
        this.unsafeFloorMaterials.add(Material.AIR);
        try {
            this.unsafeFloorMaterials.add(Material.valueOf("SOUL_FIRE"));
            this.unsafeFloorMaterials.add(Material.valueOf("POWDER_SNOW"));
            this.unsafeFloorMaterials.add(Material.valueOf("SWEET_BERRY_BUSH"));
        } catch (Exception ignored) {}

        // Biome Blacklist
        this.biomeBlacklist.clear();
        for (String biome : config.getStringList("safety-filter.biome-blacklist")) {
            this.biomeBlacklist.add(biome.toUpperCase());
        }

        // Multi-World Settings
        this.worldSettings.clear();
        ConfigurationSection worldsSection = config.getConfigurationSection("worlds");
        if (worldsSection != null) {
            for (String worldKey : worldsSection.getKeys(false)) {
                ConfigurationSection wSec = worldsSection.getConfigurationSection(worldKey);
                if (wSec != null && wSec.getBoolean("enabled", true)) {
                    WorldSpawnSettings settings = new WorldSpawnSettings(worldKey, wSec);
                    this.worldSettings.put(worldKey.toLowerCase(), settings);
                }
            }
        }

        if (debug) {
            plugin.getLogger().info("Config loaded: " + worldSettings.size() + " active worlds configured.");
        }
    }

    public boolean isWorldEnabled(String worldName) {
        if (worldName == null) return false;
        WorldSpawnSettings settings = worldSettings.get(worldName.toLowerCase());
        return settings != null && settings.isEnabled();
    }

    public WorldSpawnSettings getWorldSettings(String worldName) {
        if (worldName == null) return null;
        return worldSettings.get(worldName.toLowerCase());
    }

    public Map<String, WorldSpawnSettings> getAllWorldSettings() {
        return Collections.unmodifiableMap(worldSettings);
    }

    public boolean isDebug() { return debug; }
    public boolean isAsyncSearch() { return asyncSearch; }
    public int getMaxSearchAttempts() { return maxSearchAttempts; }
    public String getServerLanguage() { return serverLanguage != null ? serverLanguage : "zh_CN"; }
    public String getClientDefaultLanguage() { return clientDefaultLanguage != null ? clientDefaultLanguage : "zh_CN"; }
    public String getDefaultLanguage() { return getServerLanguage(); }
    public boolean isAutoLanguage() { return autoLanguage; }
    public boolean isFirstJoinEnabled() { return firstJoinEnabled; }
    public int getFirstJoinDelayTicks() { return firstJoinDelayTicks; }
    public boolean isTrackPerWorldPDC() { return trackPerWorldPDC; }
    public boolean isRespawnEnabled() { return respawnEnabled; }
    public boolean isOverrideBed() { return overrideBed; }
    public boolean isOverrideAnchor() { return overrideAnchor; }
    public boolean isRespectBypassPermission() { return respectBypassPermission; }
    public boolean isCacheEnabled() { return cacheEnabled; }
    public int getCacheTargetSize() { return cacheTargetSize; }
    public int getCacheRefillIntervalSeconds() { return cacheRefillIntervalSeconds; }
    public int getMaxCacheAgeSeconds() { return maxCacheAgeSeconds; }
    public boolean isProtectionEnabled() { return protectionEnabled; }
    public int getInvulnerableSeconds() { return invulnerableSeconds; }
    public List<String> getPotionEffects() { return potionEffects; }
    public boolean isSoundEnabled() { return soundEnabled; }
    public String getSoundName() { return soundName; }
    public float getSoundVolume() { return soundVolume; }
    public float getSoundPitch() { return soundPitch; }
    public boolean isParticleEnabled() { return particleEnabled; }
    public String getParticleType() { return particleType; }
    public int getParticleCount() { return particleCount; }
    public double getParticleSpeed() { return particleSpeed; }
    public boolean isTitleEnabled() { return titleEnabled; }
    public int getTitleFadeIn() { return titleFadeIn; }
    public int getTitleStay() { return titleStay; }
    public int getTitleFadeOut() { return titleFadeOut; }
    public boolean isActionbarEnabled() { return actionbarEnabled; }
    public boolean isClientChatEnabled() { return clientChatEnabled; }
    public boolean isClientLocationCardEnabled() { return clientLocationCardEnabled; }
    public boolean isClientProtectionMsgEnabled() { return clientProtectionMsgEnabled; }
    public boolean isClientFallbackWarningEnabled() { return clientFallbackWarningEnabled; }
    public boolean isServerLogFirstJoin() { return serverLogFirstJoin; }
    public boolean isServerLogRespawn() { return serverLogRespawn; }
    public boolean isEnableSafeSpawnCheck() { return enableSafeSpawnCheck; }
    public Set<Material> getUnsafeFloorMaterials() { return unsafeFloorMaterials; }
    public Set<String> getBiomeBlacklist() { return biomeBlacklist; }
}
