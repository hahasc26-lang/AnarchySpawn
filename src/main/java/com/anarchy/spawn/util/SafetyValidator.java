package com.anarchy.spawn.util;

import com.anarchy.spawn.AnarchySpawn;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

public class SafetyValidator {

    public static boolean isSafeFloor(AnarchySpawn plugin, Block ground) {
        if (ground == null) return false;
        Material type = ground.getType();

        if (!type.isSolid()) return false;

        if (plugin.getConfigManager().getUnsafeFloorMaterials().contains(type)) {
            return false;
        }

        String name = type.name();
        if (name.contains("LAVA") || name.contains("FIRE") || name.contains("CACTUS")
                || name.contains("BERRY") || name.contains("POWDER_SNOW") || name.contains("MAGMA")
                || name.contains("PORTAL") || name.contains("BEDROCK") || name.contains("SLIME")
                || name.contains("CAMPFIRE") || name.contains("WITHER") || name.contains("TRIPWIRE")
                || name.contains("PRESSURE_PLATE") || name.contains("SCAFFOLDING") || name.contains("HONEY_BLOCK")
                || name.contains("POINTED_DRIPSTONE") || name.contains("ANVIL") || name.contains("TRIAL_SPAWNER")
                || name.contains("VAULT") || name.contains("SCULK_SHRIEKER") || name.contains("LIGHTNING_ROD")
                || name.contains("FENCE") || name.contains("WALL") || name.contains("BARS")
                || name.contains("PANE") || name.contains("CHAIN") || name.contains("LANTERN")
                || name.contains("END_PORTAL_FRAME") || name.contains("DRAGON_EGG") || name.contains("SHULKER_BOX")) {
            return false;
        }

        return true;
    }

    public static boolean isPassable(Block block) {
        if (block == null) return false;
        Material type = block.getType();

        if (type.isAir()) return true;

        try {
            BlockData data = block.getBlockData();
            if (data instanceof Waterlogged && ((Waterlogged) data).isWaterlogged()) {
                return false;
            }
        } catch (Throwable ignored) {}

        boolean passable;
        try {
            passable = block.isPassable();
        } catch (NoSuchMethodError | Exception e) {
            passable = !type.isSolid() && !type.isOccluding();
        }

        if (passable) {
            String name = type.name();
            return !name.contains("LAVA") && !name.contains("WATER") && !name.contains("FIRE")
                    && !name.contains("BERRY") && !name.contains("POWDER_SNOW") && !name.contains("WEB")
                    && !name.contains("PORTAL") && !name.contains("CAMPFIRE") && !name.contains("WITHER")
                    && !name.contains("KELP") && !name.contains("SEAGRASS") && !name.contains("BUBBLE_COLUMN")
                    && !name.contains("CACTUS") && !name.contains("DRIPSTONE") && !name.contains("TRIPWIRE")
                    && !name.contains("SCAFFOLDING") && !name.contains("LIGHTNING_ROD") && !name.contains("TRIAL_SPAWNER")
                    && !name.contains("VAULT");
        }

        return false;
    }

    public static boolean isSafeBiome(AnarchySpawn plugin, Biome biome) {
        if (biome == null) return true;
        String biomeName = biome.name().toUpperCase();
        for (String blacklisted : plugin.getConfigManager().getBiomeBlacklist()) {
            if (biomeName.contains(blacklisted)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isSafeSurroundings(Block feet) {
        if (feet == null) return false;
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace face : faces) {
            Block neighbor = feet.getRelative(face);
            Material nType = neighbor.getType();
            if (nType == Material.LAVA || nType == Material.FIRE) {
                return false;
            }
            try {
                if (nType == Material.valueOf("SOUL_FIRE")) return false;
            } catch (Throwable ignored) {}
        }
        return true;
    }
}
