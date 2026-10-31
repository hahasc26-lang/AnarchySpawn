package com.anarchy.spawn.util;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * 统一调度器抽象层，透明支持经典的 Bukkit/Spigot/Paper 调度器以及 Folia 区域化调度器。
 *
 * <p>
 * Folia 强制实施严格的线程隔离：
 * - 针对 {@link Player} 的所有操作必须通过 {@code player.getScheduler()} (EntityScheduler) 运行在玩家归属的区域线程上。
 * - 针对区块/方块的所有访问必须通过 {@code Bukkit.getRegionScheduler()} 运行在所属世界的区域线程上。
 * - 全局/全服任务必须通过 {@code Bukkit.getGlobalRegionScheduler()} 运行。
 * - 纯后台任务必须通过 {@code Bukkit.getAsyncScheduler()} 运行。
 * </p>
 */
public final class FoliaScheduler {

    private static final boolean FOLIA;

    // RegionScheduler（与世界/区块绑定的任务）
    private static Object regionScheduler;
    private static Method regionExecuteMethod;
    private static Method regionRunMethod;

    // GlobalRegionScheduler（全局周期性/单次任务）
    private static Object globalScheduler;
    private static Method globalExecuteMethod;
    private static Method globalRunDelayedMethod;
    private static Method globalRunAtFixedRateMethod;

    // AsyncScheduler（纯后台异步工作）
    private static Object asyncScheduler;
    private static Method asyncRunNowMethod;

    static {
        boolean folia = false;
        try {
            // 标准 Folia 环境检测：检查 Folia 服务端类或 Bukkit 上的 getRegionScheduler 方法
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
     * @return 若当前运行在基于 Folia 的服务端上则返回 {@code true}。
     */
    public static boolean isFolia() {
        return FOLIA;
    }

    /**
     * 在指定玩家归属的区域线程上运行任务。
     * 用于替代玩家绑定任务中的 {@code Bukkit.getScheduler().runTask(...)}。
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
                            // 尝试调用 execute(Plugin, Runnable, Runnable, long)
                            Method exec = findAccessibleMethod(eClass, "execute", Plugin.class, Runnable.class, Runnable.class, long.class);
                            exec.invoke(entityScheduler, plugin, task, null, 1L);
                            return null;
                        } catch (NoSuchMethodException e) {
                            // 尝试调用 run(Plugin, Consumer<ScheduledTask>, Runnable)
                            Method runM = findAccessibleMethod(eClass, "run", Plugin.class, Consumer.class, Runnable.class);
                            Consumer<Object> consumer = scheduledTask -> task.run();
                            runM.invoke(entityScheduler, plugin, consumer, null);
                            return null;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            // 回退到区域调度器或全局调度器
            return runForRegion(plugin, player != null ? player.getWorld() : null, task);
        }
        return Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * 延迟指定 tick 后在玩家归属的区域线程上运行任务。
     * 用于替代玩家绑定任务中的 {@code Bukkit.getScheduler().runTaskLater(...)}。
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
            // 回退到全局延迟任务
            return runTimerGlobal(plugin, task, ticks, -1L);
        }
        return Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
    }

    /**
     * 在指定世界位于区块 (0,0) 的归属区域线程上运行任务。
     */
    public static BukkitTask runForRegion(Plugin plugin, World world, Runnable task) {
        return runForRegion(plugin, world, 0, 0, task);
    }

    /**
     * 在指定世界中对应区块的归属区域线程上运行任务。
     * 在 Folia 上至关重要，以防止读写方块时发生跨区域访问违规异常。
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
            // 回退到全局任务
            return runTimerGlobal(plugin, task, 0L, -1L);
        }
        return Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * 运行后台缓存池使用的全局重复任务。
     * 用于替代 {@code Bukkit.getScheduler().runTaskTimerAsynchronously(...)}。
     */
    public static BukkitTask runTimerGlobal(Plugin plugin, Runnable task, long delay, long period) {
        Object handle = runTimerGlobalTask(plugin, task, delay, period);
        return handle instanceof BukkitTask ? (BukkitTask) handle : null;
    }

    /**
     * 调度周期性或延迟任务并返回底层任务句柄
     * （在 Spigot/Paper 上为 BukkitTask，在 Folia 上为 ScheduledTask）。
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
     * 取消任意任务句柄（支持 BukkitTask 与 Folia 的 ScheduledTask）。
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
     * 运行不接触任何玩家或区块的纯后台异步任务。
     * 用于替代 {@code Bukkit.getScheduler().runTaskAsynchronously(...)}。
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
