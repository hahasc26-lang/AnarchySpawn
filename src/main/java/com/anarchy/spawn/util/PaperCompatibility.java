package com.anarchy.spawn.util;

import com.anarchy.spawn.AnarchySpawn;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Ensures optimal performance on PaperMC (using getChunkAtAsync & teleportAsync) while maintaining 100% Bukkit/Spigot compatibility.
 */
public class PaperCompatibility {

    private static Boolean isPaper = null;
    private static Method getChunkAtAsyncMethod = null;
    private static Method teleportAsyncMethod = null;

    static {
        try {
            getChunkAtAsyncMethod = World.class.getMethod("getChunkAtAsync", int.class, int.class);
            isPaper = true;
        } catch (NoSuchMethodException e) {
            isPaper = false;
        }

        try {
            teleportAsyncMethod = Player.class.getMethod("teleportAsync", Location.class);
        } catch (NoSuchMethodException ignored) {}
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
            } catch (Throwable ignored) {}
        }

        CompletableFuture<Chunk> fallbackFuture = new CompletableFuture<>();
        if (!AnarchySpawn.getInstance().isEnabled()) {
            fallbackFuture.completeExceptionally(new IllegalStateException("Plugin is disabled"));
            return fallbackFuture;
        }

        if (Bukkit.isPrimaryThread()) {
            try {
                Chunk chunk = world.getChunkAt(x, z);
                if (!chunk.isLoaded()) {
                    chunk.load();
                }
                fallbackFuture.complete(chunk);
            } catch (Throwable t) {
                fallbackFuture.completeExceptionally(t);
            }
        } else {
            Bukkit.getScheduler().runTask(AnarchySpawn.getInstance(), () -> {
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
        }
        return fallbackFuture;
    }

    @SuppressWarnings("unchecked")
    public static CompletableFuture<Boolean> teleportPlayerAsync(Player player, Location location) {
        if (player == null || !player.isOnline() || location == null || location.getWorld() == null) {
            return CompletableFuture.completedFuture(false);
        }

        if (teleportAsyncMethod != null) {
            try {
                CompletableFuture<Boolean> future = (CompletableFuture<Boolean>) teleportAsyncMethod.invoke(player, location);
                if (future != null) {
                    return future;
                }
            } catch (Throwable ignored) {}
        }

        CompletableFuture<Boolean> fallbackFuture = new CompletableFuture<>();
        if (Bukkit.isPrimaryThread()) {
            try {
                boolean result = player.teleport(location);
                fallbackFuture.complete(result);
            } catch (Throwable t) {
                fallbackFuture.complete(false);
            }
        } else {
            if (!AnarchySpawn.getInstance().isEnabled()) {
                return CompletableFuture.completedFuture(false);
            }
            Bukkit.getScheduler().runTask(AnarchySpawn.getInstance(), () -> {
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
        }
        return fallbackFuture;
    }

    public static boolean isPaperEnvironment() {
        return Boolean.TRUE.equals(isPaper);
    }
}
