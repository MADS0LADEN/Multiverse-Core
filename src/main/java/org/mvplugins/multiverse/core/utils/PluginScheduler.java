package org.mvplugins.multiverse.core.utils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import jakarta.inject.Inject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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

    private static final long HOP_TIMEOUT_MINUTES = 5;

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
     * <p>Use this only for {@code Bukkit.createWorld()} / unload. Block reads, PVP, gamerules,
     * and ticks-per-spawn must use {@link #callAtLocation(Location, Supplier)} instead.
     * Hopping to the global scheduler for those APIs still fails Folia's region ownership check.</p>
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

    /**
     * Returns whether the current thread owns the region for this location.
     *
     * <p>On Paper, Spigot, and MockBukkit this is always true. On Folia/Canvas it is
     * {@link Bukkit#isOwnedByCurrentRegion(Location)} — not "any region thread" and
     * not the global tick thread.</p>
     *
     * @param location the location whose region to check
     * @return true if block reads at this location are legal on this thread
     */
    public static boolean isOwnedByCurrentRegion(@NotNull Location location) {
        if (!ServerPlatform.isRegionized() || !ServerPlatform.hasRegionScheduler()) {
            return true;
        }
        if (location.getWorld() == null) {
            return true;
        }
        try {
            return Bukkit.isOwnedByCurrentRegion(location);
        } catch (NoSuchMethodError | NoClassDefFoundError e) {
            return true;
        }
    }

    /**
     * Returns whether this thread may block waiting for a scheduler hop.
     *
     * <p>During {@code onEnable} ({@code currentTick() <= 0}) waiting deadlocks because
     * region ticks have not started. Callers must then schedule fire-and-forget instead
     * of running block or world-setting mutations inline.</p>
     *
     * @return true if hopping and waiting is safe
     */
    public static boolean canWaitForSchedulerHop() {
        return currentTick() > 0;
    }

    /**
     * Runs a task on the region that owns this location. Never waits.
     *
     * <p>Inline when this thread already owns the location (or the server is not
     * regionized). Otherwise schedules on {@code RegionScheduler} and returns.</p>
     *
     * @param location the location whose region should run the task
     * @param task the task to run
     */
    public void runAtLocation(@NotNull Location location, @NotNull Runnable task) {
        if (isOwnedByCurrentRegion(location)) {
            task.run();
            return;
        }
        scheduleAtLocation(plugin, location, task);
    }

    /**
     * Runs a value-returning task on the region that owns this location.
     *
     * <p>Inline when this thread already owns the location, when the server is not
     * regionized, or during startup (cannot wait). After the server is ticking,
     * hops with {@code RegionScheduler.execute} and waits so import/load can finish
     * spawn-safety before answering the player.</p>
     *
     * @param location the location whose region should run the action
     * @param action the action to run
     * @param <T> the result type
     * @return the action result
     */
    public <T> T callAtLocation(@NotNull Location location, @NotNull Supplier<T> action) {
        return callAtLocation(plugin, location, action);
    }

    /**
     * Looks up this plugin and runs a task on the location's region.
     *
     * <p>No plugin lookup when the task can run inline. During startup this schedules
     * without waiting. After the server is ticking this hops and waits.</p>
     *
     * @param location the location whose region should run the task
     * @param task the task to run
     */
    public static void executeAtLocation(@NotNull Location location, @NotNull Runnable task) {
        if (isOwnedByCurrentRegion(location)) {
            task.run();
            return;
        }
        JavaPlugin javaPlugin = JavaPlugin.getPlugin(MultiverseCore.class);
        if (!canWaitForSchedulerHop()) {
            scheduleAtLocation(javaPlugin, location, task);
            return;
        }
        hopToLocation(javaPlugin, location, () -> {
            task.run();
            return null;
        });
    }

    private static <T> T callAtLocation(
            @NotNull JavaPlugin javaPlugin,
            @NotNull Location location,
            @NotNull Supplier<T> action) {
        if (isOwnedByCurrentRegion(location) || !canWaitForSchedulerHop()) {
            return action.get();
        }
        return hopToLocation(javaPlugin, location, action);
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
        return awaitHop(future, "the global tick thread");
    }

    private static <T> T hopToLocation(
            @NotNull JavaPlugin javaPlugin,
            @NotNull Location location,
            @NotNull Supplier<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        scheduleAtLocation(javaPlugin, location, () -> {
            try {
                future.complete(action.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return awaitHop(future, "the region thread at " + location);
    }

    private static void scheduleAtLocation(
            @NotNull JavaPlugin javaPlugin,
            @NotNull Location location,
            @NotNull Runnable task) {
        Bukkit.getRegionScheduler().execute(javaPlugin, location, task);
    }

    private static <T> T awaitHop(@NotNull CompletableFuture<T> future, @NotNull String where) {
        try {
            return future.get(HOP_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timed out waiting for " + where, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for " + where, e);
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
