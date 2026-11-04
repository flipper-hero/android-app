package hero.flipper.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class UsageConsentTest {
    @Test fun analyticsCannotBeEnabledWithoutConsentEvenInYoloMode() = runTest {
        val controls = FakeAppControls()
        val gate = ScriptedGate(false)
        val executor = ToolExecutor(flipper = FakeFlipper(), gate = gate, audit = InMemoryAuditLog(),
            appControls = controls, settings = { ApprovalSettings(autoApproveMedium = true, yolo = true, yoloAsksAfterUntrusted = false) })
        val result = executor.execute(ToolCall("analytics", "set_usage_analytics", """{"enabled":true}"""))
        assertEquals(ToolResultKind.denied, result.kind)
        assertEquals(1, gate.requests.size)
        assertFalse(controls.state.usageAnalytics)
    }
}
