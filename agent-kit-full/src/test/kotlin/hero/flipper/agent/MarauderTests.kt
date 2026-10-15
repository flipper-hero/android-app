package hero.flipper.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MarauderTests {
    @Test
    fun attackNeedsOneMatchingBoundedTarget() {
        for (args in listOf(
            """{"action":"attack","mode":"deauth"}""",
            """{"action":"attack","mode":"deauth","ap_index":100}""",
            """{"action":"attack","mode":"probe","ap_index":1}""",
            """{"action":"attack","mode":"probe"}""",
            """{"action":"attack","mode":"deauth","ap_index":0,"seconds":11}""",
            """{"action":"attack","mode":"beacon","ap_index":0}""",
        )) assertFailsWith<ToolError.BadArguments> { parseCall("marauder", args, FullEdition) }
        val attack = parseCall("marauder", """{"action":"attack","mode":"deauth","ap_index":2}""", FullEdition)
        assertEquals(RiskLevel.High, RiskAssessor.assess(attack, FullEdition).level)
    }

    @Test
    fun attacksAskEvenWithYoloAndEngagement() = runTest {
        val flipper = FakeFlipper()
        val gate = ScriptedGate(false)
        val executor = ToolExecutor(flipper, gate, InMemoryAuditLog(),
            settings = { ApprovalSettings(yolo = true, engagement = EngagementState(active = true)) },
            edition = FullEdition)
        val result = executor.execute(ToolCall("attack", "marauder",
            """{"action":"attack","mode":"deauth","ap_index":2,"seconds":3}"""))
        assertEquals(ToolResultKind.denied, result.kind)
        assertEquals(1, gate.requests.size)
        assertFalse(gate.requests.single().isPermissionChange)
        assertTrue(flipper.calls.isEmpty())
    }

    @Test
    fun outputIsFencedAsUntrusted() = runTest {
        val executor = ToolExecutor(FakeFlipper(), ScriptedGate(true), InMemoryAuditLog(), edition = FullEdition)
        val result = executor.execute(ToolCall("list", "marauder", """{"action":"list_ap"}"""))
        assertEquals(ToolResultKind.ok, result.kind)
        assertTrue(result.content.contains("Lab Wi-Fi"))
        assertTrue(result.content.contains("<<<FLIPPER_DATA"))
        assertTrue(executor.isTainted)
    }

    @Test
    fun removesTerminalControlSequences() {
        assertEquals("Lab Wi-Fi\nOK", MarauderBridge.clean("\u001B[31mLab Wi-Fi\u001B[0m\nOK".encodeToByteArray()))
    }
}
