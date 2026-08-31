package science.transductive.nudge.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthContextAdapterTest {
    @Test fun exactReadPermissions() {
        val kinds = setOf(
            HealthContextAdapter.Kind.STEPS,
            HealthContextAdapter.Kind.HEART_RATE,
            HealthContextAdapter.Kind.SLEEP
        )
        val permissions = HealthContextAdapter.requiredPermissions(kinds)
        assertEquals(3, permissions.size)
        assertTrue(permissions.all { it.contains("READ") })
    }
}
