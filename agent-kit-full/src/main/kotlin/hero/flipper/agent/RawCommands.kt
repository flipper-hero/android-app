package hero.flipper.agent

import com.flipperdevices.protobuf.CommandStatus
import com.flipperdevices.protobuf.Main
import com.squareup.moshi.Moshi
import com.squareup.wire.WireJsonAdapterFactory
import hero.flipper.kit.FlipperError
import hero.flipper.kit.FlipperRpcClient
import org.json.JSONObject
import kotlin.time.Duration.Companion.seconds

/**
 * Turns one PB_Main JSON request into RPC calls.
 * Only the content oneof is taken from the model. Command id and framing stay on the client.
 */
object RawCommands {
    private const val maxBytes = 16 * 1024
    private const val maxParts = 50

    private val adapter by lazy {
        Moshi.Builder().add(WireJsonAdapterFactory()).build().adapter(Main::class.java)
    }

    suspend fun exchange(rpc: FlipperRpcClient, jsonRequest: String): String {
        val message = parse(jsonRequest)
        return format(rpc.call(message, 20.seconds))
    }

    internal fun parse(jsonRequest: String): Main {
        val trimmed = jsonRequest.trim()
        if (trimmed.encodeToByteArray().size > maxBytes) {
            throw FlipperError.Rpc("request larger than 16 KB")
        }
        val root = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            throw FlipperError.Rpc("request must set a 'content' field, e.g. {\"content\":{\"systemPingRequest\":{}}}")
        }
        val payload = if (root.has("content")) {
            val content = root.optJSONObject("content")
                ?: throw FlipperError.Rpc("request must set a 'content' field, e.g. {\"content\":{\"systemPingRequest\":{}}}")
            if (content.length() != 1) {
                throw FlipperError.Rpc("request must set exactly one content entry")
            }
            content.toString()
        } else {
            trimmed
        }
        val decoded = try {
            adapter.fromJson(payload)
        } catch (_: Exception) {
            null
        } ?: throw FlipperError.Rpc("request must set a 'content' field, e.g. {\"content\":{\"systemPingRequest\":{}}}")
        val body = decoded.copy(command_id = 0, command_status = CommandStatus.OK, has_next = false)
        if (body == Main()) {
            throw FlipperError.Rpc("request must set a 'content' field, e.g. {\"content\":{\"systemPingRequest\":{}}}")
        }
        return body
    }

    private fun format(parts: List<Main>): String {
        val out = StringBuilder()
        for ((index, part) in parts.take(maxParts).withIndex()) {
            if (index > 0) out.append('\n')
            val json = try {
                adapter.toJson(part)
            } catch (_: Exception) {
                """{"commandStatus":"ERROR_INTERNAL"}"""
            }
            out.append(json)
            if (out.toString().encodeToByteArray().size > maxBytes) {
                out.append("\n[truncated]")
                break
            }
        }
        return out.toString()
    }
}
