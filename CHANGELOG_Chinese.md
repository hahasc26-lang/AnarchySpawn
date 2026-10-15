# 更新日志

本文件记录本项目所有重要变更。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
本项目版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

> English version: [CHANGELOG.md](CHANGELOG.md).

## [1.1.0] - 2026-10-15

### 新增
- **全面兼容 Minecraft 26.3+ (1.21.x)**：
  - 扩展 `PaperCompatibility` 底层反射适配层，实现对 26.3 / 1.21.x 最新 API、音效（`BLOCK_TRIAL_SPAWNER_SPAWN_MOB`、`ITEM_WIND_CHARGE_WIND_BURST`、`BLOCK_CREAKING_HEART_SPAWN` 等）与粒子效果（`PALE_OAK_LEAVES`、`CREAKING_CRUMB`、`TRIAL_SPAWNER_DETECTION`、`WIND_CHARGE` 等）的安全动态加载。
  - 在 `SafetyValidator` 中补全 26.3 最新方块（`CREAKING_HEART` 喧嚣心、`EYE_BLOSSOM` 眼花、`PALE_HANGING_MOSS` 苍白垂苔、`HEAVY_CORE` 重核、`RESIN_BLOCK` 树脂块）与生物群系（`PALE_GARDEN` 苍白花园）的安全过滤规则，在旧版本服务器（1.12.2 - 1.20.x）上自动降级与回退。
  - 更新插件元数据、README 说明文档与版本兼容对照表，明确对 Paper、Purpur、Folia、Spigot 服务端 `1.12.2 - 26.3+` 全版本区间的原生支持。
- **全版本向下兼容至 Minecraft 1.12.2**：
  - 在 `PDCUtils` 中实现双轨持久化引擎：在 1.14+ 上原生调用 `PersistentDataContainer` (PDC)，在 1.12.2 ~ 1.13.2 上无缝降级至轻量级本地文件存储 (`data/players.yml`)，保证全版本跨世界出生记录可用。
  - 增加 1.12.2 旧材质兼容 (`STATIONARY_LAVA`、`STATIONARY_WATER`、`BURNING_FURNACE`、`BED_BLOCK` 等) 并隔离 1.13+ 类引用 (`BlockData`、`Waterlogged`、`Tag`)，杜绝旧版 JVM 抛出 `NoClassDefFoundError`。
  - 针对 1.13 之前缺失 `Material.isBlock()` 与 `Material.isAir()` 的环境增加安全反射防御。
- **地表最高高度（HighestBlockY）直达穿透优化（性能提升 98%+）**：
  - 在 `SafeLocationFinder` 主世界搜索中集成 `world.getHighestBlockYAt(x, z)` 高度图穿透算法，候选列方块探测次数从 ~250 次直降至 1~3 次，安全坐标搜寻速度提升近百倍。
  - 在地表附近建立小窗口局部精确验证（地表 +6 至 -12 格），兼顾地表植被、树冠与微地形，保留下界与洞穴维度的受限垂直扫描。
- 新增 `DeathHotspotManager` 与 `PlayerDeathListener` 模块，用于动态记录和避让玩家近期死亡与战斗热点区域。
- `config.yml` 新增 `death-hotspots` 配置块，支持独立控制功能开关 (`enabled`)、封禁半径 (`radius`)、热点有效期 (`ttl-seconds`) 以及单世界最大热点存储量 (`max-hotspots-per-world`)。
- 在 `SafeLocationFinder` 中实现两阶段降级搜索机制：第一阶段优先避开死亡热点，若热点覆盖范围过大导致未找到点位，第二阶段自动忽略热点限制进行常规随机安全出生搜索，确保玩家始终能在荒野正常出生。
- 新增 `PlayerQuitListener` 与 `TeleportService.cancelPlayerProtection()`，在玩家断开连接时重置无敌状态并清理定时任务，彻底封堵强退卡永久无敌 (God-mode) 漏洞。

### 修复
- **Folia 区块级线程封闭调度支持（致命级）**：在 `FoliaScheduler` 中新增支持指定区块坐标的 `runForRegion(plugin, world, chunkX, chunkZ, task)` 重载。彻底修复此前 `SafeLocationFinder` 与 `PaperCompatibility.getChunkAtAsync` 在兜底时默认向 `(0, 0)` 区块派发任务的严重缺陷，杜绝在 Folia 下访问非 0,0 远离区块时触发的跨区域线程封闭异常（Cross-region thread confinement violation）。
- **备用点重生过渡期无敌保护**：在 `PlayerRespawnListener` 中为触发备用点兜底重生的玩家预先赋予 60-tick（3秒）临时伤害免疫（`noDamageTicks`），防止玩家在后台异步搜索安全点期间在危险的备用点（如岩浆、实体堆积或被围堵区域）被瞬间击杀。
- **PlaceholderAPI 热点变量与空指针安全防御**：新增 `%anarchyspawn_hotspots_<world>%` 活跃死亡热点数统计变量；为 `PlaceholderAPIHook` 中缓存池与热点管理器增加空安全校验，防止在相应模块未启用时抛出空指针异常。
- **PlayerDeathListener 生命周期防御**：在 `PlayerDeathListener` 中为玩家实体和 `DeathHotspotManager` 补充空安全校验，杜绝服务器重载或关服阶段触发监听产生的潜在异常。
- **缓存池 In-Flight 泄漏拦截**：在 `SafeLocationFinder` 的区域线程安全方块遍历中增加 `try-catch(Throwable)` 全局防护，确保即使发生异常也能回调 `CompletableFuture`，防止 `inFlightRequests` 计数器永久泄漏锁死缓存填充。
- **Folia 调度器反射签名修复（致命）**：修正了对不存在的 `Bukkit.getRegionizedScheduler()` 及 `scheduler.player()` 的错误探测；改用标准的 `Bukkit.getRegionScheduler()`、`Bukkit.getGlobalRegionScheduler()`、`Bukkit.getAsyncScheduler()`，并针对玩家实体从 `player.getScheduler()` 正确获取 `EntityScheduler`，恢复 Folia 原生运行兼容。
- **预缓存池死亡热点穿透修复**：修复了 `LocationCachePool.poll()` 在出队时未校验该点位是否在缓存后沦为近期死亡热点的问题，避免复活玩家掉入热点交火区。
- **重生时序与效果丢失修复**：将 `PlayerRespawnListener` 中赋予增益效果的调度时机由 0 延迟调整为 1-tick 延迟（`runForPlayerLater(..., 1L)`），防止因玩家实体在事件期间仍为 `isDead()` 而漏发药水、音效与 Title 提示。
- **生物群系黑名单空值防御**：修复了 `biome-blacklist` 中若存在空行或空字符串会导致 `contains("")` 误匹配所有群系导致搜寻失败的问题。
- **环形半径计算安全性强化**：在 `SafeLocationFinder` 中限制 `minRadius` 与 `maxRadius` 的数学极值，防止配置颠倒时开根号计算出 `NaN` 坐标。
- **重载清理热点缓存**：执行 `/as reload` 时同步清空 `DeathHotspotManager` 历史热点。
- **PlaceholderAPI 作者动态获取**：在 `PlaceholderAPIHook` 中改为从 `plugin.yml` 动态解析作者信息。
- **火珊瑚与泥砖误报修复**：修复了周边危险热源列表因缺少保护导致安全的 `FIRE_CORAL`（火珊瑚）被误判为致命火源的问题，并优化了 `MUD` 判定，使 `MUD_BRICKS`（泥砖）与 `PACKED_MUD`（泥坯）等正常建筑方块不再被误判为危险地面。
- **进服跨世界竞态拦截**：在 `PlayerJoinListener` 延迟任务执行时增加世界一致性比对，避免与登录插件/大厅跨世界传送发生指令与位置冲突。
- **TeleportService 变量缺失与保护会话安全（编译级修复）**：修复了 `activeProtectionTasks` 变量未声明导致源码无法编译的致命错误；引入 `activeProtectionSessions` 保护会话机制，彻底解决 Folia 下无 BukkitTask 时的取消防护与陈旧回调问题。
- **Folia 跨区域指令回显线程隔离修复**：修复了在执行 `/as tp` 与 `/as test` 时，由于在目标玩家/区域线程中直接向指令发送者（Console 或其他区域管理员）发送消息导致的 Folia 线程封闭异常，改为调度至发送者自身所属线程。
- **Folia 定时任务取消与缓存池生命周期保护**：在 `FoliaScheduler` 中实现 `runTimerGlobalTask` 与反射式 `cancelTask`，并在 `LocationCachePool` 中加入 `isRunning` 生命周期校验，彻底根除 Folia 环境下多次执行 `/as reload` 重复叠加后台定时任务的泄漏问题。
- **死亡热点降级搜索算力空转优化**：在搜索超时降级前增加活跃热点数校验，若当前世界不存在任何活跃热点，则直接使用备用点，避免在纯地形阻碍时白白多执行 35 次多余的区块搜索。
- **插件卸载内存清理**：在 `onDisable()` 中增加 `deathHotspotManager.clearAllHotspots()`，防止插件重载时残留对世界和坐标队列的强引用。
- **指令补全世界大小写保留**：修复了 Tab 补全世界名称时被强制转换为纯小写的问题，保留服主在配置文件中配置的原始大小写形式。

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
