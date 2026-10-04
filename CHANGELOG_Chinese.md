# 更新日志

本文件记录本项目所有重要变更。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
本项目版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

> English version: [CHANGELOG.md](CHANGELOG.md).

## [1.0.3] - 2026-10-04

### 新增
- 新增配置项 `safety-filter.enable-safe-spawn-check`（默认 true），控制是否启用内置危险方块保底检查。
- `ConfigManager` 新增 `enableSafeSpawnCheck` 字段与 getter；用户自定义的 `unsafe-floor-materials` 不受开关影响，始终生效。
- `SafetyValidator` 内置硬编码危险方块列表：岩浆、火、岩浆块、仙人掌、细雪、甜浆果丛、基岩、粘液块、凋零玫瑰、绊线、压力板、脚手架、蜂蜜块、尖石、铁砧、试炼刷怪笼、宝库、潜声感测器、避雷针、栅栏/墙/玻璃板/铁栏杆/锁链/灯笼、末地传送门框、龙蛋、潜影盒。

## [1.0.2] - 2026-10-03

### 新增

* **Folia 服务端原生兼容（单 JAR 双兼容）**：
  * 新增 `FoliaScheduler` 统一调度器抽象层，通过反射探测 `Bukkit.getRegionizedScheduler()` 自动识别 Folia 运行环境，全项目调度调用统一收敛至该抽象层，业务代码零 `if-else` 分支；
  * 提供 `runForPlayer` / `runForPlayerLater`（玩家所属区域线程）、`runForRegion`（世界所属区域线程）、`runTimerGlobal`（全局定时任务）、`runAsync`（纯后台任务）五类语义化调度入口；
  * 在非 Folia 环境（Paper / Purpur / Spigot / CraftBukkit）下自动回退至原有经典调度器路径，行为与 1.0.1 完全一致，零回归风险；
  * 启动日志新增运行模式提示（`Folia (Regionized Scheduler)` 或 `Paper/Spigot (Classic Scheduler)`），便于运维确认当前调度模式。

### 变更

* **调度器调用全面迁移至 `FoliaScheduler`**（共 15 处）：
  * `PlayerJoinListener`：首次加入延迟传送改用玩家区域线程调度；
  * `PlayerRespawnListener`：重生后效果施加、兜底随机传送、缓存池补充共 4 处调用迁移；
  * `PlayerWorldChangeListener`：跨世界首次传送延迟任务迁移；
  * `TeleportService`：缓存点传送、异步搜索回传、重生后效果、出生保护倒计时共 5 处调用迁移，并移除 `ensureMainThread()` 中失效的 `Bukkit.isPrimaryThread()` 判断；
  * `LocationCachePool`：后台缓存补充定时任务迁移至全局区域调度；
  * `SafeLocationFinder`：异步区块加载完成后的安全列评估迁移至世界区域线程调度；
  * `AnarchySpawnCommand`：`/as tp`、`/as setcenter`、`/as test` 共 3 处回传调度迁移。
* **`PaperCompatibility` 回退路径重构**：
  * 移除 `getChunkAtAsync` 与 `teleportPlayerAsync` 回退分支中失效的 `Bukkit.isPrimaryThread()` 判断；
  * 区块加载回退统一走 `FoliaScheduler.runForRegion`，传送回退统一走 `FoliaScheduler.runForPlayer`，同时满足 Folia 线程封闭约束与 Spigot/Paper 主线程要求。
* **`TeleportService.cancelAllProtectionTasks()` 线程安全改造**：插件禁用时对在线玩家的 `setInvulnerable(false)` 操作改为调度至各玩家所属区域线程执行，消除跨线程访问违规。

### 修复

* 修复在 Folia 服务端上因使用经典 Bukkit 调度器（`runTask` / `runTaskLater` / `runTaskAsynchronously` / `runTaskTimerAsynchronously`）而导致的 `UnsupportedOperationException` 与线程封闭（Thread Confinement）违规异常；
* 修复在 Folia 服务端上因 `Bukkit.isPrimaryThread()` 语义失效而导致的区块加载与传送回退路径异常。
* **Folia 调度器反射签名修正（关键修复）**：
  * 修正 `FoliaScheduler` 静态探测块中对 Folia 调度器 API 的错误签名假设。原实现将 `RegionScheduler#runTaskTimer(Plugin, Runnable, long, long)` 作为探测方法，但 Folia 的 `RegionScheduler` 并不存在该全局定时重载（全局定时任务属于 `GlobalRegionScheduler`），导致 `NoSuchMethodException` 使整个 Folia 探测失效并回退至 Folia 不支持的经典调度器，运行时崩溃；
  * 现按 Folia 官方 API 拆分为四类调度器并分别独立反射解析：`EntityScheduler`（玩家区域线程）、`RegionScheduler`（世界区域线程）、`GlobalRegionScheduler`（全局定时任务）、`AsyncScheduler`（纯后台任务）；
  * 每个反射句柄独立 try/catch 解析，单个方法缺失不再导致整体 Folia 探测失败；仅当 `RegionScheduler` 解析成功时才判定为 Folia 环境。
* **`runAsync()` 参数修正**：原实现错误调用 `RegionScheduler#runTask(Plugin, World, Runnable)` 却缺少 `World` 参数，异常后回退至 Folia 不支持的 `runTaskAsynchronously`。现改用 `AsyncScheduler#runNow(Plugin, Consumer)`。
* **`runTimerGlobal()` 语义修正**：原实现使用 `RegionScheduler` 执行全局定时任务，现改用 `GlobalRegionScheduler#runAtFixedRate(Plugin, Runnable, long, long)`。
* **`runForPlayer()` / `runForPlayerLater()` 签名修正**：改用 `EntityScheduler#run(Plugin, Runnable, Runnable)` 与 `runDelayed(Plugin, Runnable, Runnable, long)`。
* **`runForRegion()` 签名修正**：改用 `RegionScheduler#run(Plugin, World, int, int, Runnable)`。
* **`LocationCachePool` 线程归属修复**：`refillAllWorlds()` 原在全局调度线程直接遍历世界并读取世界数据，在 Folia 上违反线程封闭约束。现将每个世界的缓存补充逻辑经 `FoliaScheduler.runForRegion()` 调度至该世界所属区域线程执行（新增 `refillWorld()` 方法）。
* `pom.xml` 编译器插件注释由 "Java 17 support" 更正为 "Java 21 support"，与实际 `java.version=21` 保持一致。
* `plugin.yml` 补充 `api-version` 说明注释，明确编译基线与运行时兼容区间。
* **`FoliaScheduler` 安全降级修复**：当某个 Folia 反射句柄解析失败时，原实现会回退到经典 Bukkit 调度器，而该调度器在 Folia 上会抛出 `UnsupportedOperationException`。现所有入口均降级至其他 Folia 调度器（`runForRegion` → 全局、`runForPlayer` → 区域、`runForPlayerLater` → 全局延迟、`runTimerGlobal` → 异步、`runAsync` → 全局），在 Folia 运行时绝不触碰经典调度器。
* **`GlobalRegionScheduler` 一次性任务支持**：新增对 `run(Plugin, Runnable)` 与 `runDelayed(Plugin, Runnable, long)` 的反射解析，使安全降级路径所需的一次性全局任务可被调度；`runTimerGlobal` 现区分循环任务（`period > 0`）与一次性任务（`period <= 0`）。
* **`LocationCachePool` 补充守卫修复**：`isRefilling` 守卫在各国世界区域任务真正执行前即被释放，导致重复补充轮次使缓存队列溢出。现守卫覆盖整个派发窗口（Folia 上延迟一 tick 释放），并由各世界的在途计数器限制并发搜索。
* **`TeleportService` 过期缓存修复**：传送前会丢弃世界已卸载的缓存坐标，避免对未加载世界执行 `teleportAsync` 失败。
* **`PlayerRespawnListener` 过期缓存修复**：重生事件中会丢弃世界已卸载的缓存重生点，避免在主线程同步加载区块造成卡顿。
* **`SafeLocationFinder` 同步路径线程修复**：当 `async-search` 关闭时，同步搜索现经 `FoliaScheduler.runForRegion` 派发而非内联执行，从而在异步线程（如缓存池）调用时安全，并符合 Folia 线程封闭约束。
* **`MessageManager` 列表 `[noprefix]` 修复**：`sendMessage` 的列表分支现支持 `[noprefix]` 标记，与单行分支行为保持一致。

## [1.0.1] - 2026-10-01

### 新增

* **世界独立配置：初次进入世界时随机传送**：
  * 在 `config.yml` 的 `worlds.<world>` 配置项中将 `first-join` 支持为包含子配置的对象格式（同时向下兼容纯布尔值）；
  * 新增 `first-join.enabled`：控制初次进入该世界是否随机传送；
  * 新增 `first-join.ignore-existing-playerdata`：控制如果存在该玩家的数据文件（如 `playerdata/*.dat`、`stats/*.json` 或已有游玩记录）则不实行随机传送，并在检测到老玩家文件时自动补齐该世界的 PDC 标记，彻底杜绝老玩家被误传送；
  * 新增 `PlayerDataUtils` 模块，用于高性能校验玩家物理数据文件与原版记录；
  * 新增 `PlayerWorldChangeListener` 监听跨世界事件，配合 PDC（`PersistentDataContainer`）持久化标记，实现玩家初次进入某个启用了该选项的世界时自动执行安全随机传送及给予保护；
  * 在 `/as info` 管理员指令与多语言文件中新增 `{first_join}` 状态占位符，直观展示各世界初次传送启用情况。
* **终端提示**：
  * 新增未启用软依赖 hook（PlaceholderAPI / EssentialsX）时的终端友好跳过提示。

## [1.0.0] - 2026-09-30

### 新增

* **0ms 极速异步重生缓存池**：
  * 引入 `LocationCachePool` 后台多线程坐标预计算机制，常驻维护可配置容量的安全出生点队列，实现玩家死亡后 0ms 瞬间重生，彻底消除主线程因寻找安全区块造成的卡顿；
  * 提供定时自动补充、使用即刻异步补全与 `/as cache` 强制刷新指令，支持根据闲置时间自动回收过热过旧的缓存坐标；
  * 针对无预存安全点或突发高并发情况，设计平滑降级机制，优先将玩家就绪至世界备用重生点（`fallback-location`）并在后台安全投送。
* **等面积环形概率密度分布**：
  * 摒弃传统的简易极坐标均匀半径随机算法，引入等面积概率密度算法：$r = \sqrt{u \cdot (R_{max}^2 - R_{min}^2) + R_{min}^2}$；
  * 确保玩家在内外环区域的落点概率分布密度严格一致，杜绝玩家在外围稀疏、近中心点扎堆的现象。
* **三维全息安全判定引擎**：
  * 实现 `SafetyValidator` 多维度安全判定，严苛过滤岩浆、水流、火焰、灵魂火、仙人掌、甜浆果丛、粉雪、凋零玫瑰、营火、压力板、绊线及各类陷阱方块；
  * 深度适配 1.20.4+ / 1.21+ 现代试炼建筑方块，阻断试炼刷怪笼（`TRIAL_SPAWNER`）、宝库（`VAULT`）、幽匿感测体与幽匿尖啸体；
  * 自动拦截危险海洋与虚空生物群系黑名单，并在主世界环境下智能跳过无阳光直射的黑暗深层洞穴，避免玩家出生即面临困境。
* **防堵床与防连环击杀机制**：
  * 提供 `override-bed` 与 `override-anchor` 配置项，支持强制接管原版床与下界重生锚点，杜绝水晶堵床连环轰炸恶性循环；
  * 支持 `anarchyspawn.bypass` 豁免权限，允许特定管理人员保留原版床重生行为。
* **PDC 跨世界出生持久化追踪**：
  * 基于 Bukkit 原生 `PersistentDataContainer`（PDC）机制记录玩家在各世界的首次投送标记，无须绑定外部 MySQL/SQLite 数据库，杜绝连接泄露与 IO 阻塞风险。
* **服务端语言与客户端语言独立配置**：
  * 在 `config.yml` 中将 `server-language`（服务端后台日志与控制台提示）与 `client-default-language`（玩家默认回退语言）完全解耦分离；
  * 自动识别客户端语言环境（`player.getLocale()`），内置提供简体中文（`zh_CN`）、繁体中文（`zh_TW`）以及英文（`en_US`）三大开箱即用语言包；
  * 实现层级安全回退机制（目标语言 → 客户端默认语言 → 服务端语言），杜绝因新增字段未翻译而导致的控制台缺失报错。
* **玩家客户端提示与控制台日志独立开关**：
  * 在 `config.yml` 中新增 `notifications` 独立配置块，涵盖客户端聊天消息（`chat`）、详细卡片（`location-card`）、保护提示（`protection`）及备用点降级警告（`fallback-warning`）；
  * 新增服务端后台日志独立记录开关（`console-log-first-join`、`console-log-respawn`），服主可按需开启或彻底静默后台日志；
  * 优化后台日志去重：修复管理员游戏内监控通知（`[Respawn-Monitor]`）与控制台日志（`[Respawn]`）同时打印至后台的问题，并将控制台日志中的 `{biome}` 与 `{direction}` 严格绑定至服务端所设语言。
* **多行详细坐标报告卡片**：
  * 突破单行文本限制，`MessageManager` 原生支持 YAML 列表（List）多行解析，并提供 `{prefix}` 占位符避免多行列表边框被前缀干扰破坏；
  * 在 `zh_CN.yml`、`zh_TW.yml` 和 `en_US.yml` 中新增 `spawn.first-join-location-info` 与 `spawn.respawn-location-info` 多行排版卡片；
  * 内置生物群系本地化对照表（`biomes`）与八方位朝向词典（`directions`），支持 `{exact_x}`、`{exact_y}`、`{exact_z}`、`{biome}`、`{direction}`、`{distance}` 等全套占位符。
* **Minecraft 1.16.5 至 26.2+ 全版本兼容**：
  * 编写 `PaperCompatibility` 底层反射层，自动侦测 Paper / Purpur 原生异步区块加载（`getChunkAtAsync`）与异步传送（`teleportAsync`），纯 Spigot / CraftBukkit 环境自动平滑回退至主线程同步调度；
  * 反射兼容 `World.getMinHeight()`，自动适配 1.18+ 负高度区间（-64 至 320）与 1.16.5 传统高度（0 至 256）；
  * 完善药水效果类型别名映射表（`EFFECT_ALIASES`），向下兼容 1.16 ~ 1.20 传统药水名称。
* **全套出生视听与防秒杀增益**：
  * 支持自定义秒数出生无敌保护（Invulnerable & NoDamageTicks），并可在语言文件中配置开启与结束提醒；
  * 支持配置出生药水增益列表（抗性提升、缓慢下落、防火、饱和等），防止高空坠落伤与刷怪秒杀；
  * 支持传送音效播放、落地粒子生成（传送门粒子等）、屏幕中央 Title / Subtitle 标题以及 Actionbar 动作栏显示。
* **PlaceholderAPI 变量拓展**：
  * 注册 `%anarchyspawn%` 变量标识，支持获取玩家各世界出生状态（`%anarchyspawn_spawned%`、`%anarchyspawn_spawned_<world>%`）；
  * 支持获取实时世界预存池数量（`%anarchyspawn_total_cached%`、`%anarchyspawn_cache_size_<world>%`）与内外环半径范围（`%anarchyspawn_min_radius_<world>%`、`%anarchyspawn_max_radius_<world>%`）。
* **便捷管理指令与 Tab 补全**：
  * 提供 `/as reload`（配置与语言热重载）、`/as tp`（随机传送指定玩家）、`/as setcenter`（动态修改中心坐标）、`/as info`（各世界状态监视）、`/as test`（单次坐标搜索基准测速）与 `/as cache`（预存池填充）。
