package com.anarchy.spawn.util;

import com.anarchy.spawn.AnarchySpawn;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Ensures optimal performance on PaperMC (using getChunkAtAsync &
 * teleportAsync) while maintaining 100% Bukkit/Spigot compatibility from 1.12.2 to 1.20+.
 */
public class PaperCompatibility {

    private static Boolean isPaper = null;
    private static Method getChunkAtAsyncMethod = null;
    private static Method teleportAsyncMethod = null;
    private static Method getBiome3DMethod = null;
    private static Method getBiome2DMethod = null;

    private static final Map<String, String[]> SOUND_ALIASES = new HashMap<>();
    private static final Map<String, String[]> PARTICLE_ALIASES = new HashMap<>();

    static {
        try {
            getChunkAtAsyncMethod = World.class.getMethod("getChunkAtAsync", int.class, int.class);
            isPaper = true;
        } catch (NoSuchMethodException e) {
            isPaper = false;
        }

        try {
            teleportAsyncMethod = Player.class.getMethod("teleportAsync", Location.class);
        } catch (NoSuchMethodException ignored) {
        }

        try {
            getBiome3DMethod = World.class.getMethod("getBiome", int.class, int.class, int.class);
        } catch (NoSuchMethodException ignored) {
        }

        try {
            getBiome2DMethod = World.class.getMethod("getBiome", int.class, int.class);
        } catch (NoSuchMethodException ignored) {
        }

        // Initialize Sound Aliases (Modern 26.3 / 1.21+ <-> Legacy 1.12.2)
        addSoundAlias("ENTITY_ENDERMAN_TELEPORT", "ENDERMAN_TELEPORT");
        addSoundAlias("ENTITY_PLAYER_LEVELUP", "LEVEL_UP");
        addSoundAlias("ENTITY_EXPERIENCE_ORB_PICKUP", "ORB_PICKUP");
        addSoundAlias("BLOCK_NOTE_BLOCK_PLING", "NOTE_PLING");
        addSoundAlias("UI_BUTTON_CLICK", "CLICK");
        addSoundAlias("ENTITY_GENERIC_EXPLODE", "EXPLODE");
        addSoundAlias("ENTITY_ARROW_HIT_PLAYER", "SUCCESSFUL_HIT");
        addSoundAlias("BLOCK_CHEST_OPEN", "CHEST_OPEN");
        addSoundAlias("BLOCK_CHEST_CLOSE", "CHEST_CLOSE");
        addSoundAlias("BLOCK_TRIAL_SPAWNER_SPAWN_MOB", "ENTITY_ZOMBIE_INFECT");
        addSoundAlias("ITEM_WIND_CHARGE_WIND_BURST", "ENTITY_GENERIC_EXPLODE");
        addSoundAlias("BLOCK_CREAKING_HEART_SPAWN", "BLOCK_WOODEN_BUTTON_CLICK_ON");

        // Initialize Particle Aliases (Modern 26.3 / 1.21+ <-> Legacy 1.12.2)
        addParticleAlias("REVERSE_PORTAL", "PORTAL");
        addParticleAlias("SOUL_FIRE_FLAME", "FLAME");
        addParticleAlias("SOUL", "SPELL_MOB");
        addParticleAlias("DRAGON_BREATH", "SPELL_WITCH");
        addParticleAlias("END_ROD", "FIREWORKS_SPARK");
        addParticleAlias("GLOW", "SPELL_INSTANT");
        addParticleAlias("WAX_ON", "VILLAGER_HAPPY");
        addParticleAlias("SCRAPE", "CRIT");
        addParticleAlias("PALE_OAK_LEAVES", "PORTAL");
        addParticleAlias("CREAKING_CRUMB", "CRIT");
        addParticleAlias("TRIAL_SPAWNER_DETECTION", "FLAME");
        addParticleAlias("WIND_CHARGE", "EXPLOSION_NORMAL");
    }

    private static void addSoundAlias(String modern, String legacy) {
        SOUND_ALIASES.put(modern, new String[]{modern, legacy});
        SOUND_ALIASES.put(legacy, new String[]{modern, legacy});
    }

    private static void addParticleAlias(String modern, String legacy) {
        PARTICLE_ALIASES.put(modern, new String[]{modern, legacy});
        PARTICLE_ALIASES.put(legacy, new String[]{modern, legacy});
    }

    public static Biome getBiomeAt(World world, int x, int y, int z) {
        if (world == null) return null;

        // Try 3D biome lookup (1.16+)
        if (getBiome3DMethod != null) {
            try {
                return (Biome) getBiome3DMethod.invoke(world, x, y, z);
            } catch (Throwable ignored) {
            }
        }

        // Try 2D biome lookup (1.12.2 - 1.15.2)
        if (getBiome2DMethod != null) {
            try {
                return (Biome) getBiome2DMethod.invoke(world, x, z);
            } catch (Throwable ignored) {
            }
        }

        // Direct fallback attempt
        try {
            return world.getBiome(x, z);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String getBiomeNameAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return "UNKNOWN";
        Biome biome = getBiomeAt(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        return biome != null ? biome.name() : "UNKNOWN";
    }

    public static Sound getSoundSafely(String soundName) {
        if (soundName == null || soundName.trim().isEmpty()) return null;
        String cleanName = soundName.trim().toUpperCase(Locale.ROOT);

        try {
            return Sound.valueOf(cleanName);
        } catch (IllegalArgumentException ignored) {
        }

        String[] aliases = SOUND_ALIASES.get(cleanName);
        if (aliases != null) {
            for (String alias : aliases) {
                try {
                    return Sound.valueOf(alias);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        // Fuzzy fallback checks
        if (cleanName.contains("TELEPORT")) {
            for (String fallback : new String[]{"ENTITY_ENDERMAN_TELEPORT", "ENDERMAN_TELEPORT"}) {
                try {
                    return Sound.valueOf(fallback);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        return null;
    }

    public static void playSoundSafely(Player player, Location location, String soundName, float volume, float pitch) {
        if (player == null || location == null) return;
        Sound sound = getSoundSafely(soundName);
        if (sound != null) {
            try {
                player.playSound(location, sound, volume, pitch);
            } catch (Throwable ignored) {
            }
        }
    }

    public static Particle getParticleSafely(String particleName) {
        if (particleName == null || particleName.trim().isEmpty()) return null;
        String cleanName = particleName.trim().toUpperCase(Locale.ROOT);

        try {
            return Particle.valueOf(cleanName);
        } catch (IllegalArgumentException ignored) {
        }

        String[] aliases = PARTICLE_ALIASES.get(cleanName);
        if (aliases != null) {
            for (String alias : aliases) {
                try {
                    return Particle.valueOf(alias);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        // Default particle fallback
        try {
            return Particle.valueOf("PORTAL");
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static void spawnParticleSafely(World world, String particleName, Location location, int count, double offsetX, double offsetY, double offsetZ, double extra) {
        if (world == null || location == null) return;
        Particle particle = getParticleSafely(particleName);
        if (particle != null) {
            try {
                world.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra);
            } catch (Throwable ignored) {
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static CompletableFuture<Chunk> getChunkAtAsync(World world, int x, int z) {
        if (world == null) {
            CompletableFuture<Chunk> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalArgumentException("World cannot be null"));
            return f;
        }

        if (Boolean.TRUE.equals(isPaper) && getChunkAtAsyncMethod != null) {
            try {
                CompletableFuture<Chunk> future = (CompletableFuture<Chunk>) getChunkAtAsyncMethod.invoke(world, x, z);
                if (future != null) {
                    return future;
                }
            } catch (Throwable ignored) {
            }
        }

        CompletableFuture<Chunk> fallbackFuture = new CompletableFuture<>();
        if (!AnarchySpawn.getInstance().isEnabled()) {
            fallbackFuture.completeExceptionally(new IllegalStateException("Plugin is disabled"));
            return fallbackFuture;
        }

        // Chunk access must run on the owning world's region thread (Folia) or the main
        // thread (Spigot/Paper). FoliaScheduler handles both transparently.
        FoliaScheduler.runForRegion(AnarchySpawn.getInstance(), world, x, z, () -> {
            try {
                Chunk chunk = world.getChunkAt(x, z);
                if (!chunk.isLoaded()) {
                    chunk.load();
                }
                fallbackFuture.complete(chunk);
            } catch (Throwable t) {
                fallbackFuture.completeExceptionally(t);
            }
        });
        return fallbackFuture;
    }

    @SuppressWarnings("unchecked")
    public static CompletableFuture<Boolean> teleportPlayerAsync(Player player, Location location) {
        if (player == null || !player.isOnline() || location == null || location.getWorld() == null) {
            return CompletableFuture.completedFuture(false);
        }

        if (teleportAsyncMethod != null) {
            try {
                CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) teleportAsyncMethod.invoke(player,
                        location);
                if (future != null) {
                    return future;
                }
            } catch (Throwable ignored) {
            }
        }

        CompletableFuture<Boolean> fallbackFuture = new CompletableFuture<>();
        if (!AnarchySpawn.getInstance().isEnabled()) {
            return CompletableFuture.completedFuture(false);
        }
        // Teleportation must run on the player's owning region thread (Folia) or the
        // main
        // thread (Spigot/Paper). FoliaScheduler handles both transparently.
        FoliaScheduler.runForPlayer(AnarchySpawn.getInstance(), player, () -> {
            try {
                if (player.isOnline()) {
                    boolean result = player.teleport(location);
                    fallbackFuture.complete(result);
                } else {
                    fallbackFuture.complete(false);
                }
            } catch (Throwable t) {
                fallbackFuture.complete(false);
            }
        });
        return fallbackFuture;
    }

    public static boolean isPaperEnvironment() {
        return Boolean.TRUE.equals(isPaper);
    }
}

