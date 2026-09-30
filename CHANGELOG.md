# Changelog / 更新日志

所有版本的重要变更均记录于此。  
All notable changes to this project are documented here.

格式参考 [Keep a Changelog](https://keepachangelog.com/).  
Format based on [Keep a Changelog](https://keepachangelog.com/).

## [1.0.0] - 2026-09-30

### 核心特性与架构 / Core Features & Architecture

* **0ms 极速异步重生缓存池 (Zero-Lag Async Pre-Cache Pool)**:
  * 引入 `LocationCachePool` 后台多线程坐标预计算机制，常驻维护可配置容量的安全出生点队列，实现玩家死亡后 0ms 瞬间重生，彻底消除主线程因寻找安全区块造成的卡顿；
  * 提供定时自动补充、使用即刻异步补全与 `/as cache` 强制刷新指令，支持根据闲置时间自动回收过热过旧的缓存坐标；
  * 针对无预存安全点或突发高并发情况，设计平滑降级机制，优先将玩家就绪至世界备用重生点（`fallback-location`）并在后台安全投送。

* **等面积环形概率密度分布 (Equal-Area Annular Distribution)**:
  * 摒弃传统的简易极坐标均匀半径随机算法，引入等面积概率密度算法：$r = \sqrt{u \cdot (R_{max}^2 - R_{min}^2) + R_{min}^2}$；
  * 确保玩家在内外环区域的落点概率分布密度严格一致，杜绝玩家在外围稀疏、近中心点扎堆的现象。

* **三维全息安全判定引擎 (Holistic 3D Safety Validation)**:
  * 实现 `SafetyValidator` 多维度安全判定，严苛过滤岩浆、水流、火焰、灵魂火、仙人掌、甜浆果丛、粉雪、凋零玫瑰、营火、压力板、绊线及各类陷阱方块；
  * 深度适配 1.20.4+ / 1.21+ 现代试炼建筑方块，阻断试炼刷怪笼（`TRIAL_SPAWNER`）、宝库（`VAULT`）、幽匿感测体与幽匿尖啸体；
  * 自动拦截危险海洋与虚空生物群系黑名单，并在主世界环境下智能跳过无阳光直射的黑暗深层洞穴，避免玩家出生即面临困境。

* **防堵床与防连环击杀机制 (Spawn-Trap Prevention & Anti-Spawnkill)**:
  * 提供 `override-bed` 与 `override-anchor` 配置项，支持强制接管原版床与下界重生锚点，杜绝水晶堵床连环轰炸恶性循环；
  * 支持 `anarchyspawn.bypass` 豁免权限，允许特定管理人员保留原版床重生行为。

* **PDC 跨世界出生持久化追踪 (PDC Persistent Spawn History)**:
  * 基于 Bukkit 原生 `PersistentDataContainer`（PDC）机制记录玩家在各世界的首次投送标记，无须绑定外部 MySQL/SQLite 数据库，杜绝连接泄露与 IO 阻塞风险。

---

### 多语言与通知系统 / Localization & Notification System

* **服务端语言与客户端语言独立配置 (Separated Server & Client Locales)**:
  * 在 `config.yml` 中将 `server-language`（服务端后台日志与控制台提示）与 `client-default-language`（玩家默认回退语言）完全解耦分离；
  * 自动识别客户端语言环境（`player.getLocale()`），内置提供简体中文（`zh_CN`）、繁体中文（`zh_TW`）以及英文（`en_US`）三大开箱即用语言包；
  * 实现层级安全回退机制（目标语言 $\rightarrow$ 客户端默认语言 $\rightarrow$ 服务端语言），杜绝因新增字段未翻译而导致的控制台缺失报错。

* **玩家客户端提示与控制台日志独立开关 (Configurable Client & Server Notifications)**:
  * 在 `config.yml` 中新增 `notifications` 独立配置块，涵盖客户端聊天消息（`chat`）、详细卡片（`location-card`）、保护提示（`protection`）及备用点降级警告（`fallback-warning`）；
  * 新增服务端后台日志独立记录开关（`console-log-first-join`、`console-log-respawn`），服主可按需开启或彻底静默后台日志；
  * 优化后台日志去重：修复管理员游戏内监控通知（`[Respawn-Monitor]`）与控制台日志（`[Respawn]`）同时打印至后台的问题，并将控制台日志中的 `{biome}` 与 `{direction}` 严格绑定至服务端所设语言。

* **多行详细坐标报告卡片 (Multi-Line Location Report Cards)**:
  * 突破单行文本限制，`MessageManager` 原生支持 YAML 列表（List）多行解析，并提供 `{prefix}` 占位符避免多行列表边框被前缀干扰破坏；
  * 在 `zh_CN.yml`、`zh_TW.yml` 和 `en_US.yml` 中新增 `spawn.first-join-location-info` 与 `spawn.respawn-location-info` 多行排版卡片；
  * 内置生物群系本地化对照表（`biomes`）与八方位朝向词典（`directions`），支持 `{exact_x}`、`{exact_y}`、`{exact_z}`、`{biome}`、`{direction}`、`{distance}` 等全套占位符。

---

### 跨版本兼容与防御机制 / Compatibility & Protection

* **Minecraft 1.16.5 至 26.2+ 全版本兼容 (Cross-Version Architecture)**:
  * 编写 `PaperCompatibility` 底层反射层，自动侦测 Paper / Purpur 原生异步区块加载（`getChunkAtAsync`）与异步传送（`teleportAsync`），纯 Spigot / CraftBukkit 环境自动平滑回退至主线程同步调度；
  * 反射兼容 `World.getMinHeight()`，自动适配 1.18+ 负高度区间（-64 至 320）与 1.16.5 传统高度（0 至 256）；
  * 完善药水效果类型别名映射表（`EFFECT_ALIASES`），向下兼容 1.16 ~ 1.20 传统药水名称。

* **全套出生视听与防秒杀增益 (Audiovisual & Anti-Instakill Protection)**:
  * 支持自定义秒数出生无敌保护（Invulnerable & NoDamageTicks），并可在语言文件中配置开启与结束提醒；
  * 支持配置出生药水增益列表（抗性提升、缓慢下落、防火、饱和等），防止高空坠落伤与刷怪秒杀；
  * 支持传送音效播放、落地粒子生成（传送门粒子等）、屏幕中央 Title / Subtitle 标题以及 Actionbar 动作栏显示。

---

### 生态对接与运维指令 / Integrations & Commands

* **PlaceholderAPI 变量拓展 (PlaceholderAPI Integration)**:
  * 注册 `%anarchyspawn%` 变量标识，支持获取玩家各世界出生状态（`%anarchyspawn_spawned%`、`%anarchyspawn_spawned_<world>%`）；
  * 支持获取实时世界预存池数量（`%anarchyspawn_total_cached%`、`%anarchyspawn_cache_size_<world>%`）与内外环半径范围（`%anarchyspawn_min_radius_<world>%`、`%anarchyspawn_max_radius_<world>%`）。

* **便捷管理指令与 Tab 补全 (Administrative CLI & Auto-Completion)**:
  * 提供 `/as reload`（配置与语言热重载）、`/as tp`（随机传送指定玩家）、`/as setcenter`（动态修改中心坐标）、`/as info`（各世界状态监视）、`/as test`（单次坐标搜索基准测速）与 `/as cache`（预存池填充）。

---
---

### Core Features & Architecture (English)

* **Zero-Lag Async Pre-Cache Pool**:
  * Introduced the `LocationCachePool` multi-threaded coordinate pre-calculation engine, actively maintaining a configurable pool of verified safe spawn locations to guarantee 0ms instant respawn upon player death, eliminating main thread tick lag spikes caused by chunk loading;
  * Features scheduled async refilling, instant asynchronous top-off upon cache consumption, and `/as cache` manual refill command, with automated purge of stale coordinates exceeding the configured maximum cache age;
  * Integrated graceful fallback dispatching to the configured world fallback location (`fallback-location`) under cache starvation or burst concurrent joins.

* **Equal-Area Annular Distribution**:
  * Replaced naive linear polar coordinate distribution with an equal-area probability density algorithm: $r = \sqrt{u \cdot (R_{max}^2 - R_{min}^2) + R_{min}^2}$;
  * Guarantees uniform spatial distribution density across inner and outer ring boundaries, preventing players from clustering tightly around the center origin.

* **Holistic 3D Safety Validation**:
  * Built the comprehensive `SafetyValidator` engine to strictly filter out lava, flowing water, fire, soul fire, cacti, sweet berry bushes, powder snow, wither roses, campfires, pressure plates, tripwires, and deadly hazard blocks;
  * Fully adapted for modern 1.20.4+ / 1.21+ trial structure mechanics, safely detecting and blocking spawns on Trial Spawners (`TRIAL_SPAWNER`), Vaults (`VAULT`), Sculk Sensors, and Sculk Shriekers;
  * Automatically filters blacklisted ocean and void biomes, and skips pitch-black deep subterranean caverns in Overworld dimensions to prevent inescapable cave traps.

* **Spawn-Trap Prevention & Anti-Spawnkill**:
  * Provides `override-bed` and `override-anchor` configuration toggles to override vanilla bed and respawn anchor mechanics, preventing crystal bed-trapping and continuous spawn-kill cycles;
  * Supports permission `anarchyspawn.bypass` to allow privileged staff members to preserve their vanilla bed spawn points.

* **PDC Persistent Spawn History**:
  * Utilizes Bukkit's native `PersistentDataContainer` (PDC) to track per-world spawn history directly within player entity NBT data, eliminating external database dependencies, connection leaks, and IO bottlenecks.

---

### Localization & Notification System (English)

* **Separated Server & Client Locales**:
  * Completely decoupled `server-language` (used for server console logs and command outputs) from `client-default-language` (used as fallback for unmatched players) within `config.yml`;
  * Automatically detects individual player Minecraft client locales (`player.getLocale()`), bundled with out-of-the-box support for Simplified Chinese (`zh_CN`), Traditional Chinese (`zh_TW`), and English (`en_US`);
  * Implemented hierarchical graceful fallback (Target Locale $\rightarrow$ Client Default Locale $\rightarrow$ Server Locale), completely eliminating missing key errors when new configuration nodes are added.

* **Configurable Client & Server Notifications**:
  * Added a dedicated `notifications` configuration block in `config.yml`, providing independent toggles for client chat announcements (`chat`), location report cards (`location-card`), spawn protection messages (`protection`), and fallback warnings (`fallback-warning`);
  * Added independent server console logging toggles (`console-log-first-join` and `console-log-respawn`) to allow operators to enable or completely silence console coordinates logging;
  * Optimized console de-duplication: prevented in-game administrator monitor broadcasts (`[Respawn-Monitor]`) from polluting the server console alongside standard logs (`[Respawn]`), and ensured console logs strictly resolve `{biome}` and `{direction}` using the configured `server-language`.

* **Multi-Line Location Report Cards**:
  * Extended `MessageManager` with native YAML List (`List<String>`) multi-line parsing, supporting `{prefix}` placeholder placement to preserve elegant bordered message aesthetics;
  * Added `spawn.first-join-location-info` and `spawn.respawn-location-info` report cards across all language bundles;
  * Built-in localized biome dictionaries (`biomes`) and 8-cardinal direction dictionaries (`directions`), fully supporting `{exact_x}`, `{exact_y}`, `{exact_z}`, `{biome}`, `{direction}`, and `{distance}` placeholders.

---

### Compatibility & Protection (English)

* **Minecraft 1.16.5 to 26.2+ Cross-Version Architecture**:
  * Engineered the `PaperCompatibility` reflection layer to automatically leverage Paper / Purpur native asynchronous chunk loading (`getChunkAtAsync`) and async teleportation (`teleportAsync`), with seamless synchronous fallback on Spigot / CraftBukkit;
  * Reflectively handles `World.getMinHeight()` across modern negative world limits (-64 to 320) and legacy Minecraft 1.16.5 heights (0 to 256);
  * Implemented an internal potion effect alias lookup table (`EFFECT_ALIASES`) to ensure backward compatibility with legacy 1.16 ~ 1.20 potion effect names.

* **Audiovisual & Anti-Instakill Protection**:
  * Configurable invulnerability duration (`Invulnerable` & `NoDamageTicks`) upon teleportation, complete with customizable protection start and expiration messages;
  * Configurable post-teleport potion effects (Resistance, Slow Falling, Fire Resistance, Saturation) to prevent instant fall damage or mob ambush;
  * Full audiovisual feedback support including arrival sound effects, particle clouds (Portal, etc.), centered on-screen Title / Subtitle, and hotbar Actionbar alerts.

---

### Integrations & Commands (English)

* **PlaceholderAPI Integration**:
  * Registered the `%anarchyspawn%` expansion identifier, providing placeholders for player spawn status (`%anarchyspawn_spawned%`, `%anarchyspawn_spawned_<world>%`);
  * Exposes cached pool sizing (`%anarchyspawn_total_cached%`, `%anarchyspawn_cache_size_<world>%`) and world radius bounds (`%anarchyspawn_min_radius_<world>%`, `%anarchyspawn_max_radius_<world>%`).

* **Administrative CLI & Tab Completion**:
  * Comprehensive command suite with auto-completion: `/as reload` (hot reload config and locales), `/as tp` (randomly teleport target player), `/as setcenter` (dynamically update center origin), `/as info` (inspect world statuses & cache pools), `/as test` (benchmark location search latency), and `/as cache` (force-refill location cache pools).
