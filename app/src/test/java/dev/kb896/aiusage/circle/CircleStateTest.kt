package dev.kb896.aiusage.circle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CircleStateTest {
    private val ready = CircleOption.State(requested = true, serviceEnabled = true, serviceBound = true,
        shizukuRunning = true, shizukuGranted = true, shellBound = true, launcherAvailable = true, listening = true)

    @Test fun preferenceOnlyTakesEffectWithBothServicesAndNoStandalone() {
        assertTrue(ready.active)
        assertFalse(ready.copy(listening = false).active)
        assertFalse(CircleOption.State().active)
        for (state in listOf(ready.copy(standaloneInstalled = true), ready.copy(serviceEnabled = false),
            ready.copy(serviceBound = false), ready.copy(shizukuRunning = false), ready.copy(shizukuGranted = false),
            ready.copy(shellBound = false), ready.copy(launcherAvailable = false))) {
            assertFalse(state.hint, state.available)
            assertFalse(state.hint, state.active)
        }
        assertTrue(ready.copy(requested = false).available)
        assertFalse(ready.copy(requested = false).active)
    }
}
