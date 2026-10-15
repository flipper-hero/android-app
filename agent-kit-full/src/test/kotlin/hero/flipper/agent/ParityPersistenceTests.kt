package hero.flipper.agent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ParityPersistenceTests {
    @Test
    fun auditSurvivesReopeningAndKeepsOrder() = runTest {
        val file = Files.createTempFile("flipper-audit", ".jsonl").toFile()
        try {
            val log = FileAuditLog(file)
            log.record(AuditRecord(tool = "read_file", summary = "Read", risk = RiskLevel.Low,
                decision = AuditDecision.auto, succeeded = true, detail = ""))
            log.record(AuditRecord(tool = "delete", summary = "Delete", risk = RiskLevel.High,
                decision = AuditDecision.denied, succeeded = false, detail = "denied"))

            val reopened = FileAuditLog(file).recent(2)
            assertEquals(listOf("read_file", "delete"), reopened.map { it.tool })
            assertEquals(AuditDecision.denied, reopened.last().decision)
            assertEquals(RiskLevel.High, reopened.last().risk)
            assertEquals(listOf("delete"), FileAuditLog(file).recent(1).map { it.tool })
        } finally {
            file.delete()
        }
    }

    @Test
    fun completionUsesValidatedProviderURL() = runTest {
        var requested = ""
        val http = object : HttpClient {
            override suspend fun request(url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
                requested = url
                return HttpResponse(200, """{"choices":[{"message":{"content":"ok"}}]}""".encodeToByteArray())
            }
        }
        val client = CompletingClient(http, "key", "model", AIProvider.Qwen,
            "https://dashscope-us.aliyuncs.com/compatible-mode/v1")
        client.complete(listOf(ChatMessage(ChatRole.user, "test")), emptyList())
        assertEquals("https://dashscope-us.aliyuncs.com/compatible-mode/v1/chat/completions", requested)
        assertFailsWith<ProviderError.InvalidEndpoint> {
            AIProvider.Qwen.validatedBaseURL("https://example.com/compatible-mode/v1")
        }
        assertTrue(requested.startsWith("https://"))
    }

    @Test
    fun inventoryIsCachedPerDevice() = runTest {
        val directory = Files.createTempDirectory("flipper-profiles").toFile()
        try {
            val first = FakeFlipper(mapOf("/ext/nfc/Card.nfc" to "saved"))
            val store = DeviceProfileStore(directory)
            val fresh = store.build(first)
            assertEquals("6C9A530226E18000", fresh.hardwareUID)
            assertEquals(fresh.folders, DeviceProfileStore(directory).cached(first.hardwareUID)?.folders)
            assertEquals(null, DeviceProfileStore(directory).cached("AAAAAAAAAAAAAAAA"))
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }
}
