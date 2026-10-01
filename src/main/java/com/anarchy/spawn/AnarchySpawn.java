package com.anarchy.spawn;

import com.anarchy.spawn.command.AnarchySpawnCommand;
import com.anarchy.spawn.command.AnarchySpawnTabCompleter;
import com.anarchy.spawn.config.ConfigManager;
import com.anarchy.spawn.config.MessageManager;
import com.anarchy.spawn.hook.EssentialsHook;
import com.anarchy.spawn.hook.PlaceholderAPIHook;
import com.anarchy.spawn.listener.PlayerJoinListener;
import com.anarchy.spawn.listener.PlayerRespawnListener;
import com.anarchy.spawn.listener.PlayerWorldChangeListener;
import com.anarchy.spawn.service.LocationCachePool;
import com.anarchy.spawn.service.SafeLocationFinder;
import com.anarchy.spawn.service.TeleportService;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.logging.Level;

/**
 * AnarchySpawn - High-Performance Safe Random Spawn &amp; Respawn System
 * Compatible with Spigot &amp; Paper 1.16.5 - 1.21.x
 *
 * @author AnarchyDev
 */
public final class AnarchySpawn extends JavaPlugin {

    private static AnarchySpawn instance;

    // Managers &amp; Services
    private ConfigManager configManager;
    private MessageManager messageManager;
    private SafeLocationFinder locationFinder;
    private LocationCachePool cachePool;
    private TeleportService teleportService;

    // Soft Hooks
    private boolean papiHooked = false;
    private boolean essentialsHooked = false;

    // NamespacedKeys for PersistentDataContainer
    private NamespacedKey firstJoinKey;

    @Override
    @SuppressWarnings("deprecation")
    public void onEnable() {
        instance = this;
        long startTime = System.currentTimeMillis();

        getLogger().info("=========================================");
        getLogger().info("  AnarchySpawn v" + getDescription().getVersion() + " Starting...");
        getLogger().info("  Designed for Anarchy Servers");
        getLogger().info("=========================================");

        // Initialize Keys
        this.firstJoinKey = new NamespacedKey(this, "has_random_spawned");

        // Initialize Configurations &amp; Managers
        this.configManager = new ConfigManager(this);
        this.messageManager = new MessageManager(this);
        this.configManager.loadConfig();
        this.messageManager.loadMessages();

        // Initialize Services
        this.locationFinder = new SafeLocationFinder(this);
        this.cachePool = new LocationCachePool(this);
        this.teleportService = new TeleportService(this);

        // Start Background Cache Pool Worker
        this.cachePool.startScheduler();

        // Register Listeners
        registerListeners();

        // Register Commands
        registerCommands();

        // Register Soft Hooks
        registerHooks();

        long timeTaken = System.currentTimeMillis() - startTime;
        getLogger().info("AnarchySpawn successfully enabled in " + timeTaken + "ms!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling AnarchySpawn...");

        // Shutdown cache worker &amp; clear queues
        if (cachePool != null) {
            cachePool.shutdown();
        }

        // Cancel all pending protection tasks and reset invulnerability
        if (teleportService != null) {
            teleportService.cancelAllProtectionTasks();
        }

        // Unregister PAPI hook if registered
        if (papiHooked) {
            PlaceholderAPIHook.unregisterHook();
        }

        getLogger().info("AnarchySpawn has been cleanly disabled.");
        instance = null;
    }

    private void registerListeners() {
        PluginManager pm = Bukkit.getPluginManager();
        pm.registerEvents(new PlayerJoinListener(this), this);
        pm.registerEvents(new PlayerRespawnListener(this), this);
        pm.registerEvents(new PlayerWorldChangeListener(this), this);
    }

    private void registerCommands() {
        PluginCommand cmd = getCommand("anarchyspawn");
        if (cmd != null) {
            AnarchySpawnCommand executor = new AnarchySpawnCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(new AnarchySpawnTabCompleter(this));
        } else {
            getLogger().log(Level.SEVERE, "Failed to register /anarchyspawn command from plugin.yml!");
        }
    }

    private void registerHooks() {
        PluginManager pm = Bukkit.getPluginManager();

        // PlaceholderAPI Soft Hook
        if (pm.isPluginEnabled("PlaceholderAPI")) {
            papiHooked = PlaceholderAPIHook.registerHook(this);
            if (papiHooked) {
                getLogger().info("Hooked into PlaceholderAPI successfully!");
            }
        }

        // EssentialsX Soft Hook
        if (pm.isPluginEnabled("Essentials")) {
            essentialsHooked = EssentialsHook.init(this);
            if (essentialsHooked) {
                getLogger().info("Detected EssentialsX - spawn bypass compatibility ready.");
            }
        }
    }

    /**
     * Reloads configuration, messages, and restarts cache scheduler.
     */
    public void reloadPlugin() {
        configManager.loadConfig();
        messageManager.loadMessages();
        cachePool.restartScheduler();
        getLogger().info("AnarchySpawn configuration and cache pool reloaded.");
    }

    // Getters
    public static AnarchySpawn getInstance() {
        return Objects.requireNonNull(instance, "AnarchySpawn instance is not initialized yet!");
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public SafeLocationFinder getLocationFinder() {
        return locationFinder;
    }

    public LocationCachePool getCachePool() {
        return cachePool;
    }

    public TeleportService getTeleportService() {
        return teleportService;
    }

    public NamespacedKey getFirstJoinKey() {
        return firstJoinKey;
    }

    public boolean isPapiHooked() {
        return papiHooked;
    }

    public boolean isEssentialsHooked() {
        return essentialsHooked;
    }
}
