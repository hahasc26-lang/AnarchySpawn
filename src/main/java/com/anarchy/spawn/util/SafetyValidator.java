package com.anarchy.spawn.util;

import com.anarchy.spawn.AnarchySpawn;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 玩家出生点/传送点安全性验证工具类
 * <p>
 * 设计原则：
 * 1. 全版本通用：向下兼容至 1.12.2 旧材质，原生支持 1.13+ 扁平化材质与 1.20+ 幽匿/试炼结构。
 * 2. 隔离防护：所有对 1.13+ (Tag, BlockData, Waterlogged) 及 1.14+ (Material.isAir) API 的调用均采用隔离防护，杜绝 NoSuchMethodError / NoClassDefFoundError。
 * 3. 极速判断：利用 EnumSet 位向量做预热计算，将高频字符串模糊匹配转化为纳秒级的 O(1) 查找。
 * 4. 全身位防御：覆盖脚底、站立容纳空间与周围侧面灼烧威胁。
 */
public final class SafetyValidator {

    private SafetyValidator() {}

    /** 【脚底危险方块缓存】 */
    private static final Set<Material> DANGEROUS_FLOOR_MATERIALS = EnumSet.noneOf(Material.class);

    /** 【身体/头部空间危险方块缓存】 */
    private static final Set<Material> UNSAFE_PASSABLE_MATERIALS = EnumSet.noneOf(Material.class);

    /** 【邻近侧面危险方块缓存】 */
    private static final Set<Material> DANGEROUS_SURROUNDING_MATERIALS = EnumSet.noneOf(Material.class);

    private static final BlockFace[] HORIZONTAL_FACES = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    static {
        // 脚底不可落脚的危险关键字列表（仅包含对玩家生命与环境有直接破坏/剧烈减速陷阱的方块）
        List<String> floorKeywords = List.of(
            // 致命伤害与危险（仙人掌、熔岩块、营火、凋零玫瑰、滴水石、爆炸物、锚、试炼宝库、幽匿尖啸体/感测体、苍白花园喧嚣心/眼花/重核/树脂）
            "MAGMA", "CAMPFIRE", "WITHER", "CACTUS", "POINTED_DRIPSTONE", "TNT", "RESPAWN_ANCHOR",
            "CREAKING_HEART", "EYE_BLOSSOM", "HEAVY_CORE", "TRIAL_SPAWNER", "VAULT", "RESIN_CLUMP", "RESIN_BLOCK",
            "SCULK_SHRIEKER", "SCULK_SENSOR",
            // 踩踏塌陷、瞬间掉落陷阱
            "BIG_DRIPLEAF", "SCAFFOLDING", "PALE_HANGING_MOSS",
            // 极细、异形、站不稳的表面与细小摆件
            "BARS", "PANE", "CHAIN", "LANTERN", "LIGHTNING_ROD", "END_PORTAL_FRAME", "CONDUIT", "BELL",
            // 触发器与传送门陷阱（避免卡传送门/红石陷阱）
            "PRESSURE_PLATE", "TRIPWIRE", "PORTAL"
        );

        // 玩家站立空间内不可存在的穿透物关键字列表（包含现代与 1.12.2 旧版材质关键字）
        List<String> passableKeywords = List.of(
            // 液体与窒息
            "LAVA", "WATER", "POWDER_SNOW", "BUBBLE_COLUMN", "KELP", "SEAGRASS",
            // 伤害与困人
            "FIRE", "BERRY", "WEB", "CAMPFIRE", "WITHER", "POINTED_DRIPSTONE", "PORTAL", "EYE_BLOSSOM", "PALE_HANGING_MOSS",
            // 易产生窒息或失足的开关/攀爬物
            "TRAPDOOR", "DOOR", "GATE", "LADDER", "VINE"
        );

        for (Material mat : Material.values()) {
            if (!isBlockMaterial(mat)) continue;
            String name = mat.name();

            // 避免 FIRE 误伤火珊瑚块（FIRE_CORAL_BLOCK 是安全纯装饰方块）
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

                // 3. 匹配周边危险热源与刺伤威胁（包含普通火、灵魂火、流动与静态岩浆、点燃营火、仙人掌、凋零等）
                if (name.contains("LAVA") || name.contains("FIRE") || name.contains("CAMPFIRE")
                        || name.contains("CACTUS") || name.contains("WITHER")) {
                    DANGEROUS_SURROUNDING_MATERIALS.add(mat);
                }
            }
        }

        // 兼容补齐栅栏（FENCES）与石墙（WALLS）
        populateFenceAndWallTagsSafely(DANGEROUS_FLOOR_MATERIALS);
    }

    private static boolean isBlockMaterial(Material mat) {
        if (mat == null) return false;
        try {
            return mat.isBlock();
        } catch (NoSuchMethodError | Exception e) {
            // 1.12.2 fallback where Material#isBlock() does not exist
            return mat.isSolid() || mat.name().contains("AIR") || mat.name().contains("WATER") || mat.name().contains("LAVA");
        }
    }

    private static boolean isAirMaterial(Material mat) {
        if (mat == null) return false;
        try {
            return mat.isAir();
        } catch (NoSuchMethodError | Exception e) {
            // 1.12.2 & 1.13 fallback where Material#isAir() does not exist
            return mat == Material.AIR || mat.name().endsWith("AIR");
        }
    }

    @SuppressWarnings("unchecked")
    private static void populateFenceAndWallTagsSafely(Set<Material> floorSet) {
        try {
            Class<?> tagClass = Class.forName("org.bukkit.Tag");
            Object fences = tagClass.getField("FENCES").get(null);
            if (fences instanceof Tag) {
                floorSet.addAll(((Tag<Material>) fences).getValues());
            }
            Object walls = tagClass.getField("WALLS").get(null);
            if (walls instanceof Tag) {
                floorSet.addAll(((Tag<Material>) walls).getValues());
            }
        } catch (Throwable ignored) {
            // 1.12.2 fallback where org.bukkit.Tag does not exist
            for (Material mat : Material.values()) {
                String name = mat.name();
                if (name.contains("FENCE") || name.contains("WALL")) {
                    floorSet.add(mat);
                }
            }
        }
    }

    public static boolean isSafeFloor(AnarchySpawn plugin, Block ground) {
        if (ground == null) return false;
        Material type = ground.getType();

        if (!type.isSolid()) return false;

        if (plugin.getConfigManager().getUnsafeFloorMaterials().contains(type)) {
            return false;
        }

        if (plugin.getConfigManager().isEnableSafeSpawnCheck()) {
            return !DANGEROUS_FLOOR_MATERIALS.contains(type);
        }

        return true;
    }

    public static boolean isPassable(Block block) {
        if (block == null) return false;
        Material type = block.getType();

        // 空气直接放行（跨版本安全 isAir）
        if (isAirMaterial(type)) return true;

        // 针对 1.13+ 含水方块特性：隔离调用防止 1.12.2 抛出 NoClassDefFoundError
        if (isWaterloggedBlock(block)) {
            return false;
        }

        boolean passable;
        try {
            passable = block.isPassable();
        } catch (NoSuchMethodError | Exception e) {
            passable = !type.isSolid() && !type.isOccluding();
        }

        if (passable) {
            return !UNSAFE_PASSABLE_MATERIALS.contains(type);
        }

        return false;
    }

    private static boolean isWaterloggedBlock(Block block) {
        try {
            Class<?> wlClass = Class.forName("org.bukkit.block.data.Waterlogged");
            Object data = block.getBlockData();
            if (wlClass.isInstance(data)) {
                Method m = wlClass.getMethod("isWaterlogged");
                return Boolean.TRUE.equals(m.invoke(data));
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean isSafeBiome(AnarchySpawn plugin, Biome biome) {
        if (biome == null) return true;
        String biomeName = biome.name();

        for (String blacklisted : plugin.getConfigManager().getBiomeBlacklist()) {
            if (blacklisted == null || blacklisted.trim().isEmpty()) {
                continue;
            }
            if (biomeName.equalsIgnoreCase(blacklisted) || biomeName.contains(blacklisted.toUpperCase())) {
                return false;
            }
        }
        return true;
    }

    public static boolean isSafeSurroundings(Block feet) {
        if (feet == null) return false;

        Block head = feet.getRelative(BlockFace.UP);
        for (BlockFace face : HORIZONTAL_FACES) {
            Material feetNeighbor = feet.getRelative(face).getType();
            if (DANGEROUS_SURROUNDING_MATERIALS.contains(feetNeighbor)) {
                return false;
            }

            Material headNeighbor = head.getRelative(face).getType();
            if (DANGEROUS_SURROUNDING_MATERIALS.contains(headNeighbor)) {
                return false;
            }
        }
        return true;
    }
}
