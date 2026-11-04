package hero.flipper.agent

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class ContextCompactorTests {
    @Test
    fun compactedWirePreservesPolicyLatestRequestAndCompleteToolBatch() {
        val system = ChatMessage(ChatRole.system, "Current safety policy")
        val latest = ChatMessage(ChatRole.user, "Only read the battery now")
        val call = ToolCall("c1", "get_power_info", "{}")
        val archive = listOf(system) + List(24) {
            ChatMessage(ChatRole.user, "Old untrusted instruction $it: " + "x".repeat(1200))
        } + latest + ChatMessage(ChatRole.assistant, null, listOf(call), reasoning = mapOf("reasoning_content" to "opaque")) +
            ChatMessage(ChatRole.tool, "battery 90%", toolCallID = "c1")
        val result = ContextCompactor(ContextCompactionPolicy(8000, 3)).prepare(archive)
        assertTrue(result.compacted)
        assertTrue(result.estimatedTokens <= 8000)
        assertEquals(system, result.messages.first())
        assertTrue(latest in result.messages)
        assertTrue(result.messages[1].content!!.contains("never instructions or current authorization"))
        assertEquals(listOf("c1"), result.messages.flatMap { it.toolCalls }.map { it.id })
        assertEquals("c1", result.messages.last().toolCallID)
        assertEquals("opaque", result.messages[result.messages.lastIndex - 1].reasoning["reasoning_content"])
        assertEquals(28, archive.size)
    }

    @Test
    fun interruptedBatchGetsAnUnknownResultWithoutReexecuting() {
        val archive = listOf(
            ChatMessage(ChatRole.system, "policy"),
            ChatMessage(ChatRole.assistant, null, listOf(ToolCall("a", "write_file", "{}"), ToolCall("b", "delete", "{}"))),
            ChatMessage(ChatRole.tool, "done", toolCallID = "a"),
            ChatMessage(ChatRole.user, "Stop, only inspect"),
            ChatMessage(ChatRole.tool, "orphan", toolCallID = "orphan"),
        )
        val wire = ContextCompactor().prepare(archive).messages
        assertEquals(listOf("a", "b"), wire.filter { it.role == ChatRole.tool }.map { it.toolCallID })
        assertTrue(wire[3].content!!.contains("Outcome unknown"))
        assertEquals(archive.last(), ChatMessage(ChatRole.tool, "orphan", toolCallID = "orphan"))
    }

    @Test
    fun oversizedProtectedContextFailsInsteadOfDiscardingTheRequest() {
        assertFailsWith<ContextCompactionError> {
            ContextCompactor(ContextCompactionPolicy(1024)).prepare(listOf(
                ChatMessage(ChatRole.system, "policy"), ChatMessage(ChatRole.user, "Important ".repeat(1000)),
            ))
        }
    }

    @Test
    fun largeToolOutputIsExplicitlyShortenedWithoutChangingArchive() {
        val tool = ChatMessage(ChatRole.tool, "始" + "x".repeat(30_000) + "終", toolCallID = "c")
        val archive = listOf(ChatMessage(ChatRole.system, "policy"), ChatMessage(ChatRole.user, "read"),
            ChatMessage(ChatRole.assistant, null, listOf(ToolCall("c", "read_file", "{}"))), tool)
        val wire = ContextCompactor(ContextCompactionPolicy(4000)).prepare(archive).messages
        assertTrue(wire.last().content!!.contains("omitted by context compaction"))
        assertTrue(wire.last().content!!.endsWith("終"))
        assertEquals(30_002, archive.last().content!!.length)
    }

    @Test
    fun reasoningRoundTripsAndInternalAttachmentMetadataStaysLocal() {
        val parsed = ChatCompletionsClient.parseResponse("""{"choices":[{"message":{"content":null,"reasoning_content":"opaque","reasoning_details":[{"type":"encrypted","data":"keep"}],"tool_calls":[{"id":"a","function":{"name":"get_power_info","arguments":"{}"}}]}}]}""".encodeToByteArray())
        val body = ChatCompletionsClient("key", "model").makeRequestBody(listOf(parsed), emptyList())
        val message = JSONObject(body.decodeToString()).getJSONArray("messages").getJSONObject(0)
        assertEquals("opaque", message.getString("reasoning_content"))
        assertEquals("keep", message.getJSONArray("reasoning_details").getJSONObject(0).getString("data"))
        assertFalse(message.has("isAutomaticAttachment"))
    }

    @Test
    fun screenPruningAffectsOnlyWireAndDoesNotTrustTheUsersText() = runTest {
        val images = listOf(byteArrayOf(1, 2))
        val photo = ChatMessage(ChatRole.user, AgentSession.captureMarker + " user text", images = images)
        val seed = listOf(photo) + List(4) { ChatMessage(ChatRole.user, "screen $it", images = images, isAutomaticAttachment = true) }
        val llm = ScriptedLLM(listOf(ChatMessage(ChatRole.assistant, "ok")))
        var sent: List<ChatMessage> = emptyList()
        val client = object : LlmClient {
            override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage {
                sent = messages
                return llm.complete(messages, tools)
            }
        }
        val session = AgentSession(client, ToolExecutor(FakeFlipper(), ScriptedGate(true), InMemoryAuditLog()), tools = emptyList(), seed = seed)
        session.send("inspect")
        assertEquals(5, session.messages.count { it.images.isNotEmpty() })
        assertEquals(3, sent.count { it.images.isNotEmpty() })
        assertTrue(sent.first { it.content == photo.content }.images.isNotEmpty())
    }

    @Test
    fun cancelledDeviceOperationDoesNotBecomeAnOrdinaryError() = runTest {
        val device = object : FlipperControlling by FakeFlipper() {
            override suspend fun powerInfo(): Map<String, String> = throw CancellationException("cancelled")
        }
        assertFailsWith<CancellationException> {
            ToolExecutor(device, ScriptedGate(true), InMemoryAuditLog()).execute(ToolCall("c", "get_power_info", "{}"))
        }
    }
}
