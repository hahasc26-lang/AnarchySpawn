<a id="english"></a>

# AnarchySpawn

[![Minecraft](https://img.shields.io/badge/Minecraft-1.16.5--26.2-brightgreen?style=flat-square)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-17%20%7C%2021-orange?style=flat-square&logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![Build](https://img.shields.io/badge/Build-Maven-blue?style=flat-square&logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![PlaceholderAPI](https://img.shields.io/badge/PlaceholderAPI-Supported-blueviolet?style=flat-square)](https://placeholderapi.com/)
[![License](https://img.shields.io/badge/License-MIT-yellow?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Paper%20%7C%20Purpur%20%7C%20Spigot%20%7C%20Bukkit-purple?style=flat-square)](https://papermc.io/)

<div align="center">

[中文](#chinese) | **English**

</div>

> A high-performance random wilderness spawn and safe respawn engine designed for **Anarchy** and competitive survival Minecraft servers.

---

## Supported Versions & Server Engines

<details open>
<summary><b>Click to expand / collapse Version Compatibility Matrix</b></summary>

<br>

| Minecraft Version | Compatibility Status | Compatibility Details |
| :--- | :--- | :--- |
| **1.20.x - 26.2+** | Full Support (Native) | Full support for modern height limits (-64 to 320), modern potions, and trial structure safety filters (Trial Spawners, Vaults, Sculk Shriekers). |
| **1.18.x - 1.19.x** | Full Support | Seamless support for caves & cliffs height limits and ancient city biome detection. |
| **1.16.5 - 1.17.x** | Full Support (Legacy) | Automatic fallback to height 0 base and legacy potion effect aliases (`DAMAGE_RESISTANCE`, `INCREASE_DAMAGE`, etc.). |

<br>

| Server Core / Software | Compatibility | Recommendation |
| :--- | :--- | :--- |
| **Paper / Purpur** | Native Async Support | Highly Recommended (utilizes asynchronous chunk loading & teleportation without TPS drops) |
| **Spigot / CraftBukkit** | Full Synchronous Support | Fully Supported (automatically switches to scheduled sync loading fallback) |
| **Pufferfish / Gale / Leaves** | Native Async Support | Fully Supported |

</details>

---

## Features

- **Broad Version Compatibility (1.16.5 - 26.2+)**: Built-in runtime reflection layer that safely adapts across legacy and modern Minecraft releases without NoSuchMethod crashes.
- **Standalone Multi-Language System & Client Locale Auto-Detection**: Language files are organized in a dedicated `languages/` directory (`en_US.yml`, `zh_CN.yml`, `zh_TW.yml`). Messages automatically adapt to each player's client language.
- **First-Join Safe Drop**: Delays teleportation upon first join or dimension entry to safely dispatch players into configured ring wilderness zones.
- **PDC Persistent Tracking**: Uses `PersistentDataContainer` (supported since 1.14+) to track spawn history per world without external database dependencies or memory leaks.
- **Zero-Delay Respawn Cache Pool**: Background async tasks pre-search and maintain a pool of safe locations (`LocationCachePool`) for instant respawn upon death.
- **Bed & Respawn Anchor Override**: Option to override vanilla beds and respawn anchors to prevent bed-trapping and spawn-kill cycles.
- **Equal-Area Annular Distribution**: Utilizes equal-area probability density sampling to ensure uniform player distribution across outer zones without clustering near the center.
- **Holistic 3D Safety Validation**: Rigorous filtering against lava, underwater, cacti, sweet berries, powdered snow, portals, bedrock roofs/voids, dangerous ocean biomes, trial structures, and world borders.
- **PaperMC Async Chunk Compatibility**: Native reflection support for Paper's asynchronous chunk loading with smooth fallback on Spigot, preserving 20 TPS.
- **Audiovisual & Anti-Instakill Protection**: Configurable invulnerability duration, potion effects (Resistance, Slow Falling, Fire Resistance), particle effects, sounds, titles, and actionbars.
- **Ecosystem Integration**: Built-in soft dependency support for PlaceholderAPI and EssentialsX.

---

## Build & Compilation

### Requirements
- JDK 17 or JDK 21
- Apache Maven 3.8+

### Compilation Command
Run the following command in the project root directory:
```bash
mvn clean package
```
The compiled jar artifact will be located at `target/AnarchySpawn-1.0.0.jar`.

---

## Installation & Usage

1. Copy the compiled `AnarchySpawn-1.0.0.jar` into your server's `plugins/` folder.
2. Start the server; default configuration and language files will be generated in `plugins/AnarchySpawn/` and `plugins/AnarchySpawn/languages/`.
3. Customize `config.yml` and language files in `languages/` according to your server requirements.
4. Execute `/as reload` in the console or in-game to apply configuration changes on the fly.

---

## Commands & Permissions

<details>
<summary><b>Click to expand / collapse Command List (Commands)</b></summary>

<br>

| Command | Aliases | Permission | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `/as reload` | `/aspawn reload` | `anarchyspawn.reload` | OP | Reloads configuration and language files |
| `/as tp [player] [world]` | `/randspawn tp` | `anarchyspawn.tp` | OP | Teleports a player to a safe random location in target world |
| `/as setcenter <world> <x> <z>` | `/as setcenter` | `anarchyspawn.setcenter` | OP | Dynamically updates the spawn center coordinates for a world |
| `/as info` | `/as status` | `anarchyspawn.info` | OP | Displays enabled world statuses and cache pool capacity |
| `/as test [world]` | `/as benchmark` | `anarchyspawn.test` | OP | Runs a single safe coordinate search benchmark with timing |
| `/as cache` | `/as refill` | `anarchyspawn.cache` | OP | Wakes up background async workers to refill the pre-cache pool |

</details>

<details>
<summary><b>Click to expand / collapse Permission List (Permissions)</b></summary>

<br>

| Permission Node | Default | Description |
| :--- | :--- | :--- |
| `anarchyspawn.use` | Everyone (`true`) | Grants access to random safe spawns on first join and death respawn |
| `anarchyspawn.bypass` | OP | Bypasses random respawn (preserves vanilla bed and respawn anchor) |
| `anarchyspawn.admin` | OP | Master administrator permission covering all subcommands |
| `anarchyspawn.reload` | OP | Allows execution of `/as reload` |
| `anarchyspawn.tp` | OP | Allows execution of `/as tp` |
| `anarchyspawn.setcenter` | OP | Allows execution of `/as setcenter` |
| `anarchyspawn.info` | OP | Allows execution of `/as info` |
| `anarchyspawn.test` | OP | Allows execution of `/as test` |
| `anarchyspawn.cache` | OP | Allows execution of `/as cache` |

</details>

---

## PlaceholderAPI Identifiers

<details>
<summary><b>Click to expand / collapse PlaceholderAPI List (Placeholders)</b></summary>

<br>

| Placeholder | Description | Return Type / Example |
| :--- | :--- | :--- |
| `%anarchyspawn_spawned%` | Checks if the current player has performed their initial spawn in the current world | `true` / `false` |
| `%anarchyspawn_cache_size_<world>%` | Gets the count of currently available safe locations in the async cache pool for the specified world | Integer (e.g., `10`) |
| `%anarchyspawn_min_radius_<world>%` | Gets the configured minimum inner radius for the specified world | Integer (e.g., `1000`) |
| `%anarchyspawn_max_radius_<world>%` | Gets the configured maximum outer radius for the specified world | Integer (e.g., `15000`) |

</details>

---
---

<a id="chinese"></a>

# AnarchySpawn

[![Minecraft](https://img.shields.io/badge/Minecraft-1.16.5--26.2-brightgreen?style=flat-square)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-17%20%7C%2021-orange?style=flat-square&logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![Build](https://img.shields.io/badge/Build-Maven-blue?style=flat-square&logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![PlaceholderAPI](https://img.shields.io/badge/PlaceholderAPI-Supported-blueviolet?style=flat-square)](https://placeholderapi.com/)
[![License](https://img.shields.io/badge/License-MIT-yellow?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Paper%20%7C%20Purpur%20%7C%20Spigot%20%7C%20Bukkit-purple?style=flat-square)](https://papermc.io/)

<div align="center">

**中文** | [English](#english)

</div>

> 专为**无规则（Anarchy）**与**高对抗生存**服务器设计的高性能随机出生与安全重生插件。

- **全版本原生兼容**：支持 Minecraft 1.16.5 至 26.2+（Paper / Purpur / Spigot / Bukkit）。
- **0ms 极速响应**：后台异步区块预判与安全坐标池缓存，彻底告别主线程卡顿。
- **服务端与客户端语言独立配置**：服务端语言与客户端默认语言支持分开配置，并可自动根据玩家客户端语言自适应。
- **生态无缝对接**：支持 PlaceholderAPI 变量体系、EssentialsX 与 PDC 持久化存储。

---

## 服务端与版本兼容支持

<details open>
<summary><b>点击展开 / 折叠版本兼容性详细对照表</b></summary>

<br>

| Minecraft 版本区间 | 兼容状态 | 版本适配说明 |
| :--- | :--- | :--- |
| **1.20.x - 26.2+** | 完美原生支持 | 适配深度负高度世界（-64 至 320）、试炼刷怪笼（Trial Spawner）、宝库（Vault）与幽匿系列方块安全阻断。 |
| **1.18.x - 1.19.x** | 完美原生支持 | 适配洞穴与山崖世界高度上限及深暗之域危险群系过滤。 |
| **1.16.5 - 1.17.x** | 完美向下兼容 | 底层反射拦截 `getMinHeight()`，自动回退至基岩 0 高度；药水、粒子与音效自动映射老版别名。 |

<br>

| 服务端核心类型 | 兼容性支持 | 部署建议 |
| :--- | :--- | :--- |
| **Paper / Purpur** | 原生异步区块与传送 | **强烈推荐**（充分发挥异步区块加载与异步传送优势，确保 20 TPS 零卡顿） |
| **Spigot / CraftBukkit** | 同步平滑回退支持 | **完美兼容**（自动侦测并回退至安全的主线程调度加载机制） |
| **Pufferfish / Gale / Leaves** | 原生异步区块与传送 | **完美兼容** |

</details>

---

## 核心特性

- **超宽跨版本兼容（1.16.5 - 26.2+）**：内置完善的底层反射兼容层与异常拦截，杜绝跨版本 API 调用抛出 `NoSuchMethodError` 或方块枚举不存在崩溃。
- **独立多语言文件夹与服务端/客户端语言独立分离**：所有语言文件集中存放在 `languages/` 独立目录中（包含 `zh_CN.yml`、`zh_TW.yml`、`en_US.yml`），服务端控制台与客户端默认语言支持分开配置，同时支持根据玩家客户端语言自动呈现对应提示。
- **首次加入随机投送**：新玩家首次进服或进入启用世界时，延迟安全投送至指定环形区间荒野。
- **PDC 持久化追踪**：基于 `PersistentDataContainer`（1.14+ 原生支持）记录出生历史，无需外部数据库，杜绝内存泄漏。
- **0ms 极速重生缓存池**：后台异步线程预先寻找并维持安全坐标池（LocationCachePool），死亡时瞬间调取。
- **床与重生锚强制覆盖**：可选覆写原版床和重生锚重生机制，防止玩家被堵床连续击杀。
- **等面积环形随机算法**：采用等面积概率密度采样，避免玩家在圆心区域扎堆。
- **三维全息安全判定**：严苛过滤岩浆、水下、仙人掌、甜浆果、粉雪、传送门、基岩、海洋与各类危险方块，受限于世界边界。
- **PaperMC 异步区块兼容**：内置反射自动适配 Paper 异步区块加载，纯 Spigot 环境平滑回退，保障 20 TPS。
- **完整视听与防秒杀增益**：支持无敌时间、药水效果（抗性提升、缓慢下落、防火等）、音效、粒子、Title 与 Actionbar。
- **生态无缝对接**：支持 PlaceholderAPI 与 EssentialsX 软依赖拓展。

---

## 编译与构建

### 前置要求
- JDK 17 或 JDK 21
- Apache Maven 3.8+

### 构建指令
在项目根目录下执行：
```bash
mvn clean package
```
构建产物位于 `target/AnarchySpawn-1.0.0.jar`。

---

## 安装与使用

1. 将编译生成的 `AnarchySpawn-1.0.0.jar` 放入服务器 `plugins/` 目录中。
2. 启动服务器，插件将自动在 `plugins/AnarchySpawn/` 目录下生成 `config.yml`，并在 `plugins/AnarchySpawn/languages/` 目录下生成多语言文件。
3. 按照需求编辑 `config.yml` 与 `languages/` 中的语言文件。
4. 在控制台或游戏中输入 `/as reload` 即刻热重载生效。

---

## 指令与权限

<details>
<summary><b>点击展开 / 折叠指令列表 (Commands)</b></summary>

<br>

| 指令 | 别名 | 权限节点 | 默认归属 | 功能描述 |
| :--- | :--- | :--- | :--- | :--- |
| `/as reload` | `/aspawn reload` | `anarchyspawn.reload` | OP | 重载配置文件与语言文件 |
| `/as tp [玩家] [世界]` | `/randspawn tp` | `anarchyspawn.tp` | OP | 将玩家随机传送至指定世界安全点 |
| `/as setcenter <世界> <x> <z>` | `/as setcenter` | `anarchyspawn.setcenter` | OP | 动态修改指定世界的随机生成中心坐标 |
| `/as info` | `/as status` | `anarchyspawn.info` | OP | 查看所有启用世界的运行状态与缓存池余量 |
| `/as test [世界]` | `/as benchmark` | `anarchyspawn.test` | OP | 运行单次安全坐标搜索算法基准测试与耗时统计 |
| `/as cache` | `/as refill` | `anarchyspawn.cache` | OP | 立即唤醒后台异步线程补满预缓存池 |

</details>

<details>
<summary><b>点击展开 / 折叠权限列表 (Permissions)</b></summary>

<br>

| 权限节点 | 默认归属 | 功能与作用说明 |
| :--- | :--- | :--- |
| `anarchyspawn.use` | 所有人 (`true`) | 允许玩家在首次加入服务器及死亡重生时享受随机安全投送 |
| `anarchyspawn.bypass` | OP | 绕过随机出生（优先保留原版床或重生锚机制） |
| `anarchyspawn.admin` | OP | 管理员总权限（包含所有子指令与管理操作权限） |
| `anarchyspawn.reload` | OP | 允许执行配置重载指令 `/as reload` |
| `anarchyspawn.tp` | OP | 允许执行安全随机传送指令 `/as tp` |
| `anarchyspawn.setcenter` | OP | 允许动态修改世界中心坐标 `/as setcenter` |
| `anarchyspawn.info` | OP | 允许查看插件世界状态与缓存池余量 `/as info` |
| `anarchyspawn.test` | OP | 允许执行算法性能基准测试 `/as test` |
| `anarchyspawn.cache` | OP | 允许强制补充预热缓存池 `/as cache` |

</details>

---

## PlaceholderAPI 变量

<details>
<summary><b>点击展开 / 折叠 PlaceholderAPI 变量列表 (Placeholders)</b></summary>

<br>

| 变量占位符 | 作用说明 | 返回值类型 / 示例 |
| :--- | :--- | :--- |
| `%anarchyspawn_spawned%` | 检测当前玩家是否已在所在世界完成过首次随机出生 | `true` / `false` |
| `%anarchyspawn_cache_size_<world>%` | 获取指定世界后台异步预热池中当前可用的安全点位数量 | 整数（例如 `10`） |
| `%anarchyspawn_min_radius_<world>%` | 获取指定世界配置的内环最小出生半径 | 整数（例如 `1000`） |
| `%anarchyspawn_max_radius_<world>%` | 获取指定世界配置的外环最大出生半径 | 整数（例如 `15000`） |

</details>
