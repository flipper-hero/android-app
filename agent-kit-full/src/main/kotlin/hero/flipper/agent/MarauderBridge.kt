package hero.flipper.agent

import com.flipperdevices.protobuf.Main
import com.flipperdevices.protobuf.app.AppState
import com.flipperdevices.protobuf.app.DataExchangeRequest
import hero.flipper.kit.FlipperRpcClient
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import kotlin.time.Duration.Companion.seconds

/** RPC bridge for the allowlisted Marauder FAP. This class is absent from Play builds. */
class MarauderBridge(private val rpc: FlipperRpcClient) {
    suspend fun run(action: String, mode: String, seconds: Int, index: Int?): String {
        if (!active.tryLock()) throw IllegalStateException("Another Marauder operation is running")
        try {
            rpc.startApp("/ext/apps/GPIO/marauder_bridge.fap", "RPC")
            try {
                val started = withTimeoutOrNull(4_000) {
                    rpc.unsolicitedMessages.first { it.app_state_response?.state == AppState.APP_STARTED }
                }
                if (started == null) throw IllegalStateException("Marauder bridge did not start")
                return operation(action, mode, seconds, index)
            } finally {
                withContext(NonCancellable) { runCatching { rpc.exitApp() } }
            }
        } finally {
            active.unlock()
        }
    }

    private suspend fun operation(action: String, mode: String, seconds: Int, index: Int?): String = when (action) {
        "commands" -> collect("help", 2)
        "list_ap" -> collect("list -a", 2)
        "list_stations" -> collect("list -c", 2)
        "list_ssids" -> collect("list -s", 2)
        "scan_ap" -> {
            require(seconds in 1..30)
            val scan = collect("scanap", seconds, stop = true)
            limit(scan + "\n" + collect("list -a", 2))
        }
        "scan_stations" -> {
            require(seconds in 1..30)
            val scan = collect("scansta", seconds, stop = true)
            limit(scan + "\n" + collect("list -c", 2))
        }
        "sniff" -> {
            require(mode in setOf("beacon", "probe", "deauth", "pmkid") && seconds in 1..30)
            collect("sniff$mode", seconds, stop = true)
        }
        "attack" -> {
            require(mode in setOf("deauth", "probe", "beacon", "rickroll") && seconds in 1..10)
            if (mode == "deauth" || mode == "probe") {
                require(index != null && index in 0..99)
                val selected = collect("select ${if (mode == "deauth") "-a" else "-s"} $index", 2)
                if (selected.isEmpty() || selected.contains("invalid", ignoreCase = true)) {
                    throw IllegalStateException("Marauder did not accept the selected target")
                }
            } else require(index == null)
            val command = when (mode) {
                "deauth" -> "attack -t deauth"
                "probe" -> "attack -t probe"
                "beacon" -> "attack -t beacon -r"
                else -> "attack -t rickroll"
            }
            collect(command, seconds, stop = true)
        }
        else -> throw IllegalArgumentException("Unknown Marauder action")
    }

    private suspend fun send(command: String) {
        rpc.call(Main(app_data_exchange_request = DataExchangeRequest(data_ = command.encodeToByteArray().toByteString())), 10.seconds)
    }

    private suspend fun collect(command: String, seconds: Int, stop: Boolean = false): String = coroutineScope {
        val output = ByteArrayOutputStream()
        val reader = launch {
            rpc.unsolicitedMessages.collect { message ->
                val bytes = message.app_data_exchange_request?.data_?.toByteArray() ?: return@collect
                if (output.size() + bytes.size > 24 * 1024) throw IllegalStateException("Marauder output exceeds 24 KB")
                output.write(bytes)
            }
        }
        try {
            send(command)
            delay(seconds * 1_000L)
        } finally {
            if (stop) withContext(NonCancellable) {
                runCatching { send("stopscan") }
                delay(700)
            }
            reader.cancelAndJoin()
        }
        clean(output.toByteArray())
    }

    companion object {
        private val active = Mutex()

        fun clean(data: ByteArray): String {
            val out = StringBuilder()
            var escape = false
            var csi = false
            for (char in data.decodeToString()) {
                when {
                    escape -> { escape = false; if (char == '[') csi = true }
                    csi -> { if (char in '@'..'~') csi = false }
                    char == '\u001B' -> escape = true
                    char == '\n' || char == '\t' || !char.isISOControl() -> out.append(char)
                }
            }
            return out.toString().trim()
        }

        private fun limit(text: String): String = text.encodeToByteArray().take(24 * 1024).toByteArray().decodeToString()
    }
}
