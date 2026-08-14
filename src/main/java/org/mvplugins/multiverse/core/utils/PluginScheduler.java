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
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jvnet.hk2.annotations.Service;

import org.mvplugins.multiverse.core.MultiverseCore;
import org.mvplugins.multiverse.core.utils.compatibility.ServerPlatform;

/**
 * Folia/Canvas scheduler hops. Global tick is for world create/unload and CraftWorld
 * server settings. Region tick is for blocks/entities at a location. Never use the
 * async scheduler for those APIs. Never call {@code Bukkit.getCurrentTick()} to decide
 * whether to hop.
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
     * Runs a task on the next global tick.
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
     * Runs a task after the given delay in ticks on the global scheduler.
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
     * Runs a task asynchronously. Do not use this for world create, PVP, gamerules,
     * ticks-per-spawn, or block reads.
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
     * Runs a task on the global tick thread.
     *
     * <p>Use this for {@code Bukkit.createWorld()} / unload, {@code World.setPVP},
     * {@code setGameRule}, {@code setTicksPerSpawns}, {@code setDifficulty}, and other
     * CraftWorld server settings. Do not use this for {@code Block.getType}.</p>
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
     * Runs a value-returning task on the global tick thread.
     *
     * <p>Inline when already on the global tick or when the server is not regionized.
     * Otherwise hops with {@code GlobalRegionScheduler.execute} and waits. Callers on
     * the Server thread during {@code onEnable} must not use this; defer with
     * {@link #runDelayed(Runnable, long)} onto the global tick first.</p>
     *
     * @param action the action to run
     * @param <T> the result type
     * @return the action result
     */
    public <T> T callOnGlobalTick(@NotNull Supplier<T> action) {
        if (shouldRunOnGlobalTickInline()) {
            return action.get();
        }
        return hopToGlobalTick(plugin, action);
    }

    /**
     * Looks up this service and runs work on the global tick.
     *
     * @param task the task to run
     */
    public static void executeOnGlobalTick(@NotNull Runnable task) {
        if (shouldRunOnGlobalTickInline()) {
            task.run();
            return;
        }
        hopToGlobalTick(JavaPlugin.getPlugin(MultiverseCore.class), () -> {
            task.run();
            return null;
        });
    }

    /**
     * Returns whether the current thread owns the region for this location.
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
     * Returns whether this thread is a Folia/Canvas region tick thread.
     *
     * @return true if waiting for a scheduler hop is safe
     */
    public static boolean isOnRegionTickThread() {
        if (!ServerPlatform.isRegionized() || ServerPlatform.isGlobalTickThread()) {
            return false;
        }
        String name = Thread.currentThread().getName();
        return name.contains("Region Scheduler") || name.contains("Region Thread");
    }

    /**
     * Returns the world's spawn location, or the world origin if spawn cannot be read yet.
     *
     * @param world the world
     * @return a location in that world
     */
    public static @NotNull Location spawnLocationOrOrigin(@NotNull World world) {
        try {
            return world.getSpawnLocation();
        } catch (IllegalStateException e) {
            return new Location(world, 0, 0, 0);
        }
    }

    /**
     * Runs a task on the region that owns this location.
     *
     * <p>Use this for {@code Block.getType}, spawn-safety, and other block/chunk work.
     * Do not use this for {@code setPVP} / gamerules / ticks-per-spawn.</p>
     *
     * @param location the location whose region should run the task
     * @param task the task to run
     */
    public void runAtLocation(@NotNull Location location, @NotNull Runnable task) {
        executeAtLocation(plugin, location, task);
    }

    /**
     * Runs a value-returning task on the region that owns this location.
     *
     * @param location the location whose region should run the action
     * @param action the action to run
     * @param <T> the result type
     * @return the action result, or {@code null} when the work was deferred
     */
    public <T> T callAtLocation(@NotNull Location location, @NotNull Supplier<T> action) {
        return callAtLocation(plugin, location, action);
    }

    /**
     * Looks up this plugin and runs a task on the location's region.
     *
     * @param location the location whose region should run the task
     * @param task the task to run
     */
    public static void executeAtLocation(@NotNull Location location, @NotNull Runnable task) {
        if (isOwnedByCurrentRegion(location)) {
            task.run();
            return;
        }
        executeAtLocation(JavaPlugin.getPlugin(MultiverseCore.class), location, task);
    }

    private static void executeAtLocation(
            @NotNull JavaPlugin javaPlugin,
            @NotNull Location location,
            @NotNull Runnable task) {
        if (isOwnedByCurrentRegion(location)) {
            task.run();
            return;
        }
        if (isOnRegionTickThread()) {
            hopToLocation(javaPlugin, location, () -> {
                task.run();
                return null;
            });
            return;
        }
        scheduleAtLocation(javaPlugin, location, task);
    }

    private static <T> T callAtLocation(
            @NotNull JavaPlugin javaPlugin,
            @NotNull Location location,
            @NotNull Supplier<T> action) {
        if (isOwnedByCurrentRegion(location)) {
            return action.get();
        }
        if (isOnRegionTickThread()) {
            return hopToLocation(javaPlugin, location, action);
        }
        scheduleAtLocation(javaPlugin, location, action::get);
        return null;
    }

    private static boolean shouldRunOnGlobalTickInline() {
        return !ServerPlatform.isRegionized()
                || !ServerPlatform.hasRegionScheduler()
                || ServerPlatform.isGlobalTickThread();
    }

    private static <T> T hopToGlobalTick(@NotNull JavaPlugin javaPlugin, @NotNull Supplier<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(javaPlugin, () -> {
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
        try {
            Bukkit.getRegionScheduler().execute(javaPlugin, location, task);
        } catch (IllegalStateException e) {
            Bukkit.getGlobalRegionScheduler().runDelayed(javaPlugin, scheduled -> {
                try {
                    Bukkit.getRegionScheduler().execute(javaPlugin, location, task);
                } catch (IllegalStateException retryFailed) {
                    Bukkit.getGlobalRegionScheduler().runDelayed(
                            javaPlugin,
                            ignored -> scheduleAtLocation(javaPlugin, location, task),
                            20L);
                }
            }, 1L);
        }
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
