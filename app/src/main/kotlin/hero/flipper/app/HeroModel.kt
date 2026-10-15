package hero.flipper.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import hero.flipper.agent.AIModel
import hero.flipper.agent.AIProvider
import hero.flipper.agent.AgentEvent
import hero.flipper.agent.AgentSession
import hero.flipper.agent.AppControls
import hero.flipper.agent.AppSettingsSnapshot
import hero.flipper.agent.ApprovalGate
import hero.flipper.agent.ApprovalRequest
import hero.flipper.agent.ApprovalSettings
import hero.flipper.agent.AuditDecision
import hero.flipper.agent.AuditRecord
import hero.flipper.agent.AuditSink
import hero.flipper.agent.ChatMessage
import hero.flipper.agent.ChatRole
import hero.flipper.agent.CompletingClient
import hero.flipper.agent.DeviceProfileStore
import hero.flipper.agent.DeviceProfile
import hero.flipper.agent.EngagementState
import hero.flipper.agent.FapHubClient
import hero.flipper.agent.FileAuditLog
import hero.flipper.agent.FirmwareCatalog
import hero.flipper.agent.FirmwareUpdateChecker
import hero.flipper.agent.GitHubClient
import hero.flipper.agent.LlmClient
import hero.flipper.agent.PayloadForge
import hero.flipper.agent.ProviderError
import hero.flipper.agent.ToolExecutor
import hero.flipper.agent.ToolCall
import hero.flipper.agent.ToolResultKind
import hero.flipper.agent.ToolSpec
import hero.flipper.agent.UrlHttp
import hero.flipper.app.nav.HeroNav
import hero.flipper.app.nav.HeroSection
import hero.flipper.kit.FlipperDirEntry
import hero.flipper.kit.FlipperError
import hero.flipper.kit.FlipperKey
import hero.flipper.kit.FlipperPath
import hero.flipper.kit.FlipperRpcClient
import hero.flipper.kit.FlipperScreenFrame
import hero.flipper.kit.FlipperUpdateResult
import hero.flipper.kit.TarGz
import hero.flipper.kit.FirmwareUpdatePackage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class HeroShortcut { Ask, Battery, Emulate, Send, Stop }

data class Transcript(
    val kind: String,
    val text: String,
    val tool: String = "",
    val status: String = "",
    val photo: Boolean = false,
)

class HeroModel(private val context: Context) : AppControls {
    val nav = HeroNav()
    private var revisionState by mutableIntStateOf(0)
    val revision: Int get() = revisionState
    fun touch() { revisionState++ }

    var connection by mutableStateOf("Not connected")
    var connected by mutableStateOf(false)
    var scanning by mutableStateOf(false)
    var deviceTitle by mutableStateOf("Not connected")
    var firmwareLine by mutableStateOf("unknown")
    var firmwareNotice by mutableStateOf<String?>(null)
    var profile by mutableStateOf<DeviceProfile?>(null)
    var batterySummary by mutableStateOf("unknown")
    var batteryFraction by mutableStateOf<Float?>(null)
    var sdSummary by mutableStateOf("unknown")
    var sdFraction by mutableStateOf<Float?>(null)
    var statusMessage by mutableStateOf<String?>(null)
    val found = mutableStateListOf<FoundFlipper>()

    var filePath by mutableStateOf("/ext")
    val fileEntries = mutableStateListOf<FlipperDirEntry>()
    var fileSpace by mutableStateOf("")
    var fileError by mutableStateOf<String?>(null)
    var fileLoading by mutableStateOf(false)
    var previewTitle by mutableStateOf("")
    var previewText by mutableStateOf("")

    val transcript = mutableStateListOf<Transcript>()
    var draft by mutableStateOf("")
    var busy by mutableStateOf(false)
    var photoAttached by mutableStateOf(false)
    var screenBitmap by mutableStateOf<Bitmap?>(null)
    var screenLive by mutableStateOf(false)
    var remoteError by mutableStateOf<String?>(null)

    var yolo by mutableStateOf(false)
    var yoloAsksAfterReading by mutableStateOf(true)
    var autoApproveMedium by mutableStateOf(false)
    var autoConnect by mutableStateOf(true)
    var readAloud by mutableStateOf(false)
    var modelId by mutableStateOf("anthropic/claude-sonnet-4.5")
    var provider by mutableStateOf("openrouter")
    var apiBaseURL by mutableStateOf("https://openrouter.ai/api/v1")
    val availableModels = mutableStateListOf<AIModel>()
    var modelListStatus by mutableStateOf<String?>(null)
    var keyStored by mutableStateOf(false)
    var keyDraft by mutableStateOf("")
    var keySaving by mutableStateOf(false)
    var keyStatus by mutableStateOf<String?>(null)
    var engagement by mutableStateOf(EngagementState())
    var firmwareStatus by mutableStateOf("idle")
    val firmwareJobRunning: Boolean get() = firmwareJob?.isActive == true
    var activeOperation by mutableStateOf<String?>(null)
    var renameDraft by mutableStateOf("")
    val auditLines = mutableStateListOf<String>()
    var pending by mutableStateOf<ApprovalRequest?>(null)
    var shortcutKind by mutableStateOf<HeroShortcut?>(null)
    val shortcutFiles = mutableStateListOf<String>()
    var shortcutStatus by mutableStateOf<String?>(null)
    var shortcutButton by mutableStateOf("")

    var requestPermissions: (() -> Unit)? = null
    var openCamera: (() -> Unit)? = null
    var voiceContext: Context? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = HeroPrefs(context)
    private val secrets: ProviderSecrets? = try {
        ProviderSecrets(context)
    } catch (_: Exception) {
        null
    }
    private val http = UrlHttp()
    private val pipe = ActiveFlipper()
    private val fileAudit = FileAuditLog(File(context.filesDir, "audit.jsonl"))
    private val profileStore = DeviceProfileStore(File(context.filesDir, "profiles"))
    private val audit = object : AuditSink {
        override suspend fun record(record: AuditRecord) {
            fileAudit.record(record)
            withContext(Dispatchers.Main) {
                auditLines.add(0, "${record.tool} ${record.decision.name} ${record.summary}")
                while (auditLines.size > 50) auditLines.removeAt(auditLines.lastIndex)
            }
        }
        override suspend fun recent(limit: Int) = fileAudit.recent(limit)
    }
    suspend fun auditRecords(limit: Int): List<AuditRecord> = audit.recent(limit)
    private var approvalWaiter: CompletableDeferred<Boolean>? = null
    private val gate = ApprovalGate { request ->
        val waiter = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main.immediate) {
            pending = request
            nav.showApproval()
            approvalWaiter = waiter
            touch()
        }
        waiter.await()
    }
    private val llm = object : LlmClient {
        override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage {
            val key = secrets?.read(provider) ?: throw ProviderError.MissingKey()
            if (modelId.isBlank()) throw ProviderError.MissingModel()
            val chosen = AIProvider.from(provider) ?: AIProvider.OpenRouter
            return CompletingClient(http, key, modelId, chosen, apiBaseURL).complete(messages, tools)
        }
    }
    private var session: AgentSession? = null
    private var rpc: FlipperRpcClient? = null
    private var transport: BleSerialTransport? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: FlipperScanCallback? = null
    private var screenJob: Job? = null
    private var firmwareJob: Job? = null
    @Volatile private var firmwareCancel = false
    private var lastDeviceAddress = ""
    private var deviceUID = ""
    private var connecting = false
    private var voice: VoiceInput? = null
    private val speaker = ReadAloud(context)
    private var loadedPrefs = false

    init {
        scope.launch { restore() }
    }

    fun resolveApproval(allow: Boolean) {
        if (allow) nav.approveHeld() else nav.denyApproval()
        pending = null
        approvalWaiter?.complete(allow)
        approvalWaiter = null
        touch()
    }

    fun disconnect() {
        drop(reconnect = false)
    }

    fun cancelFirmware() {
        firmwareCancel = true
        firmwareJob?.cancel()
        firmwareStatus = "Cancelled"
        refreshForeground()
        touch()
    }

    fun disarm() {
        engagement = EngagementState()
        session?.updateEngagement(engagement)
        auditEngagementChange(this, false, "")
        touch()
        refreshForeground()
    }

    fun arm(state: EngagementState, auditChange: Boolean = true) {
        if (state.active && !connected) {
            statusMessage = "Connect to a Flipper before arming engagement mode."
            return
        }
        engagement = state
        session?.updateEngagement(state)
        if (auditChange) auditEngagementChange(this, true, state.profile.note)
        touch()
        refreshForeground()
    }

    internal fun recordEngagementChange(tool: String, summary: String, high: Boolean, note: String) {
        scope.launch {
            audit.record(AuditRecord(tool = tool, summary = summary,
                risk = if (high) hero.flipper.agent.RiskLevel.High else hero.flipper.agent.RiskLevel.Low,
                decision = AuditDecision.approved, succeeded = true, detail = note))
        }
    }

    @SuppressLint("MissingPermission")
    fun scan() {
        if (!permission(android.Manifest.permission.BLUETOOTH_SCAN) || !permission(android.Manifest.permission.BLUETOOTH_CONNECT)) {
            statusMessage = "Bluetooth permission is required"
            requestPermissions?.invoke()
            touch()
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            statusMessage = "Turn Bluetooth on"
            touch()
            return
        }
        stopScan()
        found.clear()
        scanning = true
        statusMessage = null
        val callback = FlipperScanCallback { device ->
            scope.launch {
                if (found.none { it.device.address == device.device.address }) found.add(device)
                if (autoConnect && lastDeviceAddress.isNotEmpty() && device.device.address == lastDeviceAddress && !connected && !connecting) {
                    connect(device)
                }
            }
        }
        scanCallback = callback
        scanner = adapter.bluetoothLeScanner
        scanner?.startScan(callback)
        scope.launch {
            delay(15_000)
            stopScan()
        }
        touch()
    }

    @SuppressLint("MissingPermission")
    fun connect(device: FoundFlipper) {
        if (connecting || connected) return
        connecting = true
        connection = "Connecting to ${device.name}"
        statusMessage = "First time with this Flipper: confirm the same PIN on the phone and on the device."
        touch()
        scope.launch {
            var opened: BleSerialTransport? = null
            try {
                stopScan()
                val link = BleSerialTransport(context, device.device)
                opened = link
                link.awaitReady()
                val client = FlipperRpcClient(link)
                client.start()
                rpc = client
                transport = link
                link.onDisconnected = {
                    scope.launch {
                        if (transport === link && connected) {
                            statusMessage = "Connection to the Flipper was lost"
                            drop(reconnect = autoConnect)
                        }
                    }
                }
                pipe.inner = wrapDevice(RpcControlling(client), client)
                connected = true
                connecting = false
                connection = device.name
                deviceTitle = device.name
                lastDeviceAddress = device.device.address
                statusMessage = null
                savePrefs()
                startForeground()
                refreshDevice()
                loadProfile()
                if (shortcutKind == HeroShortcut.Emulate || shortcutKind == HeroShortcut.Send) loadShortcutFiles()
                touch()
            } catch (error: TimeoutCancellationException) {
                failConnect(opened, "The Flipper did not answer in time")
            } catch (error: kotlinx.coroutines.CancellationException) {
                opened?.let { runCatching { it.close() } }
                connecting = false
                throw error
            } catch (error: Exception) {
                failConnect(opened, error.message ?: "Connection failed")
            }
        }
    }

    fun refreshFiles() {
        if (!connected) return
        scope.launch {
            fileLoading = true
            fileError = null
            try {
                val entries = pipe.list(filePath).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                fileEntries.clear()
                fileEntries.addAll(entries)
                val info = pipe.storageInfo("/ext")
                fileSpace = "${formatBytes(info.freeSpace)} free of ${formatBytes(info.totalSpace)}"
            } catch (error: Exception) {
                fileError = error.message
            } finally {
                fileLoading = false
                touch()
            }
        }
    }

    fun rescanDevice() {
        if (connected) scope.launch {
            val fresh = profileStore.build(pipe)
            if (connected && fresh.hardwareUID == deviceUID) {
                profile = fresh
                session?.updateProfile(fresh)
            }
        }
    }

    private fun loadProfile() {
        val uid = deviceUID
        if (uid.isEmpty()) return
        scope.launch {
            profileStore.cached(uid)?.let { cached ->
                if (connected && uid == deviceUID) {
                    profile = cached
                    session?.updateProfile(cached)
                }
            }
            rescanDevice()
        }
    }

    fun openEntry(entry: FlipperDirEntry) {
        val path = FlipperPath.join(filePath, entry.name)
        if (entry.isDirectory) {
            filePath = path
            refreshFiles()
            return
        }
        scope.launch {
            try {
                val bytes = pipe.read(path, 16 * 1024)
                previewTitle = entry.name
                previewText = previewOf(bytes)
                nav.select(hero.flipper.app.nav.HeroSection.Files)
                nav.pushFilePreview()
                touch()
            } catch (error: Exception) {
                fileError = error.message
                touch()
            }
        }
    }

    fun popDirectory(): Boolean {
        if (filePath == "/ext" || filePath == "/") return false
        val parent = FlipperPath.parent(filePath)
        filePath = if (parent == "/") "/ext" else parent
        refreshFiles()
        return true
    }

    fun sendDraft() {
        val text = draft.trim()
        if (text.isEmpty() || busy) return
        val images = pendingImage?.let { listOf(it) } ?: emptyList()
        draft = ""
        pendingImage = null
        photoAttached = false
        transcript.add(Transcript("user", text, photo = images.isNotEmpty()))
        busy = true
        scope.launch {
            try {
                val reply = agent().send(text, images) { event ->
                    when (event) {
                        is AgentEvent.ToolStarted -> transcript.add(Transcript("tool", event.summary, tool = event.name, status = "running"))
                        is AgentEvent.ToolFinished -> {
                            val status = event.result.kind.name
                            val row = Transcript("tool", event.summary + "\n" + event.result.content.take(600), tool = event.name, status = status)
                            val index = transcript.indexOfLast { it.kind == "tool" && it.tool == event.name && it.status == "running" }
                            if (index >= 0) transcript[index] = row else transcript.add(row)
                            if (event.result.kind == ToolResultKind.ok) noteOperation(event.name, event.arguments)
                        }
                    }
                }
                transcript.add(Transcript("assistant", reply))
                if (readAloud && reply.isNotBlank()) speaker.speak(reply)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                transcript.add(Transcript("assistant", error.message ?: "The model did not reply."))
            } finally {
                busy = false
            }
        }
    }

    fun newChat() {
        if (busy) return
        session = null
        transcript.clear()
        draft = ""
        clearPhoto()
    }

    fun openShortcut(kind: HeroShortcut) {
        shortcutStatus = null
        when (kind) {
            HeroShortcut.Ask -> nav.select(HeroSection.Agent)
            HeroShortcut.Battery -> {
                nav.select(HeroSection.Device)
                if (connected) scope.launch { refreshDevice() }
            }
            HeroShortcut.Stop -> {
                nav.select(HeroSection.Device)
                if (connected) runShortcutTool("stop_app", "{}")
                else shortcutStatus = "Connect to a Flipper first."
            }
            HeroShortcut.Emulate, HeroShortcut.Send -> {
                if (connected) {
                    shortcutKind = kind
                    shortcutFiles.clear()
                    loadShortcutFiles()
                } else {
                    nav.select(HeroSection.Device)
                    shortcutStatus = "Connect to a Flipper first."
                }
            }
        }
        touch()
    }

    fun closeShortcut() {
        shortcutKind = null
        shortcutFiles.clear()
        shortcutStatus = null
        shortcutButton = ""
    }

    private fun loadShortcutFiles() {
        val kind = shortcutKind ?: return
        val folders = if (kind == HeroShortcut.Emulate) {
            listOf("/ext/nfc", "/ext/lfrfid", "/ext/ibutton")
        } else {
            listOf("/ext/subghz", "/ext/infrared")
        }
        scope.launch {
            shortcutFiles.clear()
            for (folder in folders) {
                val entries = runCatching { pipe.list(folder) }.getOrNull() ?: continue
                shortcutFiles.addAll(entries.filter { !it.isDirectory }.map { FlipperPath.join(folder, it.name) })
            }
            if (shortcutFiles.isEmpty()) shortcutStatus = "No saved files found."
        }
    }

    fun selectShortcutFile(path: String) {
        if (path !in shortcutFiles) return
        val tool = when (path.substringAfterLast('.').lowercase()) {
            "nfc" -> "emulate_nfc"
            "rfid" -> "emulate_rfid"
            "ibtn" -> "emulate_ibutton"
            "sub" -> "transmit_subghz"
            "ir" -> "transmit_infrared"
            else -> return
        }
        val args = JSONObject().put("path", path)
        if (tool == "transmit_infrared") args.put("button", shortcutButton.trim())
        closeShortcut()
        runShortcutTool(tool, args.toString())
    }

    private fun runShortcutTool(tool: String, arguments: String) {
        scope.launch {
            val result = newExecutor(AuditDecision.shortcut).execute(ToolCall("shortcut", tool, arguments))
            shortcutStatus = result.content.lines().filterNot { it.startsWith("<<<") }.joinToString("\n").take(700)
            if (result.kind == ToolResultKind.ok) noteOperation(tool, arguments)
        }
    }

    fun listen() {
        val host = voiceContext ?: context
        if (!permission(android.Manifest.permission.RECORD_AUDIO, host)) {
            requestPermissions?.invoke()
            return
        }
        val input = voice ?: try {
            VoiceInput(host).also { voice = it }
        } catch (error: Exception) {
            statusMessage = error.message ?: "Speech input is unavailable"
            touch()
            return
        }
        input.listen({ heard -> draft = heard }, { statusMessage = it; touch() })
    }

    fun capturePhoto() {
        val host = voiceContext ?: context
        if (!permission(android.Manifest.permission.CAMERA, host)) {
            requestPermissions?.invoke()
            return
        }
        openCamera?.invoke()
    }

    fun attachPhoto(bitmap: Bitmap) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        pendingImage = stream.toByteArray()
        photoAttached = true
        touch()
    }

    fun clearPhoto() {
        pendingImage = null
        photoAttached = false
        touch()
    }

    fun pressPad(key: FlipperKey, long: Boolean) {
        scope.launch {
            try {
                pipe.press(key, long)
                remoteError = null
            } catch (error: Exception) {
                remoteError = error.message
            }
        }
    }

    fun watchScreen(active: Boolean) {
        screenJob?.cancel()
        screenJob = null
        if (!active || !connected) {
            screenLive = false
            return
        }
        screenJob = scope.launch {
            while (isActive) {
                try {
                    val frame = pipe.captureScreen(2.seconds)
                    screenBitmap = frameBitmap(frame)
                    screenLive = true
                    remoteError = null
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    screenLive = false
                    remoteError = error.message
                }
                delay(700)
            }
        }
    }

    fun beginConnectionTest(onNote: (String) -> Unit) {
        scope.launch {
            val note = try {
                testConnection()
                "The selected model replied."
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                error.message ?: "Test failed"
            }
            onNote(note)
        }
    }

    fun saveKey() {
        val value = keyDraft.trim()
        val selectedProvider = provider
        val selectedModel = modelId
        val selectedURL = apiBaseURL
        val box = secrets
        if (value.isEmpty() || box == null) {
            keyStatus = if (box == null) "Key storage is unavailable" else null
            return
        }
        if (keySaving) return
        keySaving = true
        scope.launch {
            try {
                val chosen = AIProvider.from(selectedProvider) ?: AIProvider.OpenRouter
                val reply = CompletingClient(http, value, selectedModel, chosen, selectedURL)
                    .complete(listOf(ChatMessage(ChatRole.user, "Reply with the word ok.")), emptyList())
                if (reply.content.isNullOrBlank() && reply.toolCalls.isEmpty()) throw ProviderError.EmptyReply()
                box.store(selectedProvider, value)
                if (provider == selectedProvider) {
                    keyDraft = ""
                    keyStored = true
                    keyStatus = "Connection verified"
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                keyStatus = error.message ?: "Could not save key"
            } finally {
                keySaving = false
                touch()
            }
        }
    }

    fun removeKey() {
        secrets?.remove(provider)
        keyStored = false
        keyDraft = ""
        keyStatus = null
        touch()
    }

    fun chooseProvider(id: String) {
        if (AIProvider.from(id) == null || provider == id) return
        scope.launch {
            savePrefs()
            val (savedModel, savedURL) = prefs.providerSettings(id)
            provider = id
            modelId = savedModel
            apiBaseURL = savedURL
            keyStored = secrets?.stored(id) == true
            availableModels.clear()
            modelListStatus = null
            keyStatus = null
            savePrefs()
            touch()
        }
    }

    fun chooseModel(id: String) {
        modelId = id.trim()
        scope.launch { savePrefs() }
    }

    fun chooseBaseURL(url: String) {
        apiBaseURL = url.trim()
        scope.launch { savePrefs() }
    }

    fun refreshModels() {
        val selectedProvider = provider
        modelListStatus = "Loading models…"
        availableModels.clear()
        scope.launch {
            try {
                val models = listModels().sortedBy { it.name.lowercase() }
                if (provider != selectedProvider) return@launch
                availableModels.addAll(models)
                modelListStatus = if (availableModels.isEmpty()) "No matching models" else null
            } catch (error: Exception) {
                if (provider == selectedProvider) modelListStatus = error.message ?: "Could not load models"
            }
        }
    }

    fun setAutoApprove(enabled: Boolean) {
        autoApproveMedium = enabled
        scope.launch { savePrefs() }
    }

    fun setAutoConnectEnabled(enabled: Boolean) {
        autoConnect = enabled
        scope.launch { savePrefs() }
    }

    fun setReadAloudEnabled(enabled: Boolean) {
        readAloud = enabled
        if (!enabled) speaker.stop()
        scope.launch { savePrefs() }
    }

    fun setYoloEnabled(enabled: Boolean) {
        yolo = enabled
        touch()
    }

    fun setYoloAsks(enabled: Boolean) {
        yoloAsksAfterReading = enabled
        touch()
    }

    fun applyRename() {
        val name = renameDraft.trim()
        scope.launch {
            try {
                pipe.setDeviceName(name)
                deviceTitle = name
                statusMessage = "The new name shows after the Flipper restarts."
            } catch (error: Exception) {
                statusMessage = error.message
            }
            touch()
        }
    }

    fun beginRestart() {
        scope.launch {
            try {
                rpc?.reboot()
            } catch (_: FlipperError.Timeout) {
            } catch (_: FlipperError.NotConnected) {
            }
            drop(reconnect = autoConnect)
        }
    }

    fun beginFirmware() {
        scope.launch {
            publishFirmwareStatus(startFirmwareUpdate())
            touch()
        }
    }

    override suspend fun settings(): AppSettingsSnapshot {
        return AppSettingsSnapshot(
            yolo = yolo,
            yoloAsksAfterReading = yoloAsksAfterReading,
            autoApproveMedium = autoApproveMedium,
            readAloud = readAloud,
            autoConnect = autoConnect,
            model = modelId,
            apiKeyStored = secrets?.stored(provider) == true,
            provider = provider,
            apiBaseURL = apiBaseURL,
            engagementActive = engagement.active,
            engagementSummary = if (engagement.active) edition().engagementSummary(engagement.profile) else "off",
        )
    }

    override suspend fun setReadAloud(enabled: Boolean) = setReadAloudEnabled(enabled)
    override suspend fun setAutoConnect(enabled: Boolean) = setAutoConnectEnabled(enabled)
    override suspend fun setYolo(enabled: Boolean) = setYoloEnabled(enabled)
    override suspend fun setYoloAsksAfterReading(enabled: Boolean) = setYoloAsks(enabled)
    override suspend fun setAutoApproveMedium(enabled: Boolean) = setAutoApprove(enabled)
    override suspend fun setModel(model: String) = chooseModel(model)
    override suspend fun setProvider(provider: AIProvider, baseURL: String?) {
        val resolved = if (baseURL.isNullOrBlank()) prefs.providerSettings(provider.wire).second else provider.validatedBaseURL(baseURL)
        if (this.provider != provider.wire) savePrefs()
        val (savedModel, _) = prefs.providerSettings(provider.wire)
        this.provider = provider.wire
        modelId = savedModel
        apiBaseURL = resolved
        keyStored = secrets?.stored(provider.wire) == true
        savePrefs()
    }

    override suspend fun listModels(): List<AIModel> {
        val key = secrets?.read(provider) ?: throw ProviderError.MissingKey()
        val chosen = AIProvider.from(provider) ?: AIProvider.OpenRouter
        val response = http.request(chosen.validatedBaseURL(apiBaseURL).trimEnd('/') + "/models", mapOf("Authorization" to "Bearer $key"))
        if (response.status !in 200..299) throw ProviderError.ModelsUnavailable()
        val data = JSONObject(response.body.decodeToString()).optJSONArray("data") ?: throw ProviderError.ModelsUnavailable()
        val models = ArrayList<AIModel>()
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val id = item.optString("id")
            if (id.isNotEmpty()) models.add(AIModel(id, item.optString("name", id)))
        }
        if (models.isEmpty()) throw ProviderError.ModelsUnavailable()
        return models
    }

    override suspend fun testConnection() {
        val reply = llm.complete(listOf(ChatMessage(ChatRole.user, "Reply with the word ok.")), emptyList())
        if (reply.content.isNullOrBlank() && reply.toolCalls.isEmpty()) throw ProviderError.EmptyReply()
    }

    override suspend fun setEngagement(state: EngagementState) {
        withContext(Dispatchers.Main) { arm(state, auditChange = false) }
    }

    override suspend fun restartDevice() {
        beginRestart()
    }

    override suspend fun startFirmwareUpdate(): String {
        if (!connected || rpc == null) return "Not connected"
        if (firmwareJob?.isActive == true) return "An update is already running."
        val info = pipe.deviceInfo()
        val distribution = FirmwareCatalog.identify(info["firmware_origin_fork"], info["firmware_version"])
            ?: return "Unknown firmware. Nothing was installed."
        firmwareCancel = false
        firmwareJob = scope.launch {
            runFirmware(distribution.name, distribution.repo, info["hardware_target"].orEmpty())
        }
        return "Started updating ${distribution.name} in the background. It downloads, uploads over Bluetooth, then the Flipper installs it and restarts."
    }

    override suspend fun firmwareUpdateStatus(): String = firmwareStatus

    override suspend fun cancelFirmwareUpdate(): String {
        cancelFirmware()
        return "Cancelled"
    }

    private suspend fun runFirmware(name: String, repo: String, hardwareTarget: String) {
        val client = rpc ?: return
        try {
            publishFirmwareStatus("Checking $name")
            val (tag, url) = withContext(Dispatchers.IO) { latestPackage(repo) }
            if (cancelled()) return
            publishFirmwareStatus("Downloading $tag")
            val bytes = withContext(Dispatchers.IO) {
                val response = http.request(url)
                if (response.status !in 200..299) throw IllegalStateException("Download failed (${response.status})")
                response.body
            }
            if (cancelled()) return
            val pkg = FirmwareUpdatePackage.parse(tag, TarGz.extract(bytes))
            if (hardwareTarget.isNotEmpty() && pkg.target.isNotEmpty() && hardwareTarget != pkg.target) {
                publishFirmwareStatus("The package is for hardware target ${pkg.target}, this Flipper is target $hardwareTarget")
                return
            }
            val power = pipe.powerInfo()
            val charging = (power["charge_state"] ?: power["charge.state"]) == "charging"
            val level = (power["charge_level"] ?: power["charge.level"])?.toIntOrNull()
            if (level != null && level < 30 && !charging) {
                publishFirmwareStatus("Battery is at $level%. Charge to at least 30% or plug it in first.")
                return
            }
            val storage = pipe.storageInfo("/ext")
            val needed = pkg.totalBytes + pkg.totalBytes / 5
            if (storage.freeSpace < needed.toULong()) {
                publishFirmwareStatus("Not enough SD space for the update.")
                return
            }
            if (cancelled()) return
            val folder = "/ext/update/${pkg.folder}"
            client.makeDirectories(folder)
            for (file in pkg.files) {
                if (cancelled()) return
                publishFirmwareStatus("Uploading ${file.path}")
                client.write("$folder/${file.path}", file.data)
            }
            if (cancelled()) return
            publishFirmwareStatus("Staging")
            when (val result = client.requestUpdate(pkg.manifestPath)) {
                is FlipperUpdateResult.Rejected -> publishFirmwareStatus("Update rejected: ${result.why}")
                FlipperUpdateResult.Ok -> {
                    publishFirmwareStatus("Restarting the Flipper into the updater")
                    client.rebootIntoUpdater()
                    drop(reconnect = false)
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            publishFirmwareStatus("Cancelled")
            throw error
        } catch (error: Exception) {
            publishFirmwareStatus(error.message ?: "Update failed")
        }
    }

    private fun cancelled(): Boolean {
        if (!firmwareCancel && coroutineContextIsActive()) return false
        publishFirmwareStatus("Cancelled")
        return true
    }

    private fun publishFirmwareStatus(value: String) {
        firmwareStatus = value
        refreshForeground()
    }

    private fun noteOperation(tool: String, arguments: String) {
        activeOperation = when (tool) {
            "emulate_nfc", "emulate_rfid", "emulate_ibutton" ->
                "Emulating ${runCatching { JSONObject(arguments).getString("path").substringAfterLast('/') }.getOrDefault("card or key") }"
            "stop_app" -> null
            else -> return
        }
        refreshForeground()
    }

    private fun refreshForeground() {
        if (connected) ContextCompat.startForegroundService(context,
            Intent(context, ConnectedDeviceService::class.java).setAction(ConnectedDeviceService.ACTION_REFRESH))
    }

    private fun coroutineContextIsActive(): Boolean = firmwareJob?.isActive != false

    private suspend fun latestPackage(repo: String): Pair<String, String> {
        val response = http.request(
            "https://api.github.com/repos/$repo/releases/latest",
            mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "FlipperHero"),
        )
        if (response.status !in 200..299) throw IllegalStateException("No update package (HTTP ${response.status})")
        val root = JSONObject(response.body.decodeToString())
        val tag = root.optString("tag_name").ifEmpty { "release" }
        val assets = root.optJSONArray("assets") ?: throw IllegalStateException("No update package for $tag")
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val assetName = asset.optString("name").lowercase()
            if (!assetName.contains("update") || !assetName.endsWith(".tgz") || !assetName.contains("f7")) continue
            val link = asset.optString("browser_download_url")
            val host = runCatching { URI(link).host?.lowercase() }.getOrNull().orEmpty()
            val allowed = link.startsWith("https://") && (host == "github.com" || host.endsWith(".github.com") || host.endsWith("githubusercontent.com"))
            if (allowed) return tag to link
        }
        throw IllegalStateException("No update package for $tag")
    }

    private fun agent(): AgentSession {
        session?.let { return it }
        val executor = newExecutor(AuditDecision.approved)
        executor.onProfileRefreshed = { profile ->
            if (profile.deviceName.isNotEmpty()) deviceTitle = profile.deviceName
            this.profile = profile
            session?.updateProfile(profile)
            touch()
        }
        return AgentSession(llm, executor, edition(), profile = profile, engagement = engagement).also { session = it }
    }

    private fun newExecutor(approvedAs: AuditDecision): ToolExecutor = ToolExecutor(
            flipper = pipe,
            gate = gate,
            audit = audit,
            forge = PayloadForge(llm),
            profileStore = profileStore,
            fapHub = FapHubClient(http),
            gitHub = GitHubClient(http),
            appControls = this,
            firmwareChecker = FirmwareUpdateChecker(http),
            settings = { approvalSettings() },
            approvedAs = approvedAs,
            edition = edition(),
        )

    private fun approvalSettings() = ApprovalSettings(
        autoApproveMedium = autoApproveMedium,
        yolo = yolo,
        yoloAsksAfterUntrusted = yoloAsksAfterReading,
        engagement = engagement,
    )

    private suspend fun refreshDevice() {
        try {
            val info = pipe.deviceInfo()
            deviceUID = info["hardware_uid"].orEmpty()
            firmwareLine = listOf(info["firmware_origin_fork"], info["firmware_version"]).mapNotNull { it }.filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "unknown" }
            scope.launch {
                val status = FirmwareUpdateChecker(http).status(info["firmware_origin_fork"], info["firmware_version"])
                if (connected && deviceUID == info["hardware_uid"].orEmpty()) firmwareNotice = status?.summary
            }
            val custom = runCatching { pipe.customDeviceName() }.getOrNull()
            deviceTitle = custom ?: info["hardware_name"] ?: connection
            val power = pipe.powerInfo()
            val level = power["charge_level"] ?: power["charge.level"]
            val charging = (power["charge_state"] ?: power["charge.state"]) == "charging"
            batterySummary = when {
                level.isNullOrEmpty() -> "unknown"
                charging -> "$level% charging"
                else -> "$level%"
            }
            batteryFraction = level?.toFloatOrNull()?.div(100f)
            val storage = pipe.storageInfo("/ext")
            sdSummary = "${formatBytes(storage.freeSpace)} free"
            sdFraction = if (storage.totalSpace == 0uL) null else (storage.totalSpace - storage.freeSpace).toFloat() / storage.totalSpace.toFloat()
        } catch (error: Exception) {
            statusMessage = error.message
        }
        touch()
    }

    private fun drop(reconnect: Boolean) {
        screenJob?.cancel()
        val closing = rpc
        val link = transport
        rpc = null
        transport = null
        pipe.inner = null
        deviceUID = ""
        profile = null
        session = null
        firmwareNotice = null
        activeOperation = null
        connected = false
        connecting = false
        connection = "Not connected"
        screenLive = false
        engagement = EngagementState()
        session?.updateEngagement(engagement)
        context.stopService(Intent(context, ConnectedDeviceService::class.java))
        scope.launch {
            runCatching { closing?.stop() }
            runCatching { link?.close() }
            if (reconnect && autoConnect) scan()
        }
        touch()
    }

    private fun failConnect(link: BleSerialTransport?, message: String) {
        connecting = false
        connected = false
        connection = "Not connected"
        pipe.inner = null
        statusMessage = message
        scope.launch { runCatching { link?.close() } }
        touch()
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val callback = scanCallback
        if (callback != null) runCatching { scanner?.stopScan(callback) }
        scanCallback = null
        scanning = false
    }

    private fun startForeground() {
        if (Build.VERSION.SDK_INT >= 33 && !permission(android.Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions?.invoke()
        }
        val intent = Intent(context, ConnectedDeviceService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    private suspend fun restore() {
        val stored = try {
            prefs.load()
        } catch (_: Exception) {
            StoredPrefs()
        }
        autoApproveMedium = stored.autoApproveMedium
        autoConnect = stored.autoConnect
        readAloud = stored.readAloud
        modelId = stored.model
        provider = stored.provider
        apiBaseURL = stored.baseURL
        lastDeviceAddress = stored.lastDeviceAddress
        keyStored = secrets?.stored(provider) == true
        auditLines.clear()
        auditLines.addAll(fileAudit.recent(50).asReversed().map { "${it.tool} ${it.decision.name} ${it.summary}" })
        loadedPrefs = true
        if (autoConnect && lastDeviceAddress.isNotEmpty()) scan()
        touch()
    }

    private suspend fun savePrefs() {
        if (!loadedPrefs) return
        prefs.save(
            StoredPrefs(
                autoApproveMedium = autoApproveMedium,
                autoConnect = autoConnect,
                readAloud = readAloud,
                model = modelId,
                provider = provider,
                baseURL = apiBaseURL,
                lastDeviceAddress = lastDeviceAddress,
            ),
        )
    }

    private fun permission(name: String, host: Context = context): Boolean =
        ContextCompat.checkSelfPermission(host, name) == PackageManager.PERMISSION_GRANTED

    private var pendingImage: ByteArray? = null

    private fun previewOf(bytes: ByteArray): String {
        val text = bytes.toString(Charsets.UTF_8)
        val printable = text.count { it == '\n' || it == '\t' || it.code in 32..126 }
        if (text.isEmpty() || printable * 10 < text.length * 8) return "Binary file, ${bytes.size} bytes"
        return text
    }

    fun shutdown() {
        scope.cancel()
    }
}

private fun formatBytes(value: ULong): String {
    val n = value.toLong()
    if (n < 1024) return "$n B"
    val kb = n / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
    return String.format(java.util.Locale.US, "%.1f MB", kb / 1024.0)
}

private fun frameBitmap(frame: FlipperScreenFrame): Bitmap {
    val (width, height) = frame.displaySize
    val scale = 4
    val bitmap = Bitmap.createBitmap(width * scale, height * scale, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(width * scale * height * scale)
    val ink = 0xFF160E06.toInt()
    val backlight = 0xFFFF8200.toInt()
    for (y in 0 until height) {
        for (x in 0 until width) {
            val color = if (frame.pixel(x, y)) ink else backlight
            for (dy in 0 until scale) {
                val row = (y * scale + dy) * width * scale
                for (dx in 0 until scale) pixels[row + x * scale + dx] = color
            }
        }
    }
    bitmap.setPixels(pixels, 0, width * scale, 0, 0, width * scale, height * scale)
    return bitmap
}
