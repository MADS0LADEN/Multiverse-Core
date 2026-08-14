package org.mvplugins.multiverse.core.utils;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import jakarta.inject.Inject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jvnet.hk2.annotations.Service;

import org.mvplugins.multiverse.core.MultiverseCore;
import org.mvplugins.multiverse.core.utils.compatibility.ServerPlatform;

/**
 * Wraps Paper/Folia region schedulers with a Bukkit scheduler fallback for MockBukkit and older servers.
 */
@Service
public final class PluginScheduler {

    private final MultiverseCore plugin;

    @Inject
    PluginScheduler(@NotNull MultiverseCore plugin) {
        this.plugin = plugin;
    }

    /**
     * Runs a task on the next server tick.
     *
     * @param task the task to run
     */
    public void runNextTick(@NotNull Runnable task) {
        if (ServerPlatform.hasRegionScheduler()) {
            Bukkit.getServer().getGlobalRegionScheduler().run(plugin, scheduledTask -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Runs a task after the given delay in ticks.
     *
     * @param task the task to run
     * @param delayTicks the delay in ticks
     * @return a cancellable task handle
     */
    public PluginTask runDelayed(@NotNull Runnable task, long delayTicks) {
        if (ServerPlatform.hasRegionScheduler()) {
            return wrap(Bukkit.getServer().getGlobalRegionScheduler().runDelayed(
                    plugin, scheduledTask -> task.run(), delayTicks));
        }
        return wrap(Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks));
    }

    /**
     * Runs a task asynchronously.
     *
     * @param task the task to run
     */
    public void runAsync(@NotNull Runnable task) {
        if (ServerPlatform.hasRegionScheduler()) {
            Bukkit.getServer().getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    /**
     * Runs a task on the entity's region thread on the next tick.
     *
     * @param entity the entity whose region thread to use
     * @param task the task to run
     */
    public void runAtEntity(@NotNull Entity entity, @NotNull Runnable task) {
        if (ServerPlatform.hasRegionScheduler()) {
            ScheduledTask scheduledTask = entity.getScheduler().run(plugin, scheduled -> task.run(), null);
            if (scheduledTask == null) {
                return;
            }
        } else {
            runNextTick(task);
        }
    }

    /**
     * Runs a task on the entity's region thread after the given delay in ticks.
     *
     * @param entity the entity whose region thread to use
     * @param task the task to run
     * @param delayTicks the delay in ticks
     * @return a cancellable task handle
     */
    public PluginTask runAtEntityLater(@NotNull Entity entity, @NotNull Runnable task, long delayTicks) {
        if (ServerPlatform.hasRegionScheduler()) {
            ScheduledTask scheduledTask = entity.getScheduler().runDelayed(
                    plugin, scheduled -> task.run(), null, delayTicks);
            if (scheduledTask == null) {
                return noopTask();
            }
            return wrap(scheduledTask);
        }
        return wrap(Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks));
    }

    private static PluginTask wrap(@NotNull ScheduledTask scheduledTask) {
        return scheduledTask::cancel;
    }

    private static PluginTask wrap(@NotNull BukkitTask bukkitTask) {
        return bukkitTask::cancel;
    }

    private static PluginTask noopTask() {
        return () -> { };
    }

    /**
     * A cancellable scheduled task handle.
     */
    public interface PluginTask {

        /**
         * Cancels this scheduled task.
         */
        void cancel();
    }
}
