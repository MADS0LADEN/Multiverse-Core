package org.mvplugins.multiverse.core.utils.compatibility

import org.mvplugins.multiverse.core.TestWithMockBukkit
import org.mvplugins.multiverse.core.utils.PluginScheduler
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ServerPlatformTest : TestWithMockBukkit() {

    @Test
    fun `MockBukkit is not a regionized Folia or CanvasMC server`() {
        assertFalse(ServerPlatform.isRegionized())
        assertNotNull(ServerPlatform.getBrandName())
    }

    @Test
    fun `PluginScheduler can run a next-tick task on MockBukkit`() {
        val scheduler = serviceLocator.getActiveService(PluginScheduler::class.java)
        assertNotNull(scheduler)
        var ran = false
        scheduler.runNextTick { ran = true }
        server.scheduler.performOneTick()
        kotlin.test.assertTrue(ran)
    }
}
