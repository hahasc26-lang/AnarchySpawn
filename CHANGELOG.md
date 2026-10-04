# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

> 中文版请见 [CHANGELOG_Chinese.md](CHANGELOG_Chinese.md).

## [1.0.3] - 2026-10-04

### Added
- Add `safety-filter.enable-safe-spawn-check` (default: true) to toggle the built-in unsafe-block fallback check.
- Add `ConfigManager.enableSafeSpawnCheck` field and getter; user-defined `unsafe-floor-materials` always applies.
- Add hard-coded unsafe floor list in `SafetyValidator`: lava, fire, magma, cactus, powder snow, sweet berry bush, bedrock, slime block, wither rose, tripwire, pressure plates, scaffolding, honey block, pointed dripstone, anvil, trial spawner, vault, sculk shrieker, lightning rod, fences/walls/bars/panes/chains/lanterns, end portal frame, dragon egg, shulker box.

## [1.0.2] - 2026-10-03

### Added

* **Native Folia Server Compatibility (Single-JAR Dual Compatibility)**:
  * Added the `FoliaScheduler` unified scheduler abstraction layer, which reflectively probes `Bukkit.getRegionizedScheduler()` to auto-detect the Folia runtime; all scheduling calls across the project are now funneled through this layer with zero `if-else` branching in business code;
  * Provides five semantic scheduling entry points: `runForPlayer` / `runForPlayerLater` (player's owning region thread), `runForRegion` (world's owning region thread), `runTimerGlobal` (global repeating task), and `runAsync` (pure background task);
  * On non-Folia environments (Paper / Purpur / Spigot / CraftBukkit) it automatically falls back to the original classic scheduler path, behaving exactly like 1.0.1 with zero regression risk;
  * The startup log now reports the runtime mode (`Folia (Regionized Scheduler)` or `Paper/Spigot (Classic Scheduler)`) so operators can confirm the active scheduling mode.

### Changed

* **Full migration of scheduler calls to `FoliaScheduler`** (15 call sites in total):
  * `PlayerJoinListener`: first-join delayed teleport now uses the player region thread scheduler;
  * `PlayerRespawnListener`: post-respawn effects, fallback random teleport, and cache pool refill (4 call sites) migrated;
  * `PlayerWorldChangeListener`: cross-world first-join delayed task migrated;
  * `TeleportService`: cached-point teleport, async search callback, post-respawn effects, and spawn protection countdown (5 call sites) migrated, and the ineffective `Bukkit.isPrimaryThread()` check inside `ensureMainThread()` removed;
  * `LocationCachePool`: background cache refill timer migrated to the global region scheduler;
  * `SafeLocationFinder`: safe-column evaluation after async chunk load migrated to the world region thread scheduler;
  * `AnarchySpawnCommand`: `/as tp`, `/as setcenter`, and `/as test` (3 call sites) migrated.
* **`PaperCompatibility` fallback path refactor**:
  * Removed the ineffective `Bukkit.isPrimaryThread()` checks from the `getChunkAtAsync` and `teleportPlayerAsync` fallback branches;
  * Chunk loading fallback now uniformly uses `FoliaScheduler.runForRegion`, and teleport fallback uniformly uses `FoliaScheduler.runForPlayer`, satisfying both Folia thread confinement and the Spigot/Paper main-thread requirement.
* **Thread-safety refactor of `TeleportService.cancelAllProtectionTasks()`**: on plugin disable, `setInvulnerable(false)` for online players is now dispatched to each player's owning region thread, eliminating cross-thread access violations.

### Fixed

* Fixed `UnsupportedOperationException` and thread confinement violations on Folia caused by using the classic Bukkit scheduler (`runTask` / `runTaskLater` / `runTaskAsynchronously` / `runTaskTimerAsynchronously`);
* Fixed abnormal chunk loading and teleport fallback paths on Folia caused by the ineffective `Bukkit.isPrimaryThread()` semantics.
* **Folia scheduler reflection signature fix (critical)**:
  * Corrected the erroneous Folia scheduler API signature assumption in the `FoliaScheduler` static probe block. The previous implementation probed `RegionScheduler#runTaskTimer(Plugin, Runnable, long, long)`, which does not exist on Folia's `RegionScheduler` (global repeating tasks belong to `GlobalRegionScheduler`), causing a `NoSuchMethodException` that disabled the entire Folia detection and fell back to the classic scheduler unsupported on Folia, resulting in runtime crashes;
  * Now split into four schedulers resolved independently via reflection: `EntityScheduler` (player region thread), `RegionScheduler` (world region thread), `GlobalRegionScheduler` (global repeating tasks), and `AsyncScheduler` (pure background tasks);
  * Each reflective handle is resolved in its own try/catch, so a single missing method no longer disables the whole Folia detection; Folia is only assumed when `RegionScheduler` resolves successfully.
* **`runAsync()` argument fix**: the previous implementation incorrectly invoked `RegionScheduler#runTask(Plugin, World, Runnable)` while omitting the `World` argument, then fell back to `runTaskAsynchronously` which is unsupported on Folia. Now uses `AsyncScheduler#runNow(Plugin, Consumer)`.
* **`runTimerGlobal()` semantic fix**: the previous implementation used `RegionScheduler` for global repeating tasks; now uses `GlobalRegionScheduler#runAtFixedRate(Plugin, Runnable, long, long)`.
* **`runForPlayer()` / `runForPlayerLater()` signature fix**: now use `EntityScheduler#run(Plugin, Runnable, Runnable)` and `runDelayed(Plugin, Runnable, Runnable, long)`.
* **`runForRegion()` signature fix**: now uses `RegionScheduler#run(Plugin, World, int, int, Runnable)`.
* **`LocationCachePool` thread-confinement fix**: `refillAllWorlds()` previously iterated worlds and read world data directly on the global scheduler thread, violating Folia's thread confinement. Each world's cache refill is now dispatched to its owning region thread via `FoliaScheduler.runForRegion()` (new `refillWorld()` method).
* Corrected the Maven compiler plugin comment in `pom.xml` from "Java 17 support" to "Java 21 support" to match the actual `java.version=21`.
* Added an `api-version` explanatory comment in `plugin.yml` clarifying the compile baseline and runtime compatibility range.
* **`FoliaScheduler` safe-degradation fix**: when a reflective Folia handle failed to resolve, the methods previously fell back to the classic Bukkit scheduler, which throws `UnsupportedOperationException` on Folia. Every entry point now degrades to another Folia scheduler (`runForRegion` → global, `runForPlayer` → region, `runForPlayerLater` → global delayed, `runTimerGlobal` → async, `runAsync` → global) and never touches the classic scheduler on a Folia runtime.
* **`GlobalRegionScheduler` one-shot support**: added reflective resolution of `run(Plugin, Runnable)` and `runDelayed(Plugin, Runnable, long)` so one-shot global tasks (used by the safe-degradation paths) can be scheduled; `runTimerGlobal` now distinguishes repeating (`period > 0`) from one-shot (`period <= 0`) tasks.
* **`LocationCachePool` refill-guard fix**: the `isRefilling` guard was released before the per-world region tasks actually ran, allowing overlapping refill rounds to overfill the cache queues. The guard now covers the whole dispatch window (released on the next tick on Folia), and the per-world in-flight counters throttle concurrent searches.
* **`TeleportService` stale-cache fix**: a cached location whose world is no longer loaded is now discarded before teleporting, preventing a failed `teleportAsync` on an unloaded world.
* **`PlayerRespawnListener` stale-cache fix**: a cached respawn point whose world is unloaded is now discarded, avoiding a synchronous chunk load on the main thread during the respawn event.
* **`SafeLocationFinder` sync-path thread fix**: when `async-search` is disabled, the synchronous search is now dispatched through `FoliaScheduler.runForRegion` instead of running inline, so it is safe when invoked from an async thread (e.g. the cache pool) and respects Folia thread confinement.
* **`MessageManager` list `[noprefix]` fix**: the list branch of `sendMessage` now honours the `[noprefix]` marker, matching the single-line branch behaviour.

## [1.0.1] - 2026-10-01

### Added

* **Per-World First-Join Random Teleportation**:
  * `first-join` under `worlds.<world>` in `config.yml` now accepts an object with nested options while remaining backward-compatible with a plain boolean value;
  * Added `first-join.enabled` to control whether players are randomly teleported on their first join into that world;
  * Added `first-join.ignore-existing-playerdata` to skip the random teleport when an existing player data file is detected (such as `playerdata/*.dat`, `stats/*.json`, or prior play records), and to automatically backfill the world's PDC marker when a veteran player is detected, completely preventing existing players from being teleported by mistake;
  * Added the `PlayerDataUtils` module for high-performance verification of player physical data files and vanilla records;
  * Added the `PlayerWorldChangeListener` to listen for cross-world events, combined with the `PersistentDataContainer` (PDC) persistent marker, so a player is automatically and safely randomly teleported and protected the first time they enter a world with this option enabled;
  * Added the `{first_join}` status placeholder in the `/as info` admin command and the localization files to clearly display the first-join teleport status of each world.
* **Console Notification**:
  * Added friendly console notifications when optional soft-dependency hooks (PlaceholderAPI / EssentialsX) are skipped.

## [1.0.0] - 2026-09-30

### Added

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
* **Separated Server & Client Locales**:
  * Completely decoupled `server-language` (used for server console logs and command outputs) from `client-default-language` (used as fallback for unmatched players) within `config.yml`;
  * Automatically detects individual player Minecraft client locales (`player.getLocale()`), bundled with out-of-the-box support for Simplified Chinese (`zh_CN`), Traditional Chinese (`zh_TW`), and English (`en_US`);
  * Implemented hierarchical graceful fallback (Target Locale → Client Default Locale → Server Locale), completely eliminating missing key errors when new configuration nodes are added.
* **Configurable Client & Server Notifications**:
  * Added a dedicated `notifications` configuration block in `config.yml`, providing independent toggles for client chat announcements (`chat`), location report cards (`location-card`), spawn protection messages (`protection`), and fallback warnings (`fallback-warning`);
  * Added independent server console logging toggles (`console-log-first-join` and `console-log-respawn`) to allow operators to enable or completely silence console coordinates logging;
  * Optimized console de-duplication: prevented in-game administrator monitor broadcasts (`[Respawn-Monitor]`) from polluting the server console alongside standard logs (`[Respawn]`), and ensured console logs strictly resolve `{biome}` and `{direction}` using the configured `server-language`.
* **Multi-Line Location Report Cards**:
  * Extended `MessageManager` with native YAML List (`List<String>`) multi-line parsing, supporting `{prefix}` placeholder placement to preserve elegant bordered message aesthetics;
  * Added `spawn.first-join-location-info` and `spawn.respawn-location-info` report cards across all language bundles;
  * Built-in localized biome dictionaries (`biomes`) and 8-cardinal direction dictionaries (`directions`), fully supporting `{exact_x}`, `{exact_y}`, `{exact_z}`, `{biome}`, `{direction}`, and `{distance}` placeholders.
* **Minecraft 1.16.5 to 26.2+ Cross-Version Architecture**:
  * Engineered the `PaperCompatibility` reflection layer to automatically leverage Paper / Purpur native asynchronous chunk loading (`getChunkAtAsync`) and async teleportation (`teleportAsync`), with seamless synchronous fallback on Spigot / CraftBukkit;
  * Reflectively handles `World.getMinHeight()` across modern negative world limits (-64 to 320) and legacy Minecraft 1.16.5 heights (0 to 256);
  * Implemented an internal potion effect alias lookup table (`EFFECT_ALIASES`) to ensure backward compatibility with legacy 1.16 ~ 1.20 potion effect names.
* **Audiovisual & Anti-Instakill Protection**:
  * Configurable invulnerability duration (`Invulnerable` & `NoDamageTicks`) upon teleportation, complete with customizable protection start and expiration messages;
  * Configurable post-teleport potion effects (Resistance, Slow Falling, Fire Resistance, Saturation) to prevent instant fall damage or mob ambush;
  * Full audiovisual feedback support including arrival sound effects, particle clouds (Portal, etc.), centered on-screen Title / Subtitle, and hotbar Actionbar alerts.
* **PlaceholderAPI Integration**:
  * Registered the `%anarchyspawn%` expansion identifier, providing placeholders for player spawn status (`%anarchyspawn_spawned%`, `%anarchyspawn_spawned_<world>%`);
  * Exposes cached pool sizing (`%anarchyspawn_total_cached%`, `%anarchyspawn_cache_size_<world>%`) and world radius bounds (`%anarchyspawn_min_radius_<world>%`, `%anarchyspawn_max_radius_<world>%`).
* **Administrative CLI & Tab Completion**:
  * Comprehensive command suite with auto-completion: `/as reload` (hot reload config and locales), `/as tp` (randomly teleport target player), `/as setcenter` (dynamically update center origin), `/as info` (inspect world statuses & cache pools), `/as test` (benchmark location search latency), and `/as cache` (force-refill location cache pools).
