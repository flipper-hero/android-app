package hero.flipper.kit

import com.flipperdevices.protobuf.CommandStatus
import com.flipperdevices.protobuf.Empty
import com.flipperdevices.protobuf.Main
import com.flipperdevices.protobuf.app.AppButtonPressRequest
import com.flipperdevices.protobuf.app.AppButtonReleaseRequest
import com.flipperdevices.protobuf.app.AppExitRequest
import com.flipperdevices.protobuf.app.AppLoadFileRequest
import com.flipperdevices.protobuf.app.GetErrorRequest
import com.flipperdevices.protobuf.app.GetErrorResponse
import com.flipperdevices.protobuf.app.StartRequest
import com.flipperdevices.protobuf.gpio.GpioInputPull
import com.flipperdevices.protobuf.gpio.GpioPin
import com.flipperdevices.protobuf.gpio.GpioPinMode
import com.flipperdevices.protobuf.gpio.ReadPin
import com.flipperdevices.protobuf.gpio.SetInputPull
import com.flipperdevices.protobuf.gpio.SetPinMode
import com.flipperdevices.protobuf.gpio.WritePin
import com.flipperdevices.protobuf.screen.InputKey
import com.flipperdevices.protobuf.screen.InputType
import com.flipperdevices.protobuf.screen.ScreenFrame
import com.flipperdevices.protobuf.screen.ScreenOrientation
import com.flipperdevices.protobuf.screen.SendInputEventRequest
import com.flipperdevices.protobuf.screen.StartScreenStreamRequest
import com.flipperdevices.protobuf.screen.StopScreenStreamRequest
import com.flipperdevices.protobuf.storage.DeleteRequest
import com.flipperdevices.protobuf.storage.File
import com.flipperdevices.protobuf.storage.InfoRequest
import com.flipperdevices.protobuf.storage.ListRequest
import com.flipperdevices.protobuf.storage.MkdirRequest
import com.flipperdevices.protobuf.storage.ReadRequest
import com.flipperdevices.protobuf.storage.RenameRequest
import com.flipperdevices.protobuf.storage.StatRequest
import com.flipperdevices.protobuf.storage.WriteRequest
import com.flipperdevices.protobuf.system.DeviceInfoRequest
import com.flipperdevices.protobuf.system.PingRequest
import com.flipperdevices.protobuf.system.PlayAudiovisualAlertRequest
import com.flipperdevices.protobuf.system.PowerInfoRequest
import com.flipperdevices.protobuf.system.RebootRequest
import com.flipperdevices.protobuf.system.UpdateRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Sends PB.Main requests over a transport and matches responses by command id.
 * Calls are serialized. The reader keeps running while a call waits.
 */
class FlipperRpcClient(
    private val transport: FlipperTransport,
    private val defaultTimeout: Duration = 20.seconds,
) {
    private class Pending(
        val deferred: CompletableDeferred<List<Main>>,
        val parts: MutableList<Main> = mutableListOf(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private val state = Mutex()
    private val decoder = FrameDecoder()
    private var reader: Job? = null
    private var nextId = 1
    private val pending = HashMap<Int, Pending>()
    private val unsolicited = Channel<Main>(Channel.BUFFERED)
    val unsolicitedMessages = unsolicited.receiveAsFlow()
    private val closedChannel = Channel<Unit>(Channel.CONFLATED)
    val closed = closedChannel.receiveAsFlow()
    private var screenStreamUsers = 0

    fun start() {
        if (reader != null) return
        reader = scope.launch {
            try {
                transport.incoming.collect { chunk -> handle(chunk) }
            } finally {
                failAll(FlipperError.NotConnected())
                closedChannel.trySend(Unit)
            }
        }
    }

    suspend fun stop() {
        reader?.cancel()
        reader = null
        failAll(FlipperError.NotConnected())
        transport.close()
    }

    suspend fun call(message: Main, timeout: Duration? = null, progress: ((Int, Int) -> Unit)? = null): List<Main> =
        call(listOf(message), timeout, progress)

    suspend fun call(messages: List<Main>, timeout: Duration? = null, progress: ((Int, Int) -> Unit)? = null): List<Main> {
        require(messages.isNotEmpty())
        gate.lock()
        try {
            val id = takeId()
            val stamped = messages.mapIndexed { index, message ->
                message.copy(command_id = id, has_next = index < messages.lastIndex)
            }
            val deferred = CompletableDeferred<List<Main>>()
            state.withLock { pending[id] = Pending(deferred) }
            val limit = timeout ?: defaultTimeout
            val timer = scope.launch {
                delay(limit)
                finish(id, Result.failure(FlipperError.Timeout()))
            }
            scope.launch {
                try {
                    stamped.forEachIndexed { index, message ->
                        if (!state.withLock { pending.containsKey(id) }) return@launch
                        transport.send(FrameCodec.encode(message))
                        progress?.invoke(index + 1, stamped.size)
                    }
                } catch (error: Exception) {
                    finish(id, Result.failure(error))
                }
            }
            return try {
                deferred.await()
            } finally {
                timer.cancel()
            }
        } finally {
            gate.unlock()
        }
    }

    /** Sends independent requests back to back, then waits for every answer. */
    suspend fun callPipelined(messages: List<Main>, timeout: Duration? = null) {
        gate.lock()
        try {
            val limit = timeout ?: defaultTimeout
            val stamped = messages.map { it.copy(command_id = takeId(), has_next = false) }
            val ids = stamped.map { it.command_id }
            val deferreds = ids.map { id ->
                val deferred = CompletableDeferred<List<Main>>()
                state.withLock { pending[id] = Pending(deferred) }
                deferred
            }
            val timer = scope.launch {
                delay(limit)
                ids.forEach { finish(it, Result.failure(FlipperError.Timeout())) }
            }
            try {
                val bytes = stamped.fold(ByteArray(0)) { acc, message -> acc + FrameCodec.encode(message) }
                transport.send(bytes)
                deferreds.forEach { it.await() }
            } finally {
                timer.cancel()
                state.withLock {
                    ids.forEach { pending.remove(it) }
                }
            }
        } finally {
            gate.unlock()
        }
    }

    private fun takeId(): Int {
        val id = nextId
        nextId = if (nextId == Int.MAX_VALUE) 1 else nextId + 1
        return id
    }

    private suspend fun handle(chunk: ByteArray) {
        val messages = try {
            decoder.append(chunk)
        } catch (error: Exception) {
            failAll(error)
            return
        }
        messages.forEach { dispatch(it) }
    }

    private suspend fun dispatch(message: Main) {
        val entry = state.withLock { pending[message.command_id] } ?: run {
            unsolicited.trySend(message)
            return
        }
        if (message.command_status != CommandStatus.OK) {
            finish(message.command_id, Result.failure(FlipperError.Rpc(wireEnumLabel(message.command_status.name))))
            return
        }
        entry.parts.add(message)
        if (!message.has_next) finish(message.command_id, Result.success(entry.parts.toList()))
    }

    private suspend fun finish(id: Int, result: Result<List<Main>>) {
        val entry = state.withLock { pending.remove(id) } ?: return
        if (!entry.deferred.isActive) return
        result.fold(
            onSuccess = { entry.deferred.complete(it) },
            onFailure = { entry.deferred.completeExceptionally(it) },
        )
    }

    private suspend fun failAll(error: Throwable) {
        val ids = state.withLock { pending.keys.toList() }
        ids.forEach { finish(it, Result.failure(error)) }
    }

    suspend fun acquireScreenStream() {
        state.withLock { screenStreamUsers += 1 }
        val first = state.withLock { screenStreamUsers == 1 }
        if (!first) return
        try {
            call(Main(gui_start_screen_stream_request = StartScreenStreamRequest()))
        } catch (error: FlipperError.Rpc) {
            val status = error.status
            if (!status.contains("VirtualDisplayAlreadyStarted") && !status.contains("virtualDisplayAlreadyStarted")) {
                state.withLock { screenStreamUsers -= 1 }
                throw error
            }
        } catch (error: Exception) {
            state.withLock { screenStreamUsers -= 1 }
            throw error
        }
    }

    suspend fun releaseScreenStream() {
        val last = state.withLock {
            if (screenStreamUsers <= 0) return
            screenStreamUsers -= 1
            screenStreamUsers == 0
        }
        if (last) {
            try {
                call(Main(gui_stop_screen_stream_request = StopScreenStreamRequest()))
            } catch (_: Exception) {
            }
        }
    }

    suspend fun ping(payload: ByteArray = byteArrayOf(1, 2, 3, 4)) {
        val parts = call(Main(system_ping_request = PingRequest(data_ = payload.toByteString())))
        val response = parts.firstOrNull()?.system_ping_response
        if (response == null || response.data_.toByteArray().contentEquals(payload).not()) {
            throw FlipperError.UnexpectedResponse()
        }
    }

    suspend fun deviceInfo(): Map<String, String> {
        val parts = call(Main(system_device_info_request = DeviceInfoRequest()))
        return parts.mapNotNull { part ->
            part.system_device_info_response?.let { it.key to it.value_ }
        }.toMap()
    }

    suspend fun powerInfo(): Map<String, String> {
        val parts = call(Main(system_power_info_request = PowerInfoRequest()))
        return parts.mapNotNull { part ->
            part.system_power_info_response?.let { it.key to it.value_ }
        }.toMap()
    }

    suspend fun storageInfo(path: String = "/ext"): FlipperStorageInfo {
        val normalized = FlipperPath.normalize(path)
        val parts = call(Main(storage_info_request = InfoRequest(path = normalized)))
        val response = parts.firstOrNull()?.storage_info_response ?: throw FlipperError.UnexpectedResponse()
        return FlipperStorageInfo(response.total_space.toULong(), response.free_space.toULong())
    }

    suspend fun list(path: String): List<FlipperDirEntry> {
        val normalized = FlipperPath.normalize(path)
        val parts = call(Main(storage_list_request = ListRequest(path = normalized)))
        val entries = parts.flatMap { part ->
            part.storage_list_response?.file_.orEmpty().map { file ->
                FlipperDirEntry(file.name, file.type == File.FileType.DIR, file.size.toLong())
            }
        }
        return entries.sortedWith(compareByDescending<FlipperDirEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun stat(path: String): FlipperDirEntry {
        val normalized = FlipperPath.normalize(path)
        val parts = call(Main(storage_stat_request = StatRequest(path = normalized)))
        val file = parts.firstOrNull()?.storage_stat_response?.file_ ?: throw FlipperError.UnexpectedResponse()
        return FlipperDirEntry(FlipperPath.lastComponent(normalized), file.type == File.FileType.DIR, file.size.toLong())
    }

    suspend fun read(path: String, maxBytes: Int = FlipperLimits.defaultMaxReadBytes): ByteArray {
        val normalized = FlipperPath.normalize(path)
        val info = stat(normalized)
        if (info.isDirectory) throw FlipperError.InvalidPath("is a directory")
        if (info.size > maxBytes) throw FlipperError.Rpc("file is ${info.size} bytes, limit is $maxBytes")
        val parts = call(Main(storage_read_request = ReadRequest(path = normalized)), timeout = 60.seconds)
        val out = okio.Buffer()
        for (part in parts) {
            part.storage_read_response?.file_?.data_?.let { out.write(it) }
        }
        return out.readByteArray()
    }

    suspend fun write(path: String, data: ByteArray, progress: ((Int, Int) -> Unit)? = null) {
        val normalized = FlipperPath.normalize(path)
        val chunk = FlipperLimits.writeChunkSize
        val messages = ArrayList<Main>()
        var offset = 0
        do {
            val end = minOf(offset + chunk, data.size)
            val slice = if (end > offset) data.copyOfRange(offset, end) else ByteArray(0)
            messages.add(
                Main(
                    storage_write_request = WriteRequest(
                        path = normalized,
                        file_ = File(data_ = slice.toByteString()),
                    ),
                ),
            )
            offset = end
        } while (offset < data.size)
        val seconds = 60 + data.size / 2048
        val total = data.size
        call(messages, timeout = seconds.seconds) { sent, _ ->
            progress?.invoke(minOf(sent * chunk, total), total)
        }
    }

    suspend fun makeDirectories(path: String) {
        val normalized = FlipperPath.normalize(path)
        var current = ""
        for (component in normalized.split('/').filter { it.isNotEmpty() }) {
            current += "/$component"
            if (current.split('/').count { it.isNotEmpty() } <= 1) continue
            try {
                makeDirectory(current)
            } catch (error: FlipperError.Rpc) {
                if (!error.status.lowercase().contains("exist")) throw error
            }
        }
    }

    suspend fun makeDirectory(path: String) {
        val normalized = FlipperPath.normalize(path)
        call(Main(storage_mkdir_request = MkdirRequest(path = normalized)))
    }

    suspend fun delete(path: String, recursive: Boolean = false) {
        val normalized = FlipperPath.normalize(path)
        call(Main(storage_delete_request = DeleteRequest(path = normalized, recursive = recursive)))
    }

    suspend fun rename(from: String, to: String) {
        call(
            Main(
                storage_rename_request = RenameRequest(
                    old_path = FlipperPath.normalize(from),
                    new_path = FlipperPath.normalize(to),
                ),
            ),
        )
    }

    suspend fun startApp(name: String, args: String = "") {
        call(Main(app_start_request = StartRequest(name = name, args = args)))
    }

    private suspend fun openInRpcMode(app: FlipperApp, path: String) {
        val normalized = FlipperPath.normalize(path)
        if (!normalized.lowercase().endsWith(".${app.fileExtension}")) {
            throw FlipperError.InvalidPath("${app.title} expects a .${app.fileExtension} file")
        }
        stat(normalized)
        call(Main(app_start_request = StartRequest(name = app.title, args = "RPC")), timeout = 30.seconds)
        try {
            call(Main(app_load_file_request = AppLoadFileRequest(path = normalized)), timeout = 30.seconds)
        } catch (error: Exception) {
            try { exitApp() } catch (_: Exception) {}
            throw error
        }
    }

    suspend fun exitApp() {
        call(Main(app_exit_request = AppExitRequest()), timeout = 15.seconds)
    }

    suspend fun emulate(app: FlipperApp, path: String) {
        openInRpcMode(app, path)
    }

    suspend fun transmitOnce(app: FlipperApp, path: String, button: String = "", hold: Duration = 400.milliseconds) {
        openInRpcMode(app, path)
        try {
            call(Main(app_button_press_request = AppButtonPressRequest(args = button)), timeout = 20.seconds)
            delay(hold)
            call(Main(app_button_release_request = AppButtonReleaseRequest()), timeout = 20.seconds)
        } catch (error: Exception) {
            try { exitApp() } catch (_: Exception) {}
            throw error
        }
        exitApp()
    }

    suspend fun loadBadKeyboardScript(path: String) {
        val normalized = FlipperPath.normalize(path)
        stat(normalized)
        call(Main(app_start_request = StartRequest(name = FlipperApp.BadKeyboard.title, args = normalized)), timeout = 30.seconds)
    }

    suspend fun appError(): String? {
        val parts = call(Main(app_get_error_request = GetErrorRequest()))
        val response: GetErrorResponse = parts.firstOrNull()?.app_get_error_response ?: return null
        if (response.code == 0) return null
        return response.text.ifEmpty { "error code ${response.code}" }
    }

    suspend fun playAlert() {
        call(Main(system_play_audiovisual_alert_request = PlayAudiovisualAlertRequest()))
    }

    suspend fun reboot() = reboot(RebootRequest.RebootMode.OS)

    suspend fun reboot(mode: RebootRequest.RebootMode) {
        try {
            call(Main(system_reboot_request = RebootRequest(mode = mode)), timeout = 5.seconds)
        } catch (_: FlipperError.Timeout) {
        } catch (_: FlipperError.NotConnected) {
        }
    }

    suspend fun requestUpdate(manifestPath: String): FlipperUpdateResult {
        val normalized = FlipperPath.normalize(manifestPath)
        val parts = call(Main(system_update_request = UpdateRequest(update_manifest = normalized)), timeout = 60.seconds)
        val response = parts.firstOrNull()?.system_update_response ?: throw FlipperError.UnexpectedResponse()
        return if (response.code == com.flipperdevices.protobuf.system.UpdateResponse.UpdateResultCode.OK) {
            FlipperUpdateResult.Ok
        } else {
            FlipperUpdateResult.Rejected(wireEnumLabel(response.code.name))
        }
    }

    suspend fun rebootIntoUpdater() = reboot(RebootRequest.RebootMode.UPDATE)

    suspend fun captureScreen(timeout: Duration = 5.seconds): FlipperScreenFrame {
        val frames = Channel<FlipperScreenFrame>(Channel.CONFLATED)
        val job = scope.launch {
            unsolicitedMessages.collect { message ->
                message.gui_screen_frame?.let { raw ->
                    frameFrom(raw)?.let { frames.trySend(it) }
                }
            }
        }
        try {
            acquireScreenStream()
            val frame = withTimeoutOrNull(timeout) { frames.receive() } ?: throw FlipperError.Timeout()
            return frame
        } finally {
            job.cancel()
            releaseScreenStream()
        }
    }

    suspend fun press(key: FlipperKey, long: Boolean = false) {
        val type = if (long) InputType.LONG else InputType.SHORT
        val events = listOf(InputType.PRESS, type, InputType.RELEASE).map { event ->
            Main(gui_send_input_event_request = SendInputEventRequest(key = key.toProto(), type = event))
        }
        callPipelined(events)
    }

    suspend fun headerSetMode(pin: FlipperGpioPin, output: Boolean, pullUp: Boolean? = null) {
        val proto = pin.toProto()
        if (output) {
            call(Main(gpio_set_pin_mode = SetPinMode(pin = proto, mode = GpioPinMode.OUTPUT)))
        } else {
            call(Main(gpio_set_pin_mode = SetPinMode(pin = proto, mode = GpioPinMode.INPUT)))
            val pull = when (pullUp) {
                true -> GpioInputPull.UP
                false -> GpioInputPull.DOWN
                null -> GpioInputPull.NO
            }
            call(Main(gpio_set_input_pull = SetInputPull(pin = proto, pull_mode = pull)))
        }
    }

    suspend fun headerRead(pin: FlipperGpioPin): Boolean {
        val parts = call(Main(gpio_read_pin = ReadPin(pin = pin.toProto())))
        val response = parts.firstOrNull()?.gpio_read_pin_response ?: throw FlipperError.UnexpectedResponse()
        return response.value_ != 0
    }

    suspend fun headerWrite(pin: FlipperGpioPin, level: Boolean) {
        call(Main(gpio_write_pin = WritePin(pin = pin.toProto(), value_ = if (level) 1 else 0)))
    }

    suspend fun customDeviceName(): String? {
        val data = try {
            read(FlipperName.path, maxBytes = 1024)
        } catch (_: Exception) {
            return null
        }
        return FlipperName.parse(data.toString(Charsets.UTF_8))
    }

    suspend fun setDeviceName(name: String) {
        val valid = FlipperName.validate(name)
        try {
            makeDirectory("/ext/dolphin")
        } catch (_: FlipperError.Rpc) {
        }
        write(FlipperName.path, FlipperName.fileContents(valid).encodeToByteArray())
    }

    private fun frameFrom(raw: ScreenFrame): FlipperScreenFrame? {
        if (raw.data_.size < FlipperScreenFrame.width * FlipperScreenFrame.height / 8) return null
        val orientation = when (raw.orientation) {
            ScreenOrientation.HORIZONTAL_FLIP -> FlipperScreenFrame.Orientation.Flipped
            ScreenOrientation.VERTICAL -> FlipperScreenFrame.Orientation.Vertical
            ScreenOrientation.VERTICAL_FLIP -> FlipperScreenFrame.Orientation.VerticalFlipped
            else -> FlipperScreenFrame.Orientation.Normal
        }
        return FlipperScreenFrame(raw.data_.toByteArray(), orientation)
    }

    private fun FlipperKey.toProto(): InputKey = when (this) {
        FlipperKey.Up -> InputKey.UP
        FlipperKey.Down -> InputKey.DOWN
        FlipperKey.Left -> InputKey.LEFT
        FlipperKey.Right -> InputKey.RIGHT
        FlipperKey.Ok -> InputKey.OK
        FlipperKey.Back -> InputKey.BACK
    }

    private fun FlipperGpioPin.toProto(): GpioPin = when (this) {
        FlipperGpioPin.Pc0 -> GpioPin.PC0
        FlipperGpioPin.Pc1 -> GpioPin.PC1
        FlipperGpioPin.Pc3 -> GpioPin.PC3
        FlipperGpioPin.Pb2 -> GpioPin.PB2
        FlipperGpioPin.Pb3 -> GpioPin.PB3
        FlipperGpioPin.Pa4 -> GpioPin.PA4
        FlipperGpioPin.Pa6 -> GpioPin.PA6
        FlipperGpioPin.Pa7 -> GpioPin.PA7
    }
}
