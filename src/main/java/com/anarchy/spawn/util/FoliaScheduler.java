package com.anarchy.spawn.util;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Unified scheduler abstraction layer that transparently supports both the
 * classic Bukkit/Spigot/Paper scheduler and the Folia regionized scheduler.
 *
 * <p>
 * Folia enforces strict thread confinement:
 * - All {@link Player} operations must run on the player's owning region thread via {@code player.getScheduler()} (EntityScheduler).
 * - All chunk/block access must run on the owning world's region thread via {@code Bukkit.getRegionScheduler()}.
 * - Global/server-wide tasks must run via {@code Bukkit.getGlobalRegionScheduler()}.
 * - Pure background work must run via {@code Bukkit.getAsyncScheduler()}.
 * </p>
 */
public final class FoliaScheduler {

    private static final boolean FOLIA;

    // RegionScheduler (world/chunk-bound work)
    private static Object regionScheduler;
    private static Method regionExecuteMethod;
    private static Method regionRunMethod;

    // GlobalRegionScheduler (global repeating/one-shot tasks)
    private static Object globalScheduler;
    private static Method globalExecuteMethod;
    private static Method globalRunDelayedMethod;
    private static Method globalRunAtFixedRateMethod;

    // AsyncScheduler (pure background work)
    private static Object asyncScheduler;
    private static Method asyncRunNowMethod;

    static {
        boolean folia = false;
        try {
            // Standard Folia detection: check for Folia server class or getRegionScheduler on Bukkit
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                folia = true;
            } catch (ClassNotFoundException e) {
                Method m = Bukkit.class.getMethod("getRegionScheduler");
                folia = (m != null);
            }

            if (folia) {
                // --- RegionScheduler ---
                try {
                    Method getRegion = Bukkit.class.getMethod("getRegionScheduler");
                    regionScheduler = getRegion.invoke(null);
                    if (regionScheduler != null) {
                        Class<?> rClass = getPublicSchedulerInterface(regionScheduler,
                                "io.papermc.paper.threadedregions.scheduler.RegionScheduler");
                        try {
                            regionExecuteMethod = findAccessibleMethod(rClass, "execute", Plugin.class, World.class, int.class, int.class, Runnable.class);
                        } catch (NoSuchMethodException ignored) {
                            try {
                                regionRunMethod = findAccessibleMethod(rClass, "run", Plugin.class, World.class, int.class, int.class, Consumer.class);
                            } catch (NoSuchMethodException ignored2) {}
                        }
                    }
                } catch (Throwable ignored) {
                    regionScheduler = null;
                }

                // --- GlobalRegionScheduler ---
                try {
                    Method getGlobal = Bukkit.class.getMethod("getGlobalRegionScheduler");
                    globalScheduler = getGlobal.invoke(null);
                    if (globalScheduler != null) {
                        Class<?> gClass = getPublicSchedulerInterface(globalScheduler,
                                "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
                        try {
                            globalExecuteMethod = findAccessibleMethod(gClass, "execute", Plugin.class, Runnable.class);
                        } catch (NoSuchMethodException ignored) {}

                        try {
                            globalRunDelayedMethod = findAccessibleMethod(gClass, "runDelayed", Plugin.class, Consumer.class, long.class);
                        } catch (NoSuchMethodException ignored) {}

                        try {
                            globalRunAtFixedRateMethod = findAccessibleMethod(gClass, "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
                        } catch (NoSuchMethodException ignored) {}
                    }
                } catch (Throwable ignored) {
                    globalScheduler = null;
                }

                // --- AsyncScheduler ---
                try {
                    Method getAsync = Bukkit.class.getMethod("getAsyncScheduler");
                    asyncScheduler = getAsync.invoke(null);
                    if (asyncScheduler != null) {
                        Class<?> aClass = getPublicSchedulerInterface(asyncScheduler,
                                "io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
                        asyncRunNowMethod = findAccessibleMethod(aClass, "runNow", Plugin.class, Consumer.class);
                    }
                } catch (Throwable ignored) {
                    asyncScheduler = null;
                }
            }
        } catch (Throwable t) {
            folia = false;
        }

        FOLIA = folia;
    }

    private FoliaScheduler() {
    }

    private static Class<?> getPublicSchedulerInterface(Object instance, String interfaceName) {
        try {
            return Class.forName(interfaceName);
        } catch (ClassNotFoundException ignored) {
            return instance.getClass();
        }
    }

    private static Method findAccessibleMethod(Class<?> clazz, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method m = clazz.getMethod(name, parameterTypes);
        try {
            m.setAccessible(true);
        } catch (Throwable ignored) {
        }
        return m;
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
            if (player != null && player.isOnline()) {
                try {
                    Method getScheduler = player.getClass().getMethod("getScheduler");
                    Object entityScheduler = getScheduler.invoke(player);
                    if (entityScheduler != null) {
                        Class<?> eClass = getPublicSchedulerInterface(entityScheduler,
                                "io.papermc.paper.threadedregions.scheduler.EntityScheduler");
                        try {
                            // Try execute(Plugin, Runnable, Runnable, long)
                            Method exec = findAccessibleMethod(eClass, "execute", Plugin.class, Runnable.class, Runnable.class, long.class);
                            exec.invoke(entityScheduler, plugin, task, null, 1L);
                            return null;
                        } catch (NoSuchMethodException e) {
                            // Try run(Plugin, Consumer<ScheduledTask>, Runnable)
                            Method runM = findAccessibleMethod(eClass, "run", Plugin.class, Consumer.class, Runnable.class);
                            Consumer<Object> consumer = scheduledTask -> task.run();
                            runM.invoke(entityScheduler, plugin, consumer, null);
                            return null;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            // Fallback to region or global
            return runForRegion(plugin, player != null ? player.getWorld() : null, task);
        }
        return Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * Runs a task later on the owning region thread of the given player.
     * Replaces {@code Bukkit.getScheduler().runTaskLater(...)} for player-bound work.
     */
    public static BukkitTask runForPlayerLater(Plugin plugin, Player player, Runnable task, long ticks) {
        if (FOLIA) {
            if (player != null && player.isOnline()) {
                try {
                    Method getScheduler = player.getClass().getMethod("getScheduler");
                    Object entityScheduler = getScheduler.invoke(player);
                    if (entityScheduler != null) {
                        Class<?> eClass = getPublicSchedulerInterface(entityScheduler,
                                "io.papermc.paper.threadedregions.scheduler.EntityScheduler");
                        try {
                            Method runDelayed = findAccessibleMethod(eClass, "runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);
                            Consumer<Object> consumer = scheduledTask -> task.run();
                            runDelayed.invoke(entityScheduler, plugin, consumer, null, Math.max(1L, ticks));
                            return null;
                        } catch (NoSuchMethodException ignored) {}
                    }
                } catch (Throwable ignored) {
                }
            }
            // Fallback to global delayed
            return runTimerGlobal(plugin, task, ticks, -1L);
        }
        return Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
    }

    /**
     * Runs a task on the owning region thread of the given world at chunk (0,0).
     */
    public static BukkitTask runForRegion(Plugin plugin, World world, Runnable task) {
        return runForRegion(plugin, world, 0, 0, task);
    }

    /**
     * Runs a task on the owning region thread of the specified chunk in the given world.
     * Essential on Folia to prevent cross-region access violations when reading or modifying blocks.
     */
    public static BukkitTask runForRegion(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task) {
        if (FOLIA) {
            if (regionScheduler != null && world != null) {
                try {
                    if (regionExecuteMethod != null) {
                        regionExecuteMethod.invoke(regionScheduler, plugin, world, chunkX, chunkZ, task);
                        return null;
                    }
                    if (regionRunMethod != null) {
                        Consumer<Object> consumer = scheduledTask -> task.run();
                        regionRunMethod.invoke(regionScheduler, plugin, world, chunkX, chunkZ, consumer);
                        return null;
                    }
                } catch (Throwable ignored) {
                }
            }
            // Fallback to global
            return runTimerGlobal(plugin, task, 0L, -1L);
        }
        return Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * Runs a repeating global task used by the background cache pool.
     * Replaces {@code Bukkit.getScheduler().runTaskTimerAsynchronously(...)}.
     */
    public static BukkitTask runTimerGlobal(Plugin plugin, Runnable task, long delay, long period) {
        Object handle = runTimerGlobalTask(plugin, task, delay, period);
        return handle instanceof BukkitTask ? (BukkitTask) handle : null;
    }

    /**
     * Schedules a repeating or delayed task and returns the underlying task handle
     * (BukkitTask on Spigot/Paper, or ScheduledTask on Folia).
     */
    public static Object runTimerGlobalTask(Plugin plugin, Runnable task, long delay, long period) {
        if (FOLIA) {
            if (globalScheduler != null) {
                try {
                    Consumer<Object> consumer = scheduledTask -> task.run();
                    if (period > 0L) {
                        if (globalRunAtFixedRateMethod != null) {
                            return globalRunAtFixedRateMethod.invoke(globalScheduler, plugin, consumer, Math.max(1L, delay), period);
                        }
                    } else {
                        if (delay > 0L && globalRunDelayedMethod != null) {
                            return globalRunDelayedMethod.invoke(globalScheduler, plugin, consumer, delay);
                        }
                        if (globalExecuteMethod != null) {
                            globalExecuteMethod.invoke(globalScheduler, plugin, task);
                            return null;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            return runAsync(plugin, task);
        }
        if (period > 0L) {
            return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
        }
        return Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, Math.max(0L, delay));
    }

    /**
     * Cancels any task handle, whether BukkitTask or Folia ScheduledTask.
     */
    public static void cancelTask(Object taskHandle) {
        if (taskHandle == null) return;
        try {
            if (taskHandle instanceof BukkitTask) {
                ((BukkitTask) taskHandle).cancel();
            } else {
                Class<?> taskClass = getPublicSchedulerInterface(taskHandle,
                        "io.papermc.paper.threadedregions.scheduler.ScheduledTask");
                Method cancelMethod = findAccessibleMethod(taskClass, "cancel");
                cancelMethod.invoke(taskHandle);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Runs a pure background task that never touches players or chunks.
     * Replaces {@code Bukkit.getScheduler().runTaskAsynchronously(...)}.
     */
    public static BukkitTask runAsync(Plugin plugin, Runnable task) {
        if (FOLIA) {
            if (asyncScheduler != null && asyncRunNowMethod != null) {
                try {
                    Consumer<Object> consumer = scheduledTask -> task.run();
                    asyncRunNowMethod.invoke(asyncScheduler, plugin, consumer);
                    return null;
                } catch (Throwable ignored) {
                }
            }
            if (globalScheduler != null && globalExecuteMethod != null) {
                try {
                    globalExecuteMethod.invoke(globalScheduler, plugin, task);
                    return null;
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
        return Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }
}
