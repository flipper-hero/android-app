package hero.flipper.agent

import hero.flipper.kit.FlipperApp
import hero.flipper.kit.FlipperDirEntry
import hero.flipper.kit.FlipperKey
import hero.flipper.kit.FlipperScreenFrame
import hero.flipper.kit.FlipperStorageInfo
import kotlin.time.Duration

class FakeFlipper(files: Map<String, String> = emptyMap()) : FlipperControlling {
    val files = HashMap<String, ByteArray>()
    val dirs = HashSet<String>()
    val calls = ArrayList<String>()
    val presses = ArrayList<String>()
    val rawRequests = ArrayList<String>()
    var pendingName: String? = null
    var hardwareUID: String = "6C9A530226E18000"
    var screen = FlipperScreenFrame(ByteArray(1024))

    init {
        dirs.add("/ext")
        for ((path, content) in files) this.files[path] = content.encodeToByteArray()
    }

    fun seed(path: String, bytes: ByteArray) {
        files[path] = bytes
    }

    override suspend fun deviceInfo(): Map<String, String> {
        calls.add("deviceInfo")
        return mapOf("hardware_name" to "Laisear", "hardware_uid" to hardwareUID,
            "firmware_commit" to "d3f89dfe", "unrelated" to "x")
    }

    override suspend fun powerInfo(): Map<String, String> {
        calls.add("powerInfo")
        return mapOf("charge.level" to "87")
    }

    override suspend fun storageInfo(path: String): FlipperStorageInfo {
        calls.add("storageInfo")
        return FlipperStorageInfo(100u, 40u)
    }

    override suspend fun list(path: String): List<FlipperDirEntry> {
        calls.add("list $path")
        val prefix = if (path.endsWith("/")) path else "$path/"
        val entries = ArrayList<FlipperDirEntry>()
        val seen = HashSet<String>()
        for ((full, data) in files) {
            if (!full.startsWith(prefix)) continue
            val rest = full.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                val dir = rest.substring(0, slash)
                if (seen.add(dir)) entries.add(FlipperDirEntry(dir, true, 0))
            } else {
                entries.add(FlipperDirEntry(rest, false, data.size.toLong()))
            }
        }
        for (dir in dirs) {
            if (!dir.startsWith(prefix)) continue
            val rest = dir.removePrefix(prefix)
            if (rest.isNotEmpty() && !rest.contains("/") && seen.add(rest)) {
                entries.add(FlipperDirEntry(rest, true, 0))
            }
        }
        return entries.sortedBy { it.name }
    }

    override suspend fun stat(path: String): FlipperDirEntry {
        files[path]?.let { return FlipperDirEntry(path.substringAfterLast('/'), false, it.size.toLong()) }
        if (dirs.contains(path)) return FlipperDirEntry(path.substringAfterLast('/'), true, 0)
        throw IllegalStateException("not exist")
    }

    override suspend fun read(path: String, maxBytes: Int): ByteArray {
        calls.add("read $path")
        val data = files[path] ?: throw IllegalStateException("not exist")
        if (data.size > maxBytes) throw IllegalStateException("too large")
        return data
    }

    override suspend fun write(path: String, data: ByteArray) {
        calls.add("write $path")
        files[path] = data
    }

    override suspend fun makeDirectory(path: String) {
        calls.add("mkdir $path")
        dirs.add(path)
    }

    override suspend fun delete(path: String, recursive: Boolean) {
        calls.add("delete $path")
        files.keys.filter { it == path || (recursive && it.startsWith("$path/")) }.toList().forEach { files.remove(it) }
    }

    override suspend fun rename(from: String, to: String) {
        calls.add("rename $from $to")
        val data = files.remove(from) ?: return
        files[to] = data
    }

    override suspend fun startApp(name: String, args: String) {
        calls.add("startApp $name $args")
    }

    override suspend fun transmitOnce(app: FlipperApp, path: String, button: String, hold: Duration) {
        calls.add("transmit ${app.title} $path $button")
    }

    override suspend fun emulate(app: FlipperApp, path: String) {
        calls.add("emulate ${app.title} $path")
    }

    override suspend fun loadBadKeyboardScript(path: String) {
        calls.add("badusb $path")
    }

    override suspend fun exitApp() {
        calls.add("exitApp")
    }

    override suspend fun playAlert() {
        calls.add("alert")
    }

    override suspend fun setDeviceName(name: String) {
        calls.add("setName $name")
        pendingName = name
    }

    override suspend fun customDeviceName(): String? = pendingName

    override suspend fun captureScreen(timeout: Duration): FlipperScreenFrame {
        calls.add("capture")
        return screen
    }

    override suspend fun press(key: FlipperKey, long: Boolean) {
        presses.add(if (long) "long_${key.wire}" else key.wire)
    }

    override suspend fun headerSetMode(pin: String, output: Boolean, pullUp: Boolean?) {
        calls.add("gpio mode $pin ${if (output) "output" else "input"}")
    }

    override suspend fun headerRead(pin: String): Boolean {
        calls.add("gpio read $pin")
        return true
    }

    override suspend fun headerWrite(pin: String, level: Boolean) {
        calls.add("gpio write $pin ${if (level) "high" else "low"}")
    }

    override suspend fun sendRaw(request: String): String {
        calls.add("rawRPC")
        rawRequests.add(request)
        return """{"commandStatus":"OK"}"""
    }

    override suspend fun marauder(action: String, mode: String, seconds: Int, index: Int?): String {
        calls.add("marauder $action $mode $seconds $index")
        return "Lab Wi-Fi"
    }
}

class StubLLM(private val content: String) : LlmClient {
    override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage =
        ChatMessage(ChatRole.assistant, content)
}

class ScriptedGate(var answer: Boolean) : ApprovalGate {
    val requests = ArrayList<ApprovalRequest>()
    override suspend fun decide(request: ApprovalRequest): Boolean {
        requests.add(request)
        return answer
    }
}

class ScriptedLLM(script: List<ChatMessage>) : LlmClient {
    private val script = script.toMutableList()
    val seenMessages = ArrayList<List<ChatMessage>>()
    override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage {
        seenMessages.add(messages)
        return if (script.isEmpty()) ChatMessage(ChatRole.assistant, "done") else script.removeAt(0)
    }
}

fun toolCall(name: String, args: String = "{}", id: String = name): ChatMessage =
    ChatMessage(ChatRole.assistant, content = null, toolCalls = listOf(ToolCall(id, name, args)))

class FakeAppControls : AppControls {
    var state = AppSettingsSnapshot(
        yolo = false,
        yoloAsksAfterReading = true,
        autoApproveMedium = false,
        readAloud = false,
        autoConnect = true,
        model = "m/x",
        apiKeyStored = true,
    )
    var restarts = 0
    var connectionTests = 0
    var engagement = EngagementState.inactive
    var updatesStarted = 0
    var updatesCancelled = 0

    override suspend fun settings(): AppSettingsSnapshot = state
    override suspend fun setReadAloud(enabled: Boolean) { state.readAloud = enabled }
    override suspend fun setAutoConnect(enabled: Boolean) { state.autoConnect = enabled }
    override suspend fun setYolo(enabled: Boolean) { state.yolo = enabled }
    override suspend fun setYoloAsksAfterReading(enabled: Boolean) { state.yoloAsksAfterReading = enabled }
    override suspend fun setAutoApproveMedium(enabled: Boolean) { state.autoApproveMedium = enabled }
    override suspend fun setModel(model: String) { state.model = model }
    override suspend fun setProvider(provider: AIProvider, baseURL: String?) {
        state.provider = provider.wire
        state.apiBaseURL = baseURL ?: provider.baseURL
    }
    override suspend fun listModels(): List<AIModel> = listOf(AIModel("m/x", "Test model"))
    override suspend fun testConnection() { connectionTests += 1 }
    override suspend fun setEngagement(state: EngagementState) {
        engagement = state
        this.state.engagementActive = state.active
        this.state.engagementSummary = if (state.active) FullEdition.engagementSummary(state.profile) else "off"
    }
    override suspend fun restartDevice() { restarts += 1 }
    override suspend fun startFirmwareUpdate(): String {
        updatesStarted += 1
        return "Started installing mntm-012."
    }
    override suspend fun firmwareUpdateStatus(): String =
        if (updatesStarted > 0) "Uploading firmware.dfu, 40%" else "No update running."
    override suspend fun cancelFirmwareUpdate(): String {
        updatesCancelled += 1
        return "Cancelled the update."
    }
}
