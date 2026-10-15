package hero.flipper.agent

import com.flipperdevices.protobuf.CommandStatus
import com.flipperdevices.protobuf.Main
import com.flipperdevices.protobuf.system.PingResponse
import hero.flipper.kit.FlipperError
import hero.flipper.kit.FlipperRpcClient
import hero.flipper.kit.FlipperTransport
import hero.flipper.kit.FrameCodec
import hero.flipper.kit.FrameDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8

class RawCommandTests {
    @Test
    fun contentJsonBecomesMainAndTheResponseComesBack() = runTest {
        val transport = Loopback { request ->
            val ping = request.system_ping_request ?: return@Loopback emptyList()
            listOf(
                Main(
                    command_id = request.command_id,
                    command_status = CommandStatus.OK,
                    system_ping_response = PingResponse(data_ = ping.data_),
                ),
            )
        }
        val client = FlipperRpcClient(transport, 2.seconds)
        client.start()
        try {
            val json = RawCommands.exchange(
                client,
                """{"commandId":99,"content":{"systemPingRequest":{"data":"aGk="}}}""",
            )
            val sent = transport.requests.single()
            assertEquals(1, sent.command_id)
            assertEquals("hi", sent.system_ping_request?.data_?.utf8())
            assertTrue(json.contains("systemPingResponse"), json)
            val parsed = RawCommands.parse(json.lineSequence().first())
            assertEquals("hi".encodeUtf8(), parsed.system_ping_response?.data_)
        } finally {
            client.stop()
        }
    }

    @Test
    fun flatProtoJsonAlsoMapsOntoMain() = runTest {
        val transport = Loopback { request ->
            listOf(
                Main(
                    command_id = request.command_id,
                    command_status = CommandStatus.OK,
                    system_ping_response = PingResponse(data_ = request.system_ping_request!!.data_),
                ),
            )
        }
        val client = FlipperRpcClient(transport, 2.seconds)
        client.start()
        try {
            RawCommands.exchange(client, """{"systemPingRequest":{"data":"YQ=="}}""")
            assertEquals("a", transport.requests.single().system_ping_request?.data_?.utf8())
        } finally {
            client.stop()
        }
    }

    @Test
    fun missingContentNeverReachesTheDevice() = runTest {
        val transport = Loopback { emptyList() }
        val client = FlipperRpcClient(transport, 2.seconds)
        client.start()
        try {
            val error = assertFails { RawCommands.exchange(client, "{}") }
            assertTrue(error is FlipperError.Rpc)
            assertTrue(error.message!!.contains("content"))
            assertEquals(0, transport.requests.size)
            val huge = """{"content":{"systemPingRequest":{"data":"${"a".repeat(20_000)}"}}}"""
            val oversized = assertFails { RawCommands.exchange(client, huge) }
            assertTrue(oversized is FlipperError.Rpc)
            assertEquals(0, transport.requests.size)
        } finally {
            client.stop()
        }
    }
}

private class Loopback(private val respond: (Main) -> List<Main>) : FlipperTransport {
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()
    private val decoder = FrameDecoder()
    private val lock = Any()
    private val received = ArrayList<Main>()
    val requests: List<Main> get() = synchronized(lock) { received.toList() }

    override suspend fun send(data: ByteArray) {
        val messages = synchronized(lock) { decoder.append(data) }
        for (message in messages) {
            synchronized(lock) { received.add(message) }
            for (reply in respond(message)) channel.send(FrameCodec.encode(reply))
        }
    }

    override suspend fun close() {
        channel.close()
    }
}
