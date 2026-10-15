package hero.flipper.agent

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ToolSweepTests {
    private class EngagementBox {
        var state: EngagementState = EngagementState.inactive
    }

    private fun device() = FakeFlipper(
        mapOf(
            "/ext/nfc/Card.nfc" to "Filetype: Flipper NFC device",
            "/ext/lfrfid/Tag.rfid" to "Filetype: Flipper RFID key",
            "/ext/ibutton/Door.ibtn" to "Filetype: Flipper iButton key",
            "/ext/subghz/Garage.sub" to "Filetype: Flipper SubGhz Key File",
            "/ext/infrared/TV.ir" to "Filetype: IR signals file",
            "/ext/badusb/Hello.txt" to "STRING hello",
            "/ext/old.txt" to "old",
        ),
    )

    private fun network(): ScriptedHttp {
        val fap = byteArrayOf(0x7F, 0x45, 0x4C, 0x46) + ByteArray(20) { 7.toByte() }
        val http = ScriptedHttp()
        http.routes = listOf(
            Triple(
                "/application?",
                200,
                """[{"_id":"a1","alias":"analog_clock","category_id":"c1","current_version":{"_id":"v1","name":"Analog Clock","version":"1.4","short_description":"Shows a clock"}}]""".encodeToByteArray(),
            ),
            Triple("/build/compatible", 200, fap),
            Triple("/category", 200, """[{"_id":"c1","name":"Tools"}]""".encodeToByteArray()),
            Triple(
                "search/code",
                200,
                """{"items":[{"path":"remotes/tv.ir","repository":{"full_name":"a/b"},"html_url":"https://github.com/a/b/blob/main/remotes/tv.ir"}]}""".encodeToByteArray(),
            ),
            Triple("raw.githubusercontent.com", 200, "Filetype: IR signals file\nVersion: 1\n".encodeToByteArray()),
            Triple("releases/latest", 200, """{"tag_name":"mntm-012"}""".encodeToByteArray()),
        )
        return http
    }

    private fun executor(flipper: FakeFlipper, gate: ScriptedGate, audit: AuditSink, controls: FakeAppControls, engagement: EngagementBox, http: ScriptedHttp): ToolExecutor {
        val directory = File(System.getProperty("java.io.tmpdir"), "sweep-${System.nanoTime()}")
        return ToolExecutor(
            flipper = flipper,
            gate = gate,
            audit = audit,
            forge = PayloadForge(StubLLM("STRING hello\nENTER")),
            profileStore = DeviceProfileStore(directory),
            fapHub = FapHubClient(http),
            gitHub = GitHubClient(http),
            appControls = controls,
            firmwareChecker = FirmwareUpdateChecker(http),
            settings = { ApprovalSettings(engagement = engagement.state) },
            edition = FullEdition,
        )
    }

    @Test
    fun everyToolHasASample() {
        val specced = ToolCatalog.specs(FullEdition).map { it.name }.toSet()
        val sampled = samples.map { it.first }.toSet()
        assertEquals(emptySet(), specced - sampled)
        assertEquals(emptySet(), sampled - specced)
        val counts = samples.map { it.first }.groupingBy { it }.eachCount()
        val duplicated = counts.filter { it.value > 1 }
        assertEquals(mapOf("set_engagement_mode" to 2), duplicated)
    }

    @Test
    fun everySampleParsesWithASummaryAndARisk() {
        for ((name, args) in samples) {
            val invocation = parseCall(name, args, FullEdition)
            assertEquals(name, invocation.toolName)
            assertTrue(invocation.summary.isNotEmpty(), name)
            val risk = RiskAssessor.assess(invocation, FullEdition)
            assertTrue(risk.level != RiskLevel.Blocked, name)
            assertTrue(risk.reasons.isNotEmpty(), name)
        }
    }

    @Test
    fun everyToolRunsAndIsAudited() = runTest {
        val http = network()
        val flipper = device()
        val audit = InMemoryAuditLog()
        val controls = FakeAppControls()
        val engagement = EngagementBox()
        val executor = executor(flipper, ScriptedGate(true), audit, controls, engagement, http)
        for ((name, args) in samples) {
            val result = executor.execute(ToolCall(name, name, args))
            assertEquals(ToolResultKind.ok, result.kind, "$name: ${result.content}")
            if (name == "set_engagement_mode") engagement.state = controls.engagement
        }
        val records = audit.recent(100)
        assertEquals(samples.map { it.first }, records.map { it.tool })
        assertTrue(records.all { it.succeeded })
        for (expected in listOf(
            "transmit Sub-GHz /ext/subghz/Garage.sub ",
            "transmit Infrared /ext/infrared/TV.ir Power",
            "emulate NFC /ext/nfc/Card.nfc",
            "emulate 125 kHz RFID /ext/lfrfid/Tag.rfid",
            "emulate iButton /ext/ibutton/Door.ibtn",
            "badusb /ext/badusb/Hello.txt",
            "exitApp",
            "alert",
            "setName Hero",
            "write /ext/apps/Tools/analog_clock.fap",
        )) {
            assertTrue(flipper.calls.contains(expected), "missing $expected")
        }
        assertTrue(flipper.files["/ext/new.txt"]?.contentEquals("old".encodeToByteArray()) == true)
        assertNull(flipper.files["/ext/sweep/note.txt"])
        assertTrue(flipper.files["/ext/badusb/forged.txt"]?.contentEquals("STRING hello\nENTER".encodeToByteArray()) == true)
        assertNotNull(flipper.files["/ext/infrared/tv.ir"])
        assertEquals(listOf("down", "ok", "long_back", "ok"), flipper.presses)
        assertEquals("anthropic/claude-sonnet-4.5", controls.state.model)
        assertTrue(controls.state.readAloud)
        assertFalse(controls.state.autoConnect)
        assertEquals(1, controls.restarts)
    }

    @Test
    fun deniedToolsChangeNothing() = runTest {
        val http = network()
        val flipper = device()
        val before = flipper.files.mapValues { it.value.copyOf() }
        val controls = FakeAppControls()
        val gate = ScriptedGate(false)
        val executor = executor(flipper, gate, InMemoryAuditLog(), controls, EngagementBox(), http)
        val asked = ArrayList<String>()
        for ((name, args) in samples) {
            val count = gate.requests.size
            val result = executor.execute(ToolCall(name, name, args))
            if (gate.requests.size > count) {
                asked.add(name)
                assertEquals(ToolResultKind.denied, result.kind, name)
            }
        }
        assertEquals(before.keys, flipper.files.keys)
        for (path in before.keys) {
            assertTrue(before.getValue(path).contentEquals(flipper.files.getValue(path)), path)
        }
        for (forbidden in listOf("transmit", "emulate", "badusb", "startApp", "setName", "mkdir", "write", "delete", "rename")) {
            assertFalse(flipper.calls.any { it.startsWith(forbidden) }, forbidden)
        }
        assertTrue(flipper.presses.isEmpty())
        assertEquals(0, controls.restarts + controls.updatesStarted)
        for (physical in listOf(
            "transmit_subghz", "transmit_infrared", "emulate_nfc", "emulate_rfid", "emulate_ibutton",
            "load_badusb_script", "press_buttons", "install_firmware_update", "install_faphub_app",
        )) {
            assertTrue(asked.contains(physical), physical)
        }
    }

    companion object {
        val samples = listOf(
            "marauder" to """{"action":"list_ap"}""",
            "get_device_info" to "{}",
            "get_power_info" to "{}",
            "get_storage_info" to "{}",
            "list_directory" to """{"path":"/ext/nfc"}""",
            "read_file" to """{"path":"/ext/nfc/Card.nfc"}""",
            "create_directory" to """{"path":"/ext/sweep"}""",
            "write_file" to """{"path":"/ext/sweep/note.txt","content":"hello"}""",
            "rename" to """{"from":"/ext/old.txt","to":"/ext/new.txt"}""",
            "delete" to """{"path":"/ext/sweep/note.txt"}""",
            "launch_app" to """{"name":"Snake"}""",
            "transmit_subghz" to """{"path":"/ext/subghz/Garage.sub"}""",
            "transmit_infrared" to """{"path":"/ext/infrared/TV.ir","button":"Power"}""",
            "emulate_nfc" to """{"path":"/ext/nfc/Card.nfc"}""",
            "emulate_rfid" to """{"path":"/ext/lfrfid/Tag.rfid"}""",
            "emulate_ibutton" to """{"path":"/ext/ibutton/Door.ibtn"}""",
            "load_badusb_script" to """{"path":"/ext/badusb/Hello.txt"}""",
            "stop_app" to "{}",
            "alert_device" to "{}",
            "forge_payload" to """{"type":"badusb","description":"type hello","path":"/ext/badusb/forged.txt"}""",
            "search_faphub" to """{"query":"analog_clock"}""",
            "install_faphub_app" to """{"query":"analog_clock"}""",
            "github_search" to """{"query":"samsung tv","extension":"ir"}""",
            "download_resource" to """{"url":"https://raw.githubusercontent.com/a/b/main/tv.ir","path":"/ext/infrared/tv.ir"}""",
            "refresh_device_knowledge" to "{}",
            "set_device_name" to """{"name":"Hero"}""",
            "check_firmware" to "{}",
            "restart_device" to "{}",
            "get_audit_log" to """{"limit":5}""",
            "get_app_settings" to "{}",
            "set_read_aloud" to """{"enabled":true}""",
            "set_auto_connect" to """{"enabled":false}""",
            "set_yolo_mode" to """{"enabled":false}""",
            "set_yolo_asks_after_reading" to """{"enabled":true}""",
            "set_auto_approve_medium" to """{"enabled":false}""",
            "set_model" to """{"model":"anthropic/claude-sonnet-4.5"}""",
            "set_ai_provider" to """{"provider":"kimi"}""",
            "list_models" to "{}",
            "test_model_connection" to "{}",
            "install_firmware_update" to "{}",
            "firmware_update_status" to "{}",
            "cancel_firmware_update" to "{}",
            "look_at_screen" to "{}",
            "press_buttons" to """{"keys":["down","ok","long_back"],"look_after":false}""",
            "set_engagement_mode" to """{"enabled":true,"raw_rpc":true,"auto_badusb":true,"note":"sweep"}""",
            "generate_engagement_report" to "{}",
            "gpio" to """{"action":"configure","pin":"pb3","direction":"input","pull":"up"}""",
            "badusb_execute" to """{"path":"/ext/badusb/Hello.txt"}""",
            "rpc_raw" to """{"request":"{\"content\":{\"systemPingRequest\":{}}}"}""",
            "set_engagement_mode" to """{"enabled":false}""",
        )
    }
}
