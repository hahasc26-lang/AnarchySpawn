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
 * 2. 隔离防护：所有对 1.13+ (Tag, BlockData, Waterlogged) 及 1.14+ (Material.isAir) API
 * 的调用均采用隔离防护，杜绝 NoSuchMethodError / NoClassDefFoundError。
 * 3. 极速判断：利用 EnumSet 位向量做预热计算，将高频字符串模糊匹配转化为纳秒级的 O(1) 查找。
 * 4. 全身位防御：覆盖脚底、站立容纳空间与周围侧面灼烧威胁。
 */
public final class SafetyValidator {

    // 工具类私有构造器，防止意外实例化
    private SafetyValidator() {
    }

    /**
     * 【脚底危险方块缓存】
     * 存放踩踏会导致掉落、伤害、触发红石/陷阱或极细难以立足的方块集合
     */
    private static final Set<Material> DANGEROUS_FLOOR_MATERIALS = EnumSet.noneOf(Material.class);

    /**
     * 【身体/头部空间危险方块缓存】
     * 存放处于玩家站立空间内会导致窒息、迟缓、剧毒、溺水或传送卡死的穿透物方块集合
     */
    private static final Set<Material> UNSAFE_PASSABLE_MATERIALS = EnumSet.noneOf(Material.class);

    /**
     * 【邻近侧面危险方块缓存】
     * 存放水平相邻一格内会对玩家施加引燃、灼烧或尖刺伤害的威胁方块集合
     */
    private static final Set<Material> DANGEROUS_SURROUNDING_MATERIALS = EnumSet.noneOf(Material.class);

    /**
     * 水平四个主方向（东、南、西、北），用于快速检测身体周遭的环境危险
     */
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
                "PRESSURE_PLATE", "TRIPWIRE", "PORTAL");

        // 玩家站立空间内不可存在的穿透物关键字列表（包含现代与 1.12.2 旧版材质关键字）
        List<String> passableKeywords = List.of(
                // 液体与窒息
                "LAVA", "WATER", "POWDER_SNOW", "BUBBLE_COLUMN", "KELP", "SEAGRASS",
                // 伤害与困人
                "FIRE", "BERRY", "WEB", "CAMPFIRE", "WITHER", "POINTED_DRIPSTONE", "PORTAL", "EYE_BLOSSOM",
                "PALE_HANGING_MOSS",
                // 易产生窒息或失足的开关/攀爬物
                "TRAPDOOR", "DOOR", "GATE", "LADDER", "VINE");

        // 遍历当前运行环境下的所有枚举材质进行预分类匹配（类加载时单次计算，避免运行时频繁正则/字符串包含运算）
        for (Material mat : Material.values()) {
            // 过滤掉纯物品 Material（如 DIAMOND_SWORD），仅对可作为放置方块的材质进行碰撞和安全判定
            if (!isBlockMaterial(mat))
                continue;
            String name = mat.name();

            // 避免 FIRE 误伤火珊瑚块（FIRE_CORAL_BLOCK 是安全纯装饰方块）
            boolean isCoral = name.contains("CORAL");

            if (!isCoral) {
                // 1. 匹配脚底危险方块：若材质名命中关键字，收录至脚底黑名单
                for (String kw : floorKeywords) {
                    if (name.contains(kw)) {
                        DANGEROUS_FLOOR_MATERIALS.add(mat);
                        break;
                    }
                }

                // 2. 匹配身体/头部不可进入的穿透方块：避免传送进入导致溺死、烧死或卡蛛网
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

        // 兼容补齐栅栏（FENCES）与石墙（WALLS）：由于其高度为 1.5 格且顶部站立易滑落卡进方块，标记为不安全落脚点
        populateFenceAndWallTagsSafely(DANGEROUS_FLOOR_MATERIALS);
    }

    /**
     * 判断材质是否为方块（跨版本防护）
     *
     * @param mat 待检测材质
     * @return 若是有效方块返回 true，物品或无效材质返回 false
     */
    private static boolean isBlockMaterial(Material mat) {
        if (mat == null)
            return false;
        try {
            // 针对 1.13+ API：Material#isBlock() 原生方法存在，直接调用返回结果
            return mat.isBlock();
        } catch (NoSuchMethodError | Exception e) {
            // 针对 1.12.2 旧版本降级策略：此版本不存在 Material#isBlock()，通过固体属性或常规流体/空气名进行兼容判定
            return mat.isSolid() || mat.name().contains("AIR") || mat.name().contains("WATER")
                    || mat.name().contains("LAVA");
        }
    }

    /**
     * 判断材质是否为空气（跨版本防护）
     *
     * @param mat 待检测材质
     * @return 若为各类空气方块（常规空气、洞穴空气、虚空空气）返回 true
     */
    private static boolean isAirMaterial(Material mat) {
        if (mat == null)
            return false;
        try {
            // 针对 1.14+ API：Material#isAir() 可直接覆盖 AIR、CAVE_AIR 与 VOID_AIR
            return mat.isAir();
        } catch (NoSuchMethodError | Exception e) {
            // 针对 1.12.2 与 1.13 旧版本降级策略：这些版本不存在 Material#isAir()，回退至枚举比较与后缀检测
            return mat == Material.AIR || mat.name().endsWith("AIR");
        }
    }

    /**
     * 安全提取栅栏与石墙材质（跨版本隔离反射）
     * <p>
     * 1.13+ 引入了 {@link Tag} 机制，可通过内置标签一次性拿取所有变种；
     * 1.12.2 并不存在 {@code org.bukkit.Tag} 类，若直接引用会导致 {@link NoClassDefFoundError}。
     *
     * @param floorSet 目标落脚点黑名单集合
     */
    @SuppressWarnings("unchecked")
    private static void populateFenceAndWallTagsSafely(Set<Material> floorSet) {
        try {
            // 针对 1.13+ 标签机制：通过反射隔离探测 org.bukkit.Tag，避免 1.12.2 因类缺失触发 NoClassDefFoundError
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
            // 针对 1.12.2 旧版本降级策略：无 Tag 类时，降级采用枚举名称包含 "FENCE" 或 "WALL" 进行全量匹配补齐
            for (Material mat : Material.values()) {
                String name = mat.name();
                if (name.contains("FENCE") || name.contains("WALL")) {
                    floorSet.add(mat);
                }
            }
        }
    }

    /**
     * 验证方块是否为合格的落脚地面
     *
     * @param plugin 插件主类实例，用于获取配置选项
     * @param ground 玩家脚底支撑方块
     * @return 若地面坚实、无害且未被禁用则返回 true
     */
    public static boolean isSafeFloor(AnarchySpawn plugin, Block ground) {
        if (ground == null)
            return false;
        Material type = ground.getType();

        // 必须为实体碰撞方块（排除空气、纯流体等虚空下坠表面）
        if (!type.isSolid())
            return false;

        // 优先检查管理员自定义配置黑名单（最高优先级）
        if (plugin.getConfigManager().getUnsafeFloorMaterials().contains(type)) {
            return false;
        }

        // 开启了安全出生检测时，拦截落脚会受损或掉落的危险方块（如岩浆块、营火、细栏杆等）
        if (plugin.getConfigManager().isEnableSafeSpawnCheck()) {
            return !DANGEROUS_FLOOR_MATERIALS.contains(type);
        }

        return true;
    }

    /**
     * 验证方块是否可以作为玩家身体或头部的容纳空间（可通行且无害）
     *
     * @param block 待检查的身体或头部位置方块
     * @return 若能安全站立且不会窒息/受伤返回 true
     */
    public static boolean isPassable(Block block) {
        if (block == null)
            return false;
        Material type = block.getType();

        // 空气直接放行（跨版本安全 isAir）
        if (isAirMaterial(type))
            return true;

        // 针对 1.13+ 含水方块特性：隔离调用防止 1.12.2 抛出 NoClassDefFoundError（避免台阶/楼梯含水后导致玩家窒息溺水）
        if (isWaterloggedBlock(block)) {
            return false;
        }

        boolean passable;
        try {
            // 针对 1.13+ API：直接调用 Block#isPassable() 校验碰撞箱是否允许实体完全穿行
            passable = block.isPassable();
        } catch (NoSuchMethodError | Exception e) {
            // 针对 1.12.2 旧版本降级策略：此版本无 isPassable 方法，退化为判定非实体且非遮挡光照遮蔽体
            passable = !type.isSolid() && !type.isOccluding();
        }

        // 若空间物理可通行，进一步排查是否包含蜘蛛网、火焰、甜浆果丛等致命障碍物
        if (passable) {
            return !UNSAFE_PASSABLE_MATERIALS.contains(type);
        }

        return false;
    }

    /**
     * 检查方块是否处于含水（Waterlogged）状态
     * <p>
     * 使用纯反射隔离 {@code org.bukkit.block.data.Waterlogged} 接口，
     * 确保在 1.12.2 等缺乏现代 BlockData 机制的旧服务端上静默降级，不抛出异常。
     *
     * @param block 待检测方块
     * @return 若处于含水状态返回 true，否则或发生异常返回 false
     */
    private static boolean isWaterloggedBlock(Block block) {
        try {
            // 针对 1.13+ BlockData 体系：通过类加载反射探测 Waterlogged 接口，防止在 1.12.2 产生类缺失崩溃
            Class<?> wlClass = Class.forName("org.bukkit.block.data.Waterlogged");
            Object data = block.getBlockData();
            if (wlClass.isInstance(data)) {
                Method m = wlClass.getMethod("isWaterlogged");
                return Boolean.TRUE.equals(m.invoke(data));
            }
        } catch (Throwable ignored) {
            // 运行在 1.12.2 时静默捕获异常并放行，旧版本水作为独立独立方块（STATIONARY_WATER）由 passable 逻辑直接拦截
        }
        return false;
    }

    /**
     * 验证出生点群系是否处于允许的安全群系列表内
     *
     * @param plugin 插件主类实例
     * @param biome  待检测生物群系
     * @return 若未在群系黑名单中返回 true
     */
    public static boolean isSafeBiome(AnarchySpawn plugin, Biome biome) {
        if (biome == null)
            return true;
        String biomeName = biome.name();

        // 匹配用户配置中的群系黑名单（忽略大小写并支持关键字模糊匹配，如 "OCEAN", "NETHER"）
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

    /**
     * 验证玩家脚底与头部周遭（水平东、南、西、北 4 面）是否存在引燃与灼伤伤害源
     *
     * @param feet 玩家脚底所在方块
     * @return 若周边四格均无火、岩浆或仙人掌返回 true，存在贴脸伤害源返回 false
     */
    public static boolean isSafeSurroundings(Block feet) {
        if (feet == null)
            return false;

        Block head = feet.getRelative(BlockFace.UP);
        // 水平四方向扫描脚部与头部两层，防止传送后一贴近即被旁边岩浆烧死或仙人掌扎死
        for (BlockFace face : HORIZONTAL_FACES) {
            // 检查脚底水平邻近方块
            Material feetNeighbor = feet.getRelative(face).getType();
            if (DANGEROUS_SURROUNDING_MATERIALS.contains(feetNeighbor)) {
                return false;
            }

            // 检查头部水平邻近方块
            Material headNeighbor = head.getRelative(face).getType();
            if (DANGEROUS_SURROUNDING_MATERIALS.contains(headNeighbor)) {
                return false;
            }
        }
        return true;
    }
}