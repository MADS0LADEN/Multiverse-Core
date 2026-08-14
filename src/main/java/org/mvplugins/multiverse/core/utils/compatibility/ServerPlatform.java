package org.mvplugins.multiverse.core.utils.compatibility;

import java.lang.reflect.Method;

import io.vavr.control.Try;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import org.mvplugins.multiverse.core.utils.ReflectHelper;

/**
 * Utility class for detecting server platform capabilities such as Folia-style regionized threading.
 */
@ApiStatus.AvailableSince("5.8")
public final class ServerPlatform {

    private static final boolean REGIONIZED = ReflectHelper.hasClass(
            "io.papermc.paper.threadedregions.RegionizedServer");

    private static final Try<Method> IS_GLOBAL_TICK_THREAD = ReflectHelper.tryGetClass(
                    "io.papermc.paper.threadedregions.RegionizedServer")
            .flatMap(clazz -> ReflectHelper.tryGetMethod(clazz, "isGlobalTickThread"));

    private static Boolean hasRegionScheduler;

    private static boolean detectRegionScheduler() {
        try {
            return Bukkit.getServer().getGlobalRegionScheduler() != null;
        } catch (NoSuchMethodError | AbstractMethodError e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns whether the server uses regionized multithreading (Folia or CanvasMC).
     *
     * @return true if regionized multithreading is active
     */
    @ApiStatus.AvailableSince("5.8")
    public static boolean isRegionized() {
        return REGIONIZED;
    }

    /**
     * Returns whether Paper region scheduler APIs are usable at runtime.
     *
     * @return true if region scheduler APIs work at runtime
     */
    @ApiStatus.AvailableSince("5.8")
    public static boolean hasRegionScheduler() {
        if (hasRegionScheduler == null) {
            hasRegionScheduler = detectRegionScheduler();
        }
        return hasRegionScheduler;
    }

    /**
     * Returns whether the current thread is Folia's global tick thread.
     *
     * <p>On non-regionized servers this is always true so callers can treat the
     * current thread as safe for world create and server-setting mutations.</p>
     *
     * @return true if world create / setPVP / gamerules are legal on this thread
     */
    @ApiStatus.AvailableSince("5.8")
    public static boolean isGlobalTickThread() {
        if (!REGIONIZED) {
            return true;
        }
        return IS_GLOBAL_TICK_THREAD
                .flatMap(ReflectHelper::tryInvokeStaticMethod)
                .filter(Boolean.class::isInstance)
                .map(Boolean.class::cast)
                .getOrElse(false);
    }

    /**
     * Returns the server brand name reported by Bukkit.
     *
     * @return the server brand name (e.g. Paper, Folia, Canvas)
     */
    @ApiStatus.AvailableSince("5.8")
    @NotNull
    public static String getBrandName() {
        return Bukkit.getName();
    }

    private ServerPlatform() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
