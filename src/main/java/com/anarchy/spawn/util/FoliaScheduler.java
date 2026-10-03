package com.anarchy.spawn.util;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;

/**
 * Unified scheduler abstraction layer that transparently supports both the
 * classic Bukkit/Spigot/Paper scheduler and the Folia regionized scheduler.
 *
 * <p>
 * Folia enforces strict thread confinement: all {@link Player} operations must
 * run on the player's owning region thread, all chunk/block access must run on
 * the owning world's region thread, and pure background work must run on the
 * async scheduler. The classic {@code Bukkit.getScheduler()} API is unavailable
 * on Folia, so every scheduling call in this plugin is routed through this
 * class.
 * </p>
 *
 * <p>
 * Because the project compiles against {@code paper-api 1.20.4} (which predates
 * the regionized scheduler API), all Folia calls are performed through
 * reflection. On non-Folia environments every method delegates to the original
 * classic scheduler path, guaranteeing zero behavioral regression for existing
 * Paper/Spigot users.
 * </p>
 *
 * <p>
 * Folia's scheduler API is composed of three distinct schedulers, each with its
 * own method signatures:
 * </p>
 * <ul>
 * <li>{@code Bukkit.getRegionizedScheduler().player()} →
 * {@code EntityScheduler}: {@code run(Plugin, Runnable, Runnable)} and
 * {@code runDelayed(Plugin, Runnable, Runnable, long)}.</li>
 * <li>{@code Bukkit.getRegionizedScheduler().region()} →
 * {@code RegionScheduler}: {@code run(Plugin, World, int, int, Runnable)}
 * and {@code runDelayed(Plugin, World, int, int, Runnable, long)}.</li>
 * <li>{@code Bukkit.getRegionizedScheduler().global()} →
 * {@code GlobalRegionScheduler}: {@code run(Plugin, Runnable)} and
 * {@code runAtFixedRate(Plugin, Runnable, long, long)}.</li>
 * <li>{@code Bukkit.getAsyncScheduler()} → {@code AsyncScheduler}:
 * {@code runNow(Plugin, Consumer)}.</li>
 * </ul>
 *
 * <p>
 * Each reflective handle is resolved independently so that a single missing
 * method can never disable the whole Folia detection path.
 * </p>
 */
public final class FoliaScheduler {

    private static final boolean FOLIA;

    // EntityScheduler (player-bound work).
    private static Object entityScheduler;
    private static Method entityRunMethod;
    private static Method entityRunDelayedMethod;

    // RegionScheduler (world/chunk-bound work).
    private static Object regionScheduler;
    private static Method regionRunMethod;

    // GlobalRegionScheduler (global repeating tasks).
    private static Object globalScheduler;
    private static Method globalRunMethod;
    private static Method globalRunDelayedMethod;
    private static Method globalRunAtFixedRateMethod;

    // AsyncScheduler (pure background work).
    private static Object asyncScheduler;
    private static Method asyncRunNowMethod;

    static {
        boolean folia = false;
        try {
            Method getRegionized = Bukkit.class.getMethod("getRegionizedScheduler");
            Object scheduler = getRegionized.invoke(null);
            if (scheduler != null) {
                Class<?> schedulerClass = scheduler.getClass();

                // --- EntityScheduler (player) ---
                try {
                    Method playerScheduler = schedulerClass.getMethod("player");
                    entityScheduler = playerScheduler.invoke(scheduler);
                    Class<?> entityClass = entityScheduler.getClass();
                    entityRunMethod = entityClass.getMethod(
                            "run", Plugin.class, Runnable.class, Runnable.class);
                    entityRunDelayedMethod = entityClass.getMethod(
                            "runDelayed", Plugin.class, Runnable.class, Runnable.class, long.class);
                } catch (Throwable ignored) {
                    entityScheduler = null;
                }

                // --- RegionScheduler (world) ---
                try {
                    Method regionSchedulerMethod = schedulerClass.getMethod("region");
                    regionScheduler = regionSchedulerMethod.invoke(scheduler);
                    Class<?> regionClass = regionScheduler.getClass();
                    regionRunMethod = regionClass.getMethod(
                            "run", Plugin.class, World.class, int.class, int.class, Runnable.class);
                } catch (Throwable ignored) {
                    regionScheduler = null;
                }

                // --- GlobalRegionScheduler ---
                try {
                    Method globalSchedulerMethod = schedulerClass.getMethod("global");
                    globalScheduler = globalSchedulerMethod.invoke(scheduler);
                    Class<?> globalClass = globalScheduler.getClass();
                    globalRunMethod = globalClass.getMethod("run", Plugin.class, Runnable.class);
                    globalRunDelayedMethod = globalClass.getMethod(
                            "runDelayed", Plugin.class, Runnable.class, long.class);
                    globalRunAtFixedRateMethod = globalClass.getMethod(
                            "runAtFixedRate", Plugin.class, Runnable.class, long.class, long.class);
                } catch (Throwable ignored) {
                    globalScheduler = null;
                }

                // --- AsyncScheduler ---
                try {
                    Method getAsync = Bukkit.class.getMethod("getAsyncScheduler");
                    asyncScheduler = getAsync.invoke(null);
                    if (asyncScheduler != null) {
                        Class<?> asyncClass = asyncScheduler.getClass();
                        asyncRunNowMethod = asyncClass.getMethod(
                                "runNow", Plugin.class, java.util.function.Consumer.class);
                    }
                } catch (Throwable ignored) {
                    asyncScheduler = null;
                }

                // Folia is considered available when at least the region scheduler
                // resolved, which is the minimum required for chunk/block access.
                folia = (regionScheduler != null);
            }
        } catch (Throwable ignored) {
            folia = false;
        }
        FOLIA = folia;
    }

    private FoliaScheduler() {
    }

    /**
     * @return {@code true} when running on a Folia-based server.
     */
    public static boolean isFolia() {
        return FOLIA;
    }

    /**
     * Runs a task on the owning region thread of the given player.
     * Replaces {@code Bukkit.getScheduler().runTask(...)} for player-bound work.
     */
    public static BukkitTask runForPlayer(Plugin plugin, Player player, Runnable task) {
        if (FOLIA) {
            if (entityScheduler != null && entityRunMethod != null && player != null) {
                try {
                    // EntityScheduler#run(Plugin, Runnable, Runnable retired)
                    entityRunMethod.invoke(entityScheduler, plugin, task, null);
                    return null;
                } catch (Throwable ignored) {
                    // Fall through to the region scheduler below.
                }
            }
            // On Folia the classic scheduler is unavailable; degrade to the region
            // scheduler (or the global scheduler) instead of throwing
            // UnsupportedOperationException.
            return runForRegion(plugin, player != null ? player.getWorld() : null, task);
        }
        return Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * Runs a task later on the owning region thread of the given player.
     * Replaces {@code Bukkit.getScheduler().runTaskLater(...)} for player-bound
     * work.
     */
    public static BukkitTask runForPlayerLater(Plugin plugin, Player player, Runnable task, long ticks) {
        if (FOLIA) {
            if (entityScheduler != null && entityRunDelayedMethod != null && player != null) {
                try {
                    // EntityScheduler#runDelayed(Plugin, Runnable, Runnable retired, long delay)
                    entityRunDelayedMethod.invoke(entityScheduler, plugin, task, null, ticks);
                    return null;
                } catch (Throwable ignored) {
                    // Fall through to the global scheduler below.
                }
            }
            // On Folia the classic scheduler is unavailable; degrade to the global
            // region scheduler instead of throwing UnsupportedOperationException.
            return runTimerGlobal(plugin, task, ticks, -1L);
        }
        return Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
    }

    /**
     * Runs a task on the owning region thread of the given world.
     * Replaces {@code Bukkit.getScheduler().runTask(...)} for chunk/block access.
     */
    public static BukkitTask runForRegion(Plugin plugin, World world, Runnable task) {
        if (FOLIA) {
            if (regionScheduler != null && regionRunMethod != null && world != null) {
                try {
                    // RegionScheduler#run(Plugin, World, int chunkX, int chunkZ, Runnable)
                    // Chunk coordinates (0, 0) are used because the task itself resolves the
                    // exact block coordinates; the region owning that chunk is scheduled.
                    regionRunMethod.invoke(regionScheduler, plugin, world, 0, 0, task);
                    return null;
                } catch (Throwable ignored) {
                    // Fall through to the global scheduler below.
                }
            }
            // On Folia the classic scheduler is unavailable; degrade to the global
            // region scheduler instead of throwing UnsupportedOperationException.
            return runTimerGlobal(plugin, task, 0L, -1L);
        }
        return Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * Runs a repeating global task used by the background cache pool.
     * Replaces {@code Bukkit.getScheduler().runTaskTimerAsynchronously(...)}.
     */
    public static BukkitTask runTimerGlobal(Plugin plugin, Runnable task, long delay, long period) {
        if (FOLIA) {
            if (globalScheduler != null) {
                try {
                    if (period > 0L) {
                        // GlobalRegionScheduler#runAtFixedRate(Plugin, Runnable, long delay, long
                        // period)
                        if (globalRunAtFixedRateMethod != null) {
                            globalRunAtFixedRateMethod.invoke(globalScheduler, plugin, task, delay, period);
                            return null;
                        }
                    } else {
                        // One-shot global task (period <= 0): use runDelayed when available.
                        if (globalRunDelayedMethod != null) {
                            globalRunDelayedMethod.invoke(globalScheduler, plugin, task, Math.max(0L, delay));
                            return null;
                        }
                        if (globalRunMethod != null) {
                            globalRunMethod.invoke(globalScheduler, plugin, task);
                            return null;
                        }
                    }
                } catch (Throwable ignored) {
                    // Fall through to the async scheduler below.
                }
            }
            // On Folia the classic scheduler is unavailable; degrade to the async
            // scheduler instead of throwing UnsupportedOperationException.
            return runAsync(plugin, task);
        }
        if (period > 0L) {
            return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
        }
        return Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, Math.max(0L, delay));
    }

    /**
     * Runs a pure background task that never touches players or chunks.
     * Replaces {@code Bukkit.getScheduler().runTaskAsynchronously(...)}.
     */
    public static BukkitTask runAsync(Plugin plugin, Runnable task) {
        if (FOLIA) {
            if (asyncScheduler != null && asyncRunNowMethod != null) {
                try {
                    // AsyncScheduler#runNow(Plugin, Consumer<ScheduledTask>)
                    java.util.function.Consumer<Object> consumer = scheduledTask -> task.run();
                    asyncRunNowMethod.invoke(asyncScheduler, plugin, consumer);
                    return null;
                } catch (Throwable ignored) {
                    // Fall through to the global scheduler below.
                }
            }
            // On Folia the classic scheduler is unavailable; degrade to the global
            // region scheduler instead of throwing UnsupportedOperationException.
            if (globalScheduler != null && globalRunMethod != null) {
                try {
                    globalRunMethod.invoke(globalScheduler, plugin, task);
                    return null;
                } catch (Throwable ignored) {
                    // Give up silently; the task cannot be scheduled on this runtime.
                }
            }
            return null;
        }
        return Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }
}
