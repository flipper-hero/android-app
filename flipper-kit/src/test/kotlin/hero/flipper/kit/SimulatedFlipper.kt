package hero.flipper.kit

import com.flipperdevices.protobuf.CommandStatus
import com.flipperdevices.protobuf.Empty
import com.flipperdevices.protobuf.Main
import com.flipperdevices.protobuf.app.GetErrorResponse
import com.flipperdevices.protobuf.storage.File
import com.flipperdevices.protobuf.storage.InfoResponse
import com.flipperdevices.protobuf.storage.ListResponse
import com.flipperdevices.protobuf.storage.ReadResponse
import com.flipperdevices.protobuf.storage.StatResponse
import com.flipperdevices.protobuf.system.UpdateResponse
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okio.ByteString.Companion.toByteString
import java.util.zip.Deflater
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MockTransport : FlipperTransport {
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = channel.receiveAsFlow()
    private val decoder = FrameDecoder()
    private val lock = Any()
    private val received = ArrayList<Main>()
    var responder: (Main) -> List<Main> = { emptyList() }

    val requests: List<Main> get() = synchronized(lock) { received.toList() }

    override suspend fun send(data: ByteArray) {
        val messages = synchronized(lock) { decoder.append(data) }
        for (message in messages) {
            synchronized(lock) { received.add(message) }
            for (reply in responder(message)) {
                val frame = FrameCodec.encode(reply)
                var offset = 0
                while (offset < frame.size) {
                    val end = minOf(offset + 7, frame.size)
                    channel.send(frame.copyOfRange(offset, end))
                    offset = end
                }
            }
        }
    }

    override suspend fun close() {
        channel.close()
    }

    suspend fun inject(message: Main) {
        channel.send(FrameCodec.encode(message))
    }
}

fun reply(
    request: Main,
    hasNext: Boolean = false,
    status: CommandStatus = CommandStatus.OK,
    body: Main = Main(empty = Empty()),
): Main = body.copy(command_id = request.command_id, has_next = hasNext, command_status = status)

class SimulatedFlipper(files: Map<String, String> = emptyMap()) {
    val transport = MockTransport()
    private val lock = Any()
    private val stored = HashMap<String, ByteArray>()
    private val directories = HashSet<String>()
    private val writes = HashMap<Int, Pair<String, ByteArray>>()
    var failures: MutableMap<String, CommandStatus> = mutableMapOf()
    var updateResult: UpdateResponse.UpdateResultCode = UpdateResponse.UpdateResultCode.OK
    var appErrorText: String = ""

    val files: Map<String, ByteArray> get() = synchronized(lock) { stored.toMap() }
    val dirs: Set<String> get() = synchronized(lock) { directories.toSet() }
    val requests: List<Main> get() = transport.requests

    init {
        directories.add("/ext")
        for ((path, text) in files) {
            stored[path] = text.encodeToByteArray()
            var parent = path.substringBeforeLast('/', "")
            while (parent.length > 1) {
                directories.add(parent)
                parent = parent.substringBeforeLast('/', "")
            }
        }
        transport.responder = { request -> synchronized(lock) { respond(request) } }
    }

    suspend fun client(timeout: Duration = 2.seconds): FlipperRpcClient {
        val client = FlipperRpcClient(transport, timeout)
        client.start()
        return client
    }

    val log: List<String>
        get() = requests.mapNotNull { message ->
            when {
                message.storage_stat_request != null -> "stat ${message.storage_stat_request!!.path}"
                message.storage_list_request != null -> "list ${message.storage_list_request!!.path}"
                message.storage_read_request != null -> "read ${message.storage_read_request!!.path}"
                message.storage_write_request != null -> "write ${message.storage_write_request!!.path}"
                message.storage_mkdir_request != null -> "mkdir ${message.storage_mkdir_request!!.path}"
                message.storage_delete_request != null -> {
                    val request = message.storage_delete_request!!
                    "delete ${request.path}${if (request.recursive) " recursive" else ""}"
                }
                message.storage_rename_request != null -> {
                    val request = message.storage_rename_request!!
                    "rename ${request.old_path} ${request.new_path}"
                }
                message.storage_info_request != null -> "info ${message.storage_info_request!!.path}"
                message.app_start_request != null -> "appStart ${message.app_start_request!!.name} ${message.app_start_request!!.args}"
                message.app_load_file_request != null -> "appLoad ${message.app_load_file_request!!.path}"
                message.app_button_press_request != null -> "appPress ${message.app_button_press_request!!.args}"
                message.app_button_release_request != null -> "appRelease"
                message.app_exit_request != null -> "appExit"
                message.app_get_error_request != null -> "appError"
                message.system_reboot_request != null -> "reboot ${wireEnumLabel(message.system_reboot_request!!.mode.name)}"
                message.system_update_request != null -> "update ${message.system_update_request!!.update_manifest}"
                message.system_play_audiovisual_alert_request != null -> "alert"
                else -> null
            }
        }

    private fun status(kind: String) = failures[kind] ?: CommandStatus.OK

    private fun answer(request: Main, kind: String, body: Main? = null): List<Main> {
        val current = status(kind)
        return listOf(reply(request, status = current, body = if (current == CommandStatus.OK) body ?: Main(empty = Empty()) else Main(empty = Empty())))
    }

    private fun respond(request: Main): List<Main> {
        request.storage_stat_request?.let { stat ->
            stored[stat.path]?.let { data ->
                val file = File(type = File.FileType.FILE, size = data.size)
                return answer(request, "stat", Main(storage_stat_response = StatResponse(file_ = file)))
            }
            if (directories.contains(stat.path)) {
                val file = File(type = File.FileType.DIR)
                return answer(request, "stat", Main(storage_stat_response = StatResponse(file_ = file)))
            }
            return listOf(reply(request, status = CommandStatus.ERROR_STORAGE_NOT_EXIST))
        }
        request.storage_read_request?.let { read ->
            val data = stored[read.path] ?: return listOf(reply(request, status = CommandStatus.ERROR_STORAGE_NOT_EXIST))
            val parts = ArrayList<Main>()
            var offset = 0
            do {
                val end = minOf(offset + 512, data.size)
                val slice = if (end > offset) data.copyOfRange(offset, end) else ByteArray(0)
                val file = File(data_ = slice.toByteString())
                parts.add(reply(request, hasNext = end < data.size, body = Main(storage_read_response = ReadResponse(file_ = file))))
                offset = end
            } while (offset < data.size)
            return parts
        }
        request.storage_write_request?.let { write ->
            val previous = writes[request.command_id] ?: (write.path to ByteArray(0))
            val combined = previous.second + (write.file_?.data_?.toByteArray() ?: ByteArray(0))
            if (request.has_next) {
                writes[request.command_id] = write.path to combined
                return emptyList()
            }
            writes.remove(request.command_id)
            if (status("write") == CommandStatus.OK) stored[previous.first] = combined
            return answer(request, "write")
        }
        request.storage_mkdir_request?.let { mkdir ->
            if (directories.contains(mkdir.path)) return listOf(reply(request, status = CommandStatus.ERROR_STORAGE_EXIST))
            directories.add(mkdir.path)
            return answer(request, "mkdir")
        }
        request.storage_delete_request?.let { delete ->
            val prefix = delete.path + "/"
            stored.keys.filter { it == delete.path || (delete.recursive && it.startsWith(prefix)) }.forEach { stored.remove(it) }
            directories.remove(delete.path)
            return answer(request, "delete")
        }
        request.storage_rename_request?.let { rename ->
            val data = stored.remove(rename.old_path) ?: return listOf(reply(request, status = CommandStatus.ERROR_STORAGE_NOT_EXIST))
            stored[rename.new_path] = data
            return answer(request, "rename")
        }
        if (request.storage_info_request != null) {
            return answer(request, "info", Main(storage_info_response = InfoResponse(total_space = 1000L, free_space = 400L)))
        }
        request.storage_list_request?.let { list ->
            val prefix = list.path + "/"
            val filesOut = ArrayList<File>()
            for ((path, data) in stored) {
                if (!path.startsWith(prefix)) continue
                val rest = path.removePrefix(prefix)
                if (rest.contains('/')) continue
                filesOut.add(File(name = rest, size = data.size))
            }
            for (dir in directories) {
                if (!dir.startsWith(prefix)) continue
                val rest = dir.removePrefix(prefix)
                if (rest.isEmpty() || rest.contains('/')) continue
                filesOut.add(File(name = rest, type = File.FileType.DIR))
            }
            return answer(request, "list", Main(storage_list_response = ListResponse(file_ = filesOut)))
        }
        if (request.app_start_request != null) return answer(request, "appStart")
        if (request.app_load_file_request != null) return answer(request, "appLoad")
        if (request.app_button_press_request != null) return answer(request, "appPress")
        if (request.app_button_release_request != null) return answer(request, "appRelease")
        if (request.app_exit_request != null) return answer(request, "appExit")
        if (request.app_get_error_request != null) {
            val response = GetErrorResponse(code = if (appErrorText.isEmpty()) 0 else 3, text = appErrorText)
            return answer(request, "appError", Main(app_get_error_response = response))
        }
        if (request.system_reboot_request != null) return answer(request, "reboot")
        if (request.system_play_audiovisual_alert_request != null) return answer(request, "alert")
        if (request.system_update_request != null) {
            return answer(request, "update", Main(system_update_response = UpdateResponse(code = updateResult)))
        }
        return listOf(reply(request))
    }
}

/** Gzip a tar the way the iOS tests do: raw deflate between a fixed header and an 8-byte trailer. */
fun gzipRaw(data: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    deflater.setInput(data)
    deflater.finish()
    val body = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (!deflater.finished()) {
        val n = deflater.deflate(buf)
        if (n > 0) body.write(buf, 0, n)
    }
    deflater.end()
    val out = java.io.ByteArrayOutputStream()
    out.write(byteArrayOf(0x1F, 0x8B.toByte(), 8, 0, 0, 0, 0, 0, 0, 0xFF.toByte()))
    out.write(body.toByteArray())
    out.write(ByteArray(8))
    return out.toByteArray()
}

fun tarHeader(path: String, size: Int, directory: Boolean): ByteArray {
    val header = ByteArray(512)
    fun put(text: String, at: Int) {
        val bytes = text.encodeToByteArray()
        bytes.forEachIndexed { index, byte -> header[at + index] = byte }
    }
    put(path, 0)
    put(if (directory) "0000755" else "0000644", 100)
    put("0000000", 108)
    put("0000000", 116)
    put(String.format("%011o", size), 124)
    put("00000000000", 136)
    put("        ", 148)
    header[156] = if (directory) '5'.code.toByte() else '0'.code.toByte()
    put("ustar", 257)
    put("00", 263)
    val checksum = header.sumOf { it.toInt() and 0xFF }
    put(String.format("%06o", checksum), 148)
    header[154] = 0
    header[155] = 0x20
    return header
}

fun tar(entries: List<Pair<String, ByteArray?>>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for ((path, data) in entries) {
        out.write(tarHeader(path, data?.size ?: 0, data == null))
        if (data != null) {
            out.write(data)
            val pad = (512 - data.size % 512) % 512
            if (pad > 0) out.write(ByteArray(pad))
        }
    }
    out.write(ByteArray(1024))
    return out.toByteArray()
}
