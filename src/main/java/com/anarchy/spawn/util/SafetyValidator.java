package com.anarchy.spawn.util;

import com.anarchy.spawn.AnarchySpawn;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 玩家出生点/传送点安全性验证工具类
 * <p>
 * 设计原则：
 * 1. 极速判断：利用 EnumSet 位向量在类加载阶段做预热计算，将高频字符串模糊匹配转化为纳秒级的 O(1) 查找。
 * 2. 精准防误判：排除珊瑚块等含有 "FIRE" 等敏感字眼但实际上安全的方块。
 * 3. 全身位防御：不仅检测脚下和脚部周围，还覆盖头部空间与头部邻近可能灼伤玩家的火源/岩浆。
 */
public final class SafetyValidator {

    // 工具类禁止实例化
    private SafetyValidator() {}

    /**
     * 【脚底危险方块缓存】
     * 玩家脚踩上去会受到伤害、减速下陷、失足跌落或产生恶性交互的方块集合。
     */
    private static final Set<Material> DANGEROUS_FLOOR_MATERIALS = EnumSet.noneOf(Material.class);

    /**
     * 【身体/头部空间危险方块缓存】
     * 虽可通过碰撞检测（isPassable == true），但会使玩家窒息、淹溺、烧伤或减速被困的方块（如水、岩浆、蜘蛛网等）。
     */
    private static final Set<Material> UNSAFE_PASSABLE_MATERIALS = EnumSet.noneOf(Material.class);

    /**
     * 【邻近侧面危险方块缓存】
     * 当位于玩家身体周围（脚部、头部的前后左右）一格时，会引燃玩家或造成灼烧伤害的方块（如岩浆、明火、营火等）。
     */
    private static final Set<Material> DANGEROUS_SURROUNDING_MATERIALS = EnumSet.noneOf(Material.class);

    /**
     * 仅检测东南西北四个水平面方向，避免遍历多余的斜角和上下方向
     */
    private static final BlockFace[] HORIZONTAL_FACES = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    /*
     * 静态初始化块：只在服务器加载此类时执行一次。
     * 遍历服务器全部 Material 并根据关键词归类装入 EnumSet，避免在运行时反复遍历字符串。
     */
    static {
        // 脚底不可落脚的关键字列表
        List<String> floorKeywords = List.of(
            // 弹跳/黏滞/减速等不稳定方块
            "SLIME", "HONEY_BLOCK", "SOUL_SAND", "MUD",
            // 伤害与危险
            "MAGMA", "CAMPFIRE", "WITHER", "CACTUS", "POINTED_DRIPSTONE",
            // 踩踏会塌陷或掉落
            "BIG_DRIPLEAF", "SCAFFOLDING", "ANVIL",
            // 极细、异形、站不稳的表面
            "BARS", "PANE", "CHAIN", "LANTERN", "LIGHTNING_ROD", "END_PORTAL_FRAME", "DRAGON_EGG",
            // 交互与容器（避免卡箱子/红石）
            "SHULKER_BOX", "PRESSURE_PLATE", "TRIPWIRE", "BEDROCK", "PORTAL"
        );

        // 玩家站立空间内不可存在的穿透物关键字列表
        List<String> passableKeywords = List.of(
            // 液体与窒息
            "LAVA", "WATER", "POWDER_SNOW", "BUBBLE_COLUMN", "KELP", "SEAGRASS",
            // 伤害与困人
            "FIRE", "BERRY", "WEB", "CAMPFIRE", "WITHER", "POINTED_DRIPSTONE", "PORTAL",
            // 易产生窒息或失足的开关/攀爬物
            "TRAPDOOR", "DOOR", "GATE", "LADDER", "VINE"
        );

        for (Material mat : Material.values()) {
            // 过滤掉非方块物品（如苹果、钻石剑等），减少无效检查
            if (!mat.isBlock()) continue;
            String name = mat.name();

            // 特殊防护：避免 FIRE 关键词误伤火珊瑚块（FIRE_CORAL_BLOCK 是完全安全的纯装饰方块）
            boolean isCoral = name.contains("CORAL");

            if (!isCoral) {
                // 1. 匹配脚底危险方块
                for (String kw : floorKeywords) {
                    if (name.contains(kw)) {
                        DANGEROUS_FLOOR_MATERIALS.add(mat);
                        break;
                    }
                }

                // 2. 匹配身体/头部不可进入的穿透方块
                for (String kw : passableKeywords) {
                    if (name.contains(kw)) {
                        UNSAFE_PASSABLE_MATERIALS.add(mat);
                        break;
                    }
                }
            }

            // 3. 匹配周边危险热源（包含普通火、灵魂火、流动与静态岩浆、点燃的营火等）
            if (name.contains("LAVA") || name.contains("FIRE") || name.contains("CAMPFIRE")) {
                DANGEROUS_SURROUNDING_MATERIALS.add(mat);
            }
        }

        // 使用 Bukkit 原生 Tag 补齐栅栏（FENCES）与石墙（WALLS）
        // 这两类方块高度为 1.5 格，玩家站上去会有特殊的碰撞上浮导致窒息卡墙，且容易失足
        try {
            DANGEROUS_FLOOR_MATERIALS.addAll(Tag.FENCES.getValues());
            DANGEROUS_FLOOR_MATERIALS.addAll(Tag.WALLS.getValues());
        } catch (Throwable ignored) {
            // 针对极旧版本可能缺少特定 Tag 的兼容处理
        }
    }

    /**
     * 验证脚底方块（支撑面）是否安全、平整且可稳定站立
     *
     * @param plugin 插件主类实例，用于获取配置
     * @param ground 玩家脚下直接踩着的那个方块（即 feet.getRelative(BlockFace.DOWN)）
     * @return true 表示脚下安全；false 表示不能作为落脚点
     */
    public static boolean isSafeFloor(AnarchySpawn plugin, Block ground) {
        if (ground == null) return false;
        Material type = ground.getType();

        // 必须为可站立的实心完整方块（自动剔除空气、草丛、花朵、火等非实心方块）
        if (!type.isSolid()) return false;

        // 优先检查配置文件中用户自定义的危险地板黑名单
        if (plugin.getConfigManager().getUnsafeFloorMaterials().contains(type)) {
            return false;
        }

        // 若开启了安全生成检测，使用 O(1) 效率的位向量检测系统预设的危险列表
        if (plugin.getConfigManager().isEnableSafeSpawnCheck()) {
            return !DANGEROUS_FLOOR_MATERIALS.contains(type);
        }

        return true;
    }

    /**
     * 验证该方块空间是否允许玩家身体或头部穿过/容纳（不被卡住、不窒息、不受伤害）
     * <p>
     * 通常在生成点算法中需要分别对【脚部位置 block】和【头部位置 block.getRelative(UP)】各调用一次。
     *
     * @param block 目标容纳空间所在的方块
     * @return true 表示该空间通透且安全；false 表示有碰撞体积阻挡或存在伤害性元素
     */
    public static boolean isPassable(Block block) {
        if (block == null) return false;
        Material type = block.getType();

        // 空气直接放行（绝大多数安全传送点都是普通空气或洞穴空气）
        if (type.isAir()) return true;

        // 针对 1.13+ 含水方块特性：即使楼梯、半砖可以通过或留有空隙，含水也可能导致淹溺，直接判定不通过
        try {
            BlockData data = block.getBlockData();
            if (data instanceof Waterlogged && ((Waterlogged) data).isWaterlogged()) {
                return false;
            }
        } catch (Throwable ignored) {}

        // 尝试调用现代服务端的 isPassable 碰撞测试（判断玩家碰撞箱能否进入）
        boolean passable;
        try {
            passable = block.isPassable();
        } catch (NoSuchMethodError | Exception e) {
            // 兼容低版本：非实体方块且非完全遮挡光照的完整方块
            passable = !type.isSolid() && !type.isOccluding();
        }

        // 即使碰撞体积可穿过，还必须确保它不是蜘蛛网、火、水、岩浆等伤害/陷阱材质
        if (passable) {
            return !UNSAFE_PASSABLE_MATERIALS.contains(type);
        }

        return false;
    }

    /**
     * 验证该坐标所在的生物群系是否在黑名单中（如排除深海、地狱荒地、末地高原等）
     *
     * @param plugin 插件主类实例，用于获取配置
     * @param biome  待检测的群系
     * @return true 表示群系安全；false 表示在黑名单中不可选
     */
    public static boolean isSafeBiome(AnarchySpawn plugin, Biome biome) {
        if (biome == null) return true;
        String biomeName = biome.name();

        // 遍历配置文件中配置的群系黑名单关键字
        for (String blacklisted : plugin.getConfigManager().getBiomeBlacklist()) {
            if (biomeName.equalsIgnoreCase(blacklisted) || biomeName.contains(blacklisted.toUpperCase())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 验证玩家身体周围（脚部和头部的水平 4 周）是否存在致命威胁
     * <p>
     * 防止传送后虽然脚踩石头、站位是空气，但身体紧挨着岩浆流或明火，一瞬间被引燃。
     *
     * @param feet 玩家脚部所在的空气方块位置
     * @return true 表示周围环境安全；false 表示旁边存在火源、岩浆或危险营火
     */
    public static boolean isSafeSurroundings(Block feet) {
        if (feet == null) return false;

        // 获取玩家头部对应的方块位置
        Block head = feet.getRelative(BlockFace.UP);

        // 环绕检测东西南北 4 个方向
        for (BlockFace face : HORIZONTAL_FACES) {
            // 1. 脚部水平相邻检测
            Material feetNeighbor = feet.getRelative(face).getType();
            if (DANGEROUS_SURROUNDING_MATERIALS.contains(feetNeighbor)) {
                return false;
            }

            // 2. 头部水平相邻检测（防岩浆瀑布或从高处流下的火）
            Material headNeighbor = head.getRelative(face).getType();
            if (DANGEROUS_SURROUNDING_MATERIALS.contains(headNeighbor)) {
                return false;
            }
        }
        return true;
    }
}