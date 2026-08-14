package org.mvplugins.multiverse.core.utils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import jakarta.inject.Inject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
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
     * Runs a task on the global tick thread, waiting if a hop is required.
     *
     * <p>On Paper, Spigot, MockBukkit, the global tick thread, and during server startup
     * this runs immediately. After the server is ticking on Folia or CanvasMC, player
     * commands run on a region thread, so this hops with
     * {@code GlobalRegionScheduler.execute} and waits. Do not use the async scheduler
     * for world create, PVP, gamerules, or spawn ticks.</p>
     *
     * @param task the task to run
     */
    public void runOnGlobalTick(@NotNull Runnable task) {
        callOnGlobalTick(() -> {
            task.run();
            return null;
        });
    }

    /**
     * Runs a value-returning task on the global tick thread, waiting if a hop is required.
     *
     * @param action the action to run
     * @param <T> the result type
     * @return the action result
     */
    public <T> T callOnGlobalTick(@NotNull Supplier<T> action) {
        if (!needsGlobalTickHop()) {
            return action.get();
        }
        return hopToGlobalTick(action);
    }

    /**
     * Looks up this service and runs {@link #runOnGlobalTick(Runnable)}.
     *
     * @param task the task to run
     */
    public static void executeOnGlobalTick(@NotNull Runnable task) {
        if (!needsGlobalTickHop()) {
            task.run();
            return;
        }
        MultiverseCore core = JavaPlugin.getPlugin(MultiverseCore.class);
        core.getServiceLocator().getService(PluginScheduler.class).hopToGlobalTick(() -> {
            task.run();
            return null;
        });
    }

    private static boolean needsGlobalTickHop() {
        if (!ServerPlatform.isRegionized() || !ServerPlatform.hasRegionScheduler()) {
            return false;
        }
        if (ServerPlatform.isGlobalTickThread()) {
            return false;
        }
        // Startup is allowed to create worlds. Hopping before the global tick loop
        // starts would deadlock onEnable waiting for a tick that has not begun.
        return currentTick() > 0;
    }

    private static int currentTick() {
        try {
            return Bukkit.getCurrentTick();
        } catch (NoSuchMethodError | UnsupportedOperationException e) {
            return 0;
        }
    }

    private <T> T hopToGlobalTick(@NotNull Supplier<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                future.complete(action.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        try {
            return future.get(5, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timed out waiting for the global tick thread", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for the global tick thread", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(cause);
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
