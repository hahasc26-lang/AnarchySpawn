package com.anarchy.spawn;

import com.anarchy.spawn.command.AnarchySpawnCommand;
import com.anarchy.spawn.command.AnarchySpawnTabCompleter;
import com.anarchy.spawn.config.ConfigManager;
import com.anarchy.spawn.config.MessageManager;
import com.anarchy.spawn.hook.EssentialsHook;
import com.anarchy.spawn.hook.PlaceholderAPIHook;
import com.anarchy.spawn.listener.PlayerDeathListener;
import com.anarchy.spawn.listener.PlayerJoinListener;
import com.anarchy.spawn.listener.PlayerQuitListener;
import com.anarchy.spawn.listener.PlayerRespawnListener;
import com.anarchy.spawn.listener.PlayerWorldChangeListener;
import com.anarchy.spawn.service.DeathHotspotManager;
import com.anarchy.spawn.service.LocationCachePool;
import com.anarchy.spawn.service.SafeLocationFinder;
import com.anarchy.spawn.service.TeleportService;
import com.anarchy.spawn.util.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.logging.Level;

/**
 * AnarchySpawn - 高性能安全随机出生与复活系统主类
 * <p>
 * 主要特性与架构设计：
 * <ul>
 * <li>跨平台调度：同时兼容 Bukkit/Spigot/Paper 全局主线程与 Folia 分区分片线程模型 (Regionized
 * Multi-Threading)。</li>
 * <li>异步预生成缓冲池：通过 {@link LocationCachePool} 后台寻找安全点，消除传送时现场检索造成的瞬时卡顿。</li>
 * <li>反围剿与热点规避：通过 {@link DeathHotspotManager} 监测频繁阵亡坐标，避免玩家连续复活到埋伏点。</li>
 * <li>软依赖解耦：动态挂载 PlaceholderAPI 与 EssentialsX，未安装时自适应跳过，不引发
 * ClassNotFoundException。</li>
 * </ul>
 *
 * @author AnarchyDev
 */
public final class AnarchySpawn extends JavaPlugin {

    /**
     * 单例实例引用，便于静态访问主类上下文
     */
    private static AnarchySpawn instance;

    // ==========================================
    // 配置与文本管理模块
    // ==========================================

    /**
     * 基础配置文件 (config.yml) 加载与管理服务
     */
    private ConfigManager configManager;

    /**
     * 多语言提示信息与自定义消息解析管理服务
     */
    private MessageManager messageManager;

    // ==========================================
    // 核心业务与算法服务
    // ==========================================

    /**
     * 安全坐标检索器（碰撞体积、方块属性、流体及危险方块过滤）
     */
    private SafeLocationFinder locationFinder;

    /**
     * 异步坐标预缓存池，用于维持可用出生点队列并降低主线程计算压力
     */
    private LocationCachePool cachePool;

    /**
     * 实体传送、跨区块异步预加载调度以及落地短暂无敌保护服务
     */
    private TeleportService teleportService;

    /**
     * 死亡高危热点管理服务，记录玩家阵亡坐标并动态生成排斥半径
     */
    private DeathHotspotManager deathHotspotManager;

    // ==========================================
    // 第三方插件软依赖状态标识
    // ==========================================

    /**
     * PlaceholderAPI 是否成功注册并可用
     */
    private boolean papiHooked = false;

    /**
     * EssentialsX 是否成功挂载（用于读取重生点接管与绕过权限）
     */
    private boolean essentialsHooked = false;

    /**
     * 插件加载与启用入口
     * <p>
     * 负责初始化环境检测、加载配置、构建服务管线、注册事件与指令。
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onEnable() {
        // 绑定全局单例实例
        instance = this;
        long startTime = System.currentTimeMillis();

        // 打印启动 Banner 与当前服务端的调度器运行模式
        getLogger().info("=========================================");
        getLogger().info("  AnarchySpawn v" + getDescription().getVersion() + " Starting...");
        getLogger().info("  Scheduler Mode: "
                + (FoliaScheduler.isFolia() ? "Folia (Regionized Scheduler)" : "Paper/Spigot (Classic Scheduler)"));
        getLogger().info("=========================================");

        // 1. 初始化文件配置系统
        this.configManager = new ConfigManager(this);
        this.messageManager = new MessageManager(this);
        this.configManager.loadConfig();
        this.messageManager.loadMessages();

        // 2. 初始化核心逻辑服务（注意：存在依赖顺序）
        this.deathHotspotManager = new DeathHotspotManager(this);
        this.locationFinder = new SafeLocationFinder(this);
        this.cachePool = new LocationCachePool(this);
        this.teleportService = new TeleportService(this);

        // 3. 启动后台位置缓存池工作线程/调度任务
        this.cachePool.startScheduler();

        // 4. 注册 Bukkit 事件监听器
        registerListeners();

        // 5. 注册指令执行器与 Tab 补全器
        registerCommands();

        // 6. 探测并连接第三方软依赖（PAPI、EssentialsX 等）
        registerHooks();

        // 统计启动耗时
        long timeTaken = System.currentTimeMillis() - startTime;
        getLogger().info("AnarchySpawn successfully enabled in " + timeTaken + "ms!");
    }

    /**
     * 插件卸载与关闭入口
     * <p>
     * 负责有序释放线程池资源、清空内存缓存、取消进行中的保护任务，防止热重载或停服时发生内存泄漏。
     */
    @Override
    public void onDisable() {
        getLogger().info("Disabling AnarchySpawn...");

        // 终止位置缓冲池的异步线程与定时任务，排空等待队列
        if (cachePool != null) {
            cachePool.shutdown();
        }

        // 取消所有尚未过期的传送落地无敌状态与保护倒计时任务
        if (teleportService != null) {
            teleportService.cancelAllProtectionTasks();
        }

        // 清除内存中记录的所有死亡热点坐标与排斥区
        if (deathHotspotManager != null) {
            deathHotspotManager.clearAllHotspots();
        }

        // 若挂载了 PAPI，注销本插件注册的自定义变量拓展
        if (papiHooked) {
            PlaceholderAPIHook.unregisterHook();
        }

        getLogger().info("AnarchySpawn has been cleanly disabled.");
        // 置空单例引用，协助 GC 回收主类加载的类加载器资源
        instance = null;
    }

    /**
     * 注册所有玩家生命周期与传送相关的事件监听器
     */
    private void registerListeners() {
        PluginManager pm = Bukkit.getPluginManager();
        // 进服判定与初次出生逻辑
        pm.registerEvents(new PlayerJoinListener(this), this);
        // 玩家死亡复活点拦截与计算
        pm.registerEvents(new PlayerRespawnListener(this), this);
        // 跨世界传送安全校验与边界调整
        pm.registerEvents(new PlayerWorldChangeListener(this), this);
        // 玩家死亡事件监听（用于记录死亡热点坐标）
        pm.registerEvents(new PlayerDeathListener(this), this);
        // 玩家离线清理（移除非法缓存与保护计时器）
        pm.registerEvents(new PlayerQuitListener(this), this);
    }

    /**
     * 注册根指令及补全器
     */
    private void registerCommands() {
        PluginCommand cmd = getCommand("anarchyspawn");
        if (cmd != null) {
            AnarchySpawnCommand executor = new AnarchySpawnCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(new AnarchySpawnTabCompleter(this));
        } else {
            // plugin.yml 缺失命令声明时输出严重错误提示
            getLogger().log(Level.SEVERE, "Failed to register /anarchyspawn command from plugin.yml!");
        }
    }

    /**
     * 检查并加载可选的软依赖插件
     */
    private void registerHooks() {
        PluginManager pm = Bukkit.getPluginManager();

        // PlaceholderAPI 软依赖挂载
        if (pm.isPluginEnabled("PlaceholderAPI")) {
            papiHooked = PlaceholderAPIHook.registerHook(this);
            if (papiHooked) {
                getLogger().info("Hooked into PlaceholderAPI successfully!");
            }
        } else {
            getLogger().info("PlaceholderAPI not found, skipping hook.");
        }

        // EssentialsX 软依赖挂载（用于兼容 /spawn 权限或重生点分流）
        if (pm.isPluginEnabled("Essentials")) {
            essentialsHooked = EssentialsHook.init(this);
            if (essentialsHooked) {
                getLogger().info("Detected EssentialsX - spawn bypass compatibility ready.");
            }
        } else {
            getLogger().info("EssentialsX not found, skipping hook.");
        }
    }

    /**
     * 热重载插件配置、语言文件并重启缓存调度器。
     * <p>
     * 通常由控制台或拥有管理员权限的玩家执行 {@code /anarchyspawn reload} 触发。
     */
    public void reloadPlugin() {
        // 重载基础配置参数
        configManager.loadConfig();
        // 重载本地化语言提示
        messageManager.loadMessages();
        // 按照新配置重启缓冲池调度（例如改变了搜索半径或缓存容量）
        cachePool.restartScheduler();
        // 重置所有历史死亡热点
        if (deathHotspotManager != null) {
            deathHotspotManager.clearAllHotspots();
        }
        getLogger().info("AnarchySpawn configuration and cache pool reloaded.");
    }

    // ==========================================
    // Getter 与静态访问方法
    // ==========================================

    /**
     * 获取当前插件单例
     *
     * @return {@link AnarchySpawn} 实例
     * @throws NullPointerException 若插件尚未初始化或已被禁用注销
     */
    public static AnarchySpawn getInstance() {
        return Objects.requireNonNull(instance, "AnarchySpawn instance is not initialized yet!");
    }

    /**
     * 获取配置管理器
     *
     * @return {@link ConfigManager}
     */
    public ConfigManager getConfigManager() {
        return configManager;
    }

    /**
     * 获取消息提示管理器
     *
     * @return {@link MessageManager}
     */
    public MessageManager getMessageManager() {
        return messageManager;
    }

    /**
     * 获取安全点定位服务
     *
     * @return {@link SafeLocationFinder}
     */
    public SafeLocationFinder getLocationFinder() {
        return locationFinder;
    }

    /**
     * 获取坐标异步缓存池服务
     *
     * @return {@link LocationCachePool}
     */
    public LocationCachePool getCachePool() {
        return cachePool;
    }

    /**
     * 获取传送及无敌保护调度服务
     *
     * @return {@link TeleportService}
     */
    public TeleportService getTeleportService() {
        return teleportService;
    }

    /**
     * 获取死亡热点分析管理器
     *
     * @return {@link DeathHotspotManager}
     */
    public DeathHotspotManager getDeathHotspotManager() {
        return deathHotspotManager;
    }

    /**
     * 查询 PlaceholderAPI 钩子是否激活
     *
     * @return 如果成功加载则为 true，否则为 false
     */
    public boolean isPapiHooked() {
        return papiHooked;
    }

    /**
     * 查询 EssentialsX 钩子是否激活
     *
     * @return 如果成功加载则为 true，否则为 false
     */
    public boolean isEssentialsHooked() {
        return essentialsHooked;
    }
}