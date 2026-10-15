package hero.flipper.agent

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class EngagementTests {
    private fun armed(autoApprovals: Boolean = true, rawRpc: Boolean = false, autoBadKb: Boolean = false) =
        EngagementState(
            active = true,
            profile = EngagementProfile(autoApprovals, rawRpc, autoBadKb),
            startedAt = Instant.now(),
        )

    private class Fixture(
        engagement: EngagementState = EngagementState.inactive,
        approve: Boolean = true,
        files: Map<String, String> = emptyMap(),
    ) {
        val flipper = FakeFlipper(files)
        val gate = ScriptedGate(approve)
        val audit = InMemoryAuditLog()
        val controls = FakeAppControls()
        val executor = ToolExecutor(
            flipper = flipper,
            gate = gate,
            audit = audit,
            appControls = controls,
            settings = { ApprovalSettings(engagement = engagement) },
            edition = FullEdition,
        )
    }

    @Test
    fun engagementAutoApprovalsSkipPromptsButBlockedStaysBlocked() = runTest {
        val fixture = Fixture(armed())
        val medium = fixture.executor.execute(ToolCall("1", "create_directory", """{"path":"/ext/e"}"""))
        assertFalse(medium.isError)
        val high = fixture.executor.execute(ToolCall("2", "transmit_subghz", """{"path":"/ext/subghz/g.sub"}"""))
        assertFalse(high.isError, high.content)
        val blocked = fixture.executor.execute(ToolCall("3", "write_file", """{"path":"/int/x","content":"x"}"""))
        assertEquals(ToolResultKind.blocked, blocked.kind)
        assertTrue(fixture.gate.requests.isEmpty())
        assertEquals(listOf(AuditDecision.engaged, AuditDecision.engaged, AuditDecision.blocked), fixture.audit.records.map { it.decision })
        assertTrue(fixture.flipper.calls.any { it.startsWith("transmit") })
    }

    @Test
    fun engagementWithoutAutoApprovalsStillAsks() = runTest {
        val fixture = Fixture(armed(autoApprovals = false))
        fixture.executor.execute(ToolCall("1", "create_directory", """{"path":"/ext/e"}"""))
        assertEquals(1, fixture.gate.requests.size)
    }

    @Test
    fun armingAlwaysNeedsConsentEvenUnderEveryYoloFlag() = runTest {
        val controls = FakeAppControls()
        val gate = ScriptedGate(false)
        val executor = ToolExecutor(
            FakeFlipper(), gate, InMemoryAuditLog(), appControls = controls,
            settings = { ApprovalSettings(autoApproveMedium = true, yolo = true, yoloAsksAfterUntrusted = false) },
            edition = FullEdition,
        )
        val result = executor.execute(ToolCall("1", "set_engagement_mode", """{"enabled":true,"raw_rpc":true}"""))
        assertEquals(ToolResultKind.denied, result.kind)
        assertEquals(1, gate.requests.size)
        assertTrue(gate.requests[0].isPermissionChange)
        assertFalse(controls.engagement.active)
    }

    @Test
    fun approvedArmingAppliesProfileAndDisarmingIsFree() = runTest {
        val fixture = Fixture()
        fixture.executor.execute(
            ToolCall("1", "set_engagement_mode", """{"enabled":true,"raw_rpc":true,"note":"client X physical"}"""),
        )
        assertTrue(fixture.controls.engagement.active)
        assertTrue(fixture.controls.engagement.profile.rawRpc)
        assertEquals("client X physical", fixture.controls.engagement.profile.note)
        assertEquals(1, fixture.gate.requests.size)
        fixture.executor.execute(ToolCall("2", "set_engagement_mode", """{"enabled":false}"""))
        assertEquals(1, fixture.gate.requests.size)
        assertFalse(fixture.controls.engagement.active)
    }

    @Test
    fun badUsbExecuteIsLockedUntilArmed() = runTest {
        val fixture = Fixture(files = mapOf("/ext/badusb/a.txt" to "REM x"))
        val locked = fixture.executor.execute(ToolCall("1", "badusb_execute", """{"path":"/ext/badusb/a.txt"}"""))
        assertTrue(locked.isError)
        assertTrue(locked.content.contains("engagement mode"))
        assertTrue(fixture.flipper.calls.isEmpty())
        assertTrue(fixture.gate.requests.isEmpty())
        assertEquals(listOf(AuditDecision.blocked), fixture.audit.records.map { it.decision })

        val asked = Fixture(armed(autoApprovals = false, autoBadKb = true), approve = false, files = mapOf("/ext/badusb/a.txt" to "REM x"))
        val result = asked.executor.execute(ToolCall("1", "badusb_execute", """{"path":"/ext/badusb/a.txt"}"""))
        assertEquals(ToolResultKind.denied, result.kind)
        assertTrue(asked.flipper.presses.isEmpty())
    }

    @Test
    fun badUsbExecuteRunsTheScriptWhenArmed() = runTest {
        val fixture = Fixture(armed(autoBadKb = true), files = mapOf("/ext/badusb/a.txt" to "STRING hi"))
        val result = fixture.executor.execute(ToolCall("1", "badusb_execute", """{"path":"/ext/badusb/a.txt"}"""))
        assertFalse(result.isError, result.content)
        assertEquals(1, result.images.size)
        assertTrue(fixture.flipper.calls.contains("badusb /ext/badusb/a.txt"))
        assertTrue(fixture.flipper.calls.contains("capture"))
        assertEquals(listOf("ok"), fixture.flipper.presses)
        assertEquals(listOf(AuditDecision.engaged), fixture.audit.records.map { it.decision })
    }

    @Test
    fun rawRpcIsLockedUntilArmedAndFencesOutput() = runTest {
        val locked = Fixture()
        val denied = locked.executor.execute(
            ToolCall("1", "rpc_raw", """{"request":"{\"content\":{\"systemPingRequest\":{}}}"}"""),
        )
        assertTrue(denied.isError)
        assertTrue(locked.flipper.calls.isEmpty())

        val fixture = Fixture(armed(rawRpc = true))
        val ok = fixture.executor.execute(
            ToolCall("1", "rpc_raw", """{"request":"{\"content\":{\"systemPingRequest\":{}}}"}"""),
        )
        assertFalse(ok.isError, ok.content)
        assertTrue(ok.content.contains("<<<FLIPPER_DATA"))
        assertEquals("""{"content":{"systemPingRequest":{}}}""", fixture.flipper.rawRequests.first())
        assertTrue(fixture.executor.isTainted)
    }

    @Test
    fun headerPins() = runTest {
        val fixture = Fixture(approve = false)
        val read = fixture.executor.execute(ToolCall("1", "gpio", """{"action":"read","pin":"pa4"}"""))
        assertFalse(read.isError)
        assertTrue(read.content.contains("high"))
        val write = fixture.executor.execute(ToolCall("2", "gpio", """{"action":"write","pin":"pa7","value":"1"}"""))
        assertEquals(ToolResultKind.denied, write.kind)
        assertEquals(1, fixture.gate.requests.size)
        val configure = fixture.executor.execute(
            ToolCall("3", "gpio", """{"action":"configure","pin":"pb3","direction":"input","pull":"up"}"""),
        )
        assertEquals(ToolResultKind.denied, configure.kind)
        assertEquals(listOf("gpio read pa4"), fixture.flipper.calls)
        assertFailsParse("gpio", """{"action":"read","pin":"zz9"}""")
        assertFailsParse("gpio", """{"action":"explode","pin":"pa4"}""")
    }

    @Test
    fun reportContainsTimelineAndDecisions() = runTest {
        val fixture = Fixture(armed(autoBadKb = true), files = mapOf("/ext/badusb/a.txt" to "STRING hi"))
        fixture.executor.execute(ToolCall("1", "get_device_info", "{}"))
        fixture.executor.execute(ToolCall("2", "badusb_execute", """{"path":"/ext/badusb/a.txt"}"""))
        val result = fixture.executor.execute(ToolCall("3", "generate_engagement_report", "{}"))
        assertFalse(result.isError, result.content)
        assertTrue(result.content.contains("# Engagement report"))
        assertTrue(result.content.contains("| get_device_info | low | auto | ok |"))
        assertTrue(result.content.contains("| badusb_execute | high | engaged | ok |"))
        assertTrue(result.content.contains("auto_approvals+auto_badusb"))
        val markdown = EngagementReport.markdown(fixture.audit.recent(10), EngagementState.inactive)
        assertFalse(markdown.contains("Engagement armed"))
        val hostile = AuditRecord(
            tool = "read_file",
            summary = "path | with pipe\nnewline",
            risk = RiskLevel.Low,
            decision = AuditDecision.auto,
            succeeded = true,
            detail = "",
        )
        val escaped = EngagementReport.markdown(listOf(hostile), EngagementState.inactive)
        assertFalse(escaped.lines().any { it.contains("path | with pipe") })
    }

    @Test
    fun settingsReportEngagementState() = runTest {
        val fixture = Fixture()
        fixture.controls.setEngagement(armed(rawRpc = true))
        val result = fixture.executor.execute(ToolCall("1", "get_app_settings", "{}"))
        assertTrue(result.content.contains("engagement_mode: armed (auto_approvals+raw_rpc)"), result.content)
        assertTrue(result.content.contains("api_key_stored: true"))
        assertFalse(result.content.contains("sk-"))
        val off = Fixture().executor.execute(ToolCall("1", "get_app_settings", "{}"))
        assertTrue(off.content.contains("engagement_mode: off"))
    }

    @Test
    fun promptReflectsArming() {
        val locked = FullEdition.systemPrompt("n1", "", EngagementState.inactive)
        assertTrue(locked.contains("not armed"))
        assertTrue(locked.contains("<<<FLIPPER_DATA"))
        val armedPrompt = FullEdition.systemPrompt(
            "n1",
            "",
            EngagementState(true, EngagementProfile(autoBadKb = true), Instant.now()),
        )
        assertTrue(armedPrompt.contains("ARMED"))
        assertTrue(armedPrompt.contains("badusb_execute"))
        assertFalse(armedPrompt.contains("Scope note"))
        val scoped = FullEdition.systemPrompt(
            "n1",
            "",
            EngagementState(true, EngagementProfile(note = "office lobby"), Instant.now()),
        )
        assertTrue(scoped.contains("office lobby"))
    }

    @Test
    fun argumentParsing() {
        val engage = parseCall("set_engagement_mode", """{"enabled":true,"raw_rpc":true,"note":"x"}""", FullEdition) as SetEngagementMode
        assertEquals("set_engagement_mode", engage.toolName)
        assertTrue(engage.requiresExplicitConsent)
        assertTrue(engage.profile.rawRpc)
        assertFalse(engage.profile.autoBadKb)
        val disarm = parseCall("set_engagement_mode", """{"enabled":false}""", FullEdition)
        assertFalse(disarm.requiresExplicitConsent)
        val report = parseCall("generate_engagement_report", "{}", FullEdition) as GenerateEngagementReport
        assertEquals(200, report.limit)
        val raw = parseCall("rpc_raw", """{"request":"{}"}""", FullEdition)
        assertEquals("Send the raw device command request", raw.summary)
    }

    private fun assertFailsParse(name: String, args: String) {
        var failed = false
        try {
            parseCall(name, args, FullEdition)
        } catch (_: ToolError) {
            failed = true
        }
        assertTrue(failed, name)
    }
}
