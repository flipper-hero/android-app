package hero.flipper.agent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ParityPersistenceTests {
    @Test
    fun ai2342ChatBoundsOutputInsteadOfUsingTheGatewayDefault() {
        val wire = ChatCompletionsClient("test", "gpt-4.1-mini", AIProvider.Ai2342)
        val messages = listOf(ChatMessage(ChatRole.user, "Read battery"))
        val chat = org.json.JSONObject(wire.makeRequestBody(messages, ToolCatalog.specs(StoreEdition)).decodeToString())
        assertEquals(8192, chat.getInt("max_tokens"))
        assertTrue(chat.getJSONArray("tools").length() > 0)
        wire.connectionTest = true
        val probe = org.json.JSONObject(wire.makeRequestBody(messages, emptyList()).decodeToString())
        assertEquals(1024, probe.getInt("max_tokens"))
    }

    @Test
    fun providerCredentialsAreNotForwardedThroughRedirects() = runTest {
        val forwarded = java.util.concurrent.atomic.AtomicBoolean(false)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect") { request ->
            request.responseHeaders.add("Location", "/destination")
            request.sendResponseHeaders(302, -1)
            request.close()
        }
        server.createContext("/destination") { request ->
            forwarded.set(true)
            request.sendResponseHeaders(200, -1)
            request.close()
        }
        server.start()
        try {
            val response = UrlHttp().request("http://127.0.0.1:${server.address.port}/redirect", mapOf("Authorization" to "Bearer test-key"))
            assertEquals(302, response.status)
            assertEquals(false, forwarded.get())
        } finally { server.stop(0) }
    }

    @Test
    fun connectionTestsUseProviderSpecificBoundedRequests() = runTest {
        var sent = org.json.JSONObject()
        val http = object : HttpClient {
            override suspend fun request(url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
                sent = org.json.JSONObject(body!!.decodeToString())
                return HttpResponse(200, """{"choices":[{"message":{"content":"ok"}}]}""".encodeToByteArray())
            }
        }
        CompletingClient(http, "test", "MiniMax-M3", AIProvider.Minimax, connectionTest = true)
            .complete(listOf(ChatMessage(ChatRole.user, "Reply with the word ok.")), emptyList())
        assertEquals(1024, sent.getInt("max_tokens"))
        assertEquals("disabled", sent.getJSONObject("thinking").getString("type"))
        assertEquals(1, sent.getJSONArray("messages").length())
    }
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
