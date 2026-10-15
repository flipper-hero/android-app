package hero.flipper.agent

import hero.flipper.kit.FlipperApp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/** Runs model tool calls: validate, assess risk, ask the user, execute, audit. */
class ToolExecutor(
    private val flipper: FlipperControlling,
    private val gate: ApprovalGate,
    private val audit: AuditSink,
    private val forge: PayloadForge? = null,
    private val profileStore: DeviceProfileStore? = null,
    private val fapHub: FapHubClient? = null,
    private val gitHub: GitHubClient? = null,
    private val appControls: AppControls? = null,
    private val firmwareChecker: FirmwareUpdateChecker? = null,
    private val settings: () -> ApprovalSettings = { ApprovalSettings() },
    private val approvedAs: AuditDecision = AuditDecision.approved,
    val nonce: String = UUID.randomUUID().toString().replace("-", "").take(8).lowercase(),
    private val edition: AgentEdition = StoreEdition,
) {
    var isTainted: Boolean = false
        private set

    var onProfileRefreshed: ((DeviceProfile) -> Unit)? = null

    private val forged = HashMap<String, String>()
    private val downloaded = HashMap<String, ByteArray>()
    private val pendingImages = ArrayList<ByteArray>()

    fun newUserTurn() {
        isTainted = false
    }

    suspend fun execute(call: ToolCall): ToolResult {
        val invocation = try {
            parseCall(call.name, call.arguments, edition)
        } catch (error: Exception) {
            audit.record(AuditRecord(tool = call.name, summary = "invalid call", risk = null, decision = AuditDecision.invalid, succeeded = false, detail = "$error"))
            return ToolResult.error("$error")
        }
        val assessment = RiskAssessor.assess(invocation, edition)
        if (assessment.level == RiskLevel.Blocked) {
            audit.record(
                AuditRecord(
                    tool = invocation.toolName,
                    summary = invocation.summary,
                    risk = RiskLevel.Blocked,
                    decision = AuditDecision.blocked,
                    succeeded = false,
                    detail = assessment.reasons.joinToString("; "),
                ),
            )
            return ToolResult.error(
                "Blocked by safety policy: ${assessment.reasons.joinToString("; ")}. Do not retry or look for a workaround; tell the user.",
                ToolResultKind.blocked,
            )
        }
        val current = settings()
        val capability = edition.capability(invocation)
        if (capability != null && !current.engagement.armed(capability)) {
            audit.record(
                AuditRecord(
                    tool = invocation.toolName,
                    summary = invocation.summary,
                    risk = assessment.level,
                    decision = AuditDecision.blocked,
                    succeeded = false,
                    detail = "capability not armed",
                ),
            )
            return ToolResult.error(edition.capabilityRefusal(invocation.toolName))
        }
        var decision = AuditDecision.auto
        val consent = invocation.requiresExplicitConsent
        if (consent || invocation.alwaysAsk || ApprovalPolicy.requiresApproval(assessment.level, current, isTainted)) {
            val request = ApprovalRequest(
                tool = invocation.toolName,
                summary = invocation.summary,
                risk = assessment.level,
                reasons = assessment.reasons,
                diff = previewDiff(invocation),
                afterUntrustedContent = isTainted,
                isPermissionChange = consent,
            )
            if (!gate.decide(request)) {
                audit.record(
                    AuditRecord(
                        tool = invocation.toolName,
                        summary = invocation.summary,
                        risk = assessment.level,
                        decision = AuditDecision.denied,
                        succeeded = false,
                        detail = "denied by user",
                    ),
                )
                return ToolResult.error("The user denied this action. Do not repeat it; ask what they want instead.", ToolResultKind.denied)
            }
            decision = approvedAs
        } else if (current.engagement.active && current.engagement.profile.autoApprovals && assessment.level >= RiskLevel.Medium) {
            decision = AuditDecision.engaged
        } else if (current.yolo && assessment.level >= RiskLevel.Medium) {
            decision = AuditDecision.yolo
        }
        return try {
            pendingImages.clear()
            val output = run(invocation)
            audit.record(
                AuditRecord(
                    tool = invocation.toolName,
                    summary = invocation.summary,
                    risk = assessment.level,
                    decision = decision,
                    succeeded = true,
                    detail = "",
                ),
            )
            val images = pendingImages.toList()
            pendingImages.clear()
            ToolResult.ok(output, images)
        } catch (error: Exception) {
            audit.record(
                AuditRecord(
                    tool = invocation.toolName,
                    summary = invocation.summary,
                    risk = assessment.level,
                    decision = decision,
                    succeeded = false,
                    detail = "$error",
                ),
            )
            ToolResult.error("$error")
        }
    }

    private fun hooks() = EditionHooks(
        flipper = flipper,
        appControls = appControls,
        audit = audit,
        nonce = nonce,
        settings = settings,
        markTainted = { isTainted = true },
        attachScreen = { attachScreen() },
    )

    private suspend fun attachScreen(): String {
        val frame = flipper.captureScreen(5.seconds)
        isTainted = true
        val png = renderScreen(frame) ?: return "Captured the screen, but could not render it."
        pendingImages.add(png)
        val (width, height) = frame.displaySize
        return "Screen captured (${width}x${height} px), attached as an image."
    }

    private suspend fun deviceAPIVersion(): String {
        val info = flipper.deviceInfo()
        val major = info["firmware_api_major"] ?: "0"
        val minor = info["firmware_api_minor"] ?: "0"
        return "$major.$minor"
    }

    private suspend fun findApp(query: String): Triple<CatalogApp, FapHubClient, String> {
        val client = fapHub ?: throw CatalogError.NotFound(query)
        val api = deviceAPIVersion()
        val results = client.search(query, api)
        val match = results.firstOrNull { it.alias.equals(query, ignoreCase = true) }
            ?: results.firstOrNull { it.name.equals(query, ignoreCase = true) }
            ?: results.firstOrNull()
            ?: throw CatalogError.NotFound(query)
        return Triple(match, client, api)
    }

    private suspend fun run(call: AgentCall): String = when (call) {
        is CoreCall -> runCore(call)
        else -> edition.execute(call, hooks()) ?: throw ToolError.UnknownTool(call.toolName)
    }

    private suspend fun runCore(call: CoreCall): String = when (call) {
        GetDeviceInfo -> {
            val info = flipper.deviceInfo()
            val keys = listOf(
                "hardware_name", "hardware_model", "hardware_region_provisioned", "firmware_origin_fork",
                "firmware_version", "firmware_commit", "firmware_branch", "firmware_build_date",
                "protobuf_version_major", "protobuf_version_minor",
            )
            val lines = keys.mapNotNull { key -> info[key]?.let { "$key: ${Untrusted.sanitizeName(it)}" } }.toMutableList()
            val pending = try { flipper.customDeviceName() } catch (_: Exception) { null }
            if (pending != null && pending != info["hardware_name"]) {
                lines.add("pending_name: ${Untrusted.sanitizeName(pending)} (shown after the next restart)")
            }
            lines.joinToString("\n")
        }
        GetPowerInfo -> {
            val info = flipper.powerInfo()
            val keys = listOf(
                "charge_level", "charge_state", "battery_health", "battery_voltage",
                "battery_current", "battery_temp", "capacity_remain", "capacity_full",
                "charge.level", "charge.state", "battery.health",
            )
            val lines = keys.mapNotNull { key -> info[key]?.let { "$key: ${Untrusted.sanitizeName(it)}" } }.toMutableList()
            val level = info["charge_level"] ?: info["charge.level"]
            if (level != null) {
                val state = info["charge_state"] ?: info["charge.state"] ?: "unknown"
                lines.add(0, "summary: battery ${Untrusted.sanitizeName(level)}%, ${Untrusted.sanitizeName(state)}")
            }
            if (lines.isEmpty()) {
                info.entries.sortedBy { it.key }.take(12).joinToString("\n") { "${it.key}: ${Untrusted.sanitizeName(it.value)}" }
            } else {
                lines.joinToString("\n")
            }
        }
        is GetStorageInfo -> {
            val info = flipper.storageInfo(call.path)
            "total: ${info.totalSpace} bytes\nfree: ${info.freeSpace} bytes"
        }
        is ListDirectory -> {
            val entries = flipper.list(call.path)
            isTainted = true
            val lines = entries.take(200).map { entry ->
                if (entry.isDirectory) "[dir] ${Untrusted.sanitizeName(entry.name)}"
                else "[file] ${Untrusted.sanitizeName(entry.name)} (${entry.size} bytes)"
            }
            var text = if (lines.isEmpty()) "(empty)" else lines.joinToString("\n")
            if (entries.size > 200) text += "\n… ${entries.size - 200} more entries"
            Untrusted.wrap(text, call.path, nonce)
        }
        is ReadFile -> {
            val data = flipper.read(call.path, maxReadBytes)
            isTainted = true
            val text = if (data.any { it == 0.toByte() }) null else decodeUtf8(data)
            if (text == null) {
                val head = data.take(32).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
                Untrusted.wrap("binary file, ${data.size} bytes, first bytes: $head", call.path, nonce)
            } else {
                Untrusted.wrap(text, call.path, nonce)
            }
        }
        is CreateDirectory -> {
            flipper.makeDirectory(call.path)
            "Created folder ${call.path}"
        }
        is WriteFile -> {
            val bytes = call.content.encodeToByteArray()
            flipper.write(call.path, bytes)
            "Wrote ${bytes.size} bytes to ${call.path}"
        }
        is RenamePath -> {
            flipper.rename(call.from, call.to)
            "Moved ${call.from} to ${call.to}"
        }
        is LaunchApp -> {
            flipper.startApp(call.name, call.args)
            "Started app ${call.name}"
        }
        is DeletePath -> {
            flipper.delete(call.path, call.recursive)
            "Deleted ${call.path}"
        }
        is TransmitSubGhz -> {
            flipper.transmitOnce(FlipperApp.SubGhz, call.path, "", 400.milliseconds)
            "Transmitted ${call.path} once"
        }
        is TransmitInfrared -> {
            flipper.transmitOnce(FlipperApp.Infrared, call.path, call.button, 250.milliseconds)
            val which = if (call.button.isEmpty()) "the remote" else call.button
            "Sent $which from ${call.path}"
        }
        is EmulateNfc -> {
            flipper.emulate(FlipperApp.Nfc, call.path)
            "Emulating ${call.path}. It keeps running until you call stop_app."
        }
        is EmulateRfid -> {
            flipper.emulate(FlipperApp.Rfid, call.path)
            "Emulating ${call.path}. It keeps running until you call stop_app."
        }
        is EmulateIButton -> {
            flipper.emulate(FlipperApp.IButton, call.path)
            "Emulating ${call.path}. It keeps running until you call stop_app."
        }
        is LoadBadKeyboardScript -> {
            flipper.loadBadKeyboardScript(call.path)
            "Bad KB is open with ${call.path} loaded. Press Run on the Flipper to execute it."
        }
        StopApp -> {
            flipper.exitApp()
            "Closed the app on the Flipper"
        }
        is ForgePayload -> {
            val content = forged.remove(call.path) ?: run {
                val forger = forge ?: throw ForgeError.Unavailable()
                forger.forge(call.kind, call.description)
            }
            flipper.write(call.path, content.encodeToByteArray())
            "Wrote ${content.encodeToByteArray().size} bytes to ${call.path}"
        }
        is SearchFapHub -> {
            val client = fapHub ?: throw CatalogError.NotFound(call.query)
            val api = deviceAPIVersion()
            val results = client.search(call.query, api)
            if (results.isEmpty()) "No apps found for '${call.query}' (firmware SDK $api)"
            else results.joinToString("\n") {
                "${Untrusted.sanitizeName(it.name)} [${it.alias}] v${it.version} - ${Untrusted.sanitizeName(it.summary)}"
            }
        }
        is InstallFapHubApp -> {
            val (app, client, api) = findApp(call.query)
            val data = downloaded.remove(app.versionID) ?: client.downloadBuild(app.versionID, api)
            val categories = try { client.categories(api) } catch (_: Exception) { emptyMap() }
            val folder = categories[app.categoryID] ?: "Tools"
            val name = if (app.alias.isEmpty()) app.name else app.alias
            val target = "/ext/apps/$folder/$name.fap"
            try { flipper.makeDirectory("/ext/apps/$folder") } catch (_: Exception) {}
            flipper.write(target, data)
            "Installed ${app.name} v${app.version} (${data.size} bytes) to $target"
        }
        is SearchGitHub -> {
            val client = gitHub ?: throw CatalogError.Malformed()
            val hits = client.searchCode(call.query, call.fileExtension.ifEmpty { null })
            isTainted = true
            if (hits.isEmpty()) "Nothing found on GitHub for '${call.query}'"
            else Untrusted.wrap(
                hits.joinToString("\n") { "${Untrusted.sanitizeName(it.repository)}: ${Untrusted.sanitizeName(it.path)}\n  ${it.rawURL}" },
                "github search",
                nonce,
            )
        }
        is DownloadResource -> {
            val client = gitHub ?: throw CatalogError.Malformed()
            val data = downloaded.remove(call.url) ?: client.fetchRaw(call.url)
            flipper.write(call.path, data)
            "Wrote ${data.size} bytes from ${call.url} to ${call.path}"
        }
        RefreshDeviceKnowledge -> {
            val store = profileStore ?: throw CatalogError.Malformed()
            val profile = store.build(flipper)
            onProfileRefreshed?.invoke(profile)
            isTainted = true
            profile.promptSummary(nonce)
        }
        CheckFirmware -> {
            val checker = firmwareChecker ?: throw CatalogError.Malformed()
            val info = flipper.deviceInfo()
            val status = checker.status(info["firmware_origin_fork"], info["firmware_version"])
                ?: return "Unknown firmware distribution (fork ${Untrusted.sanitizeName(info["firmware_origin_fork"] ?: "?")}, version ${Untrusted.sanitizeName(info["firmware_version"] ?: "?")})."
            "${status.summary}\nReleases: ${status.distribution.releasesURL}\n${status.distribution.notes}"
        }
        RestartDevice -> {
            val controls = appControls ?: throw CatalogError.Malformed()
            controls.restartDevice()
            "The Flipper is restarting. The app reconnects on its own; further device actions fail until then."
        }
        is GetAuditLog -> {
            val entries = audit.recent(call.limit)
            if (entries.isEmpty()) "The audit log is empty."
            else {
                isTainted = true
                val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
                Untrusted.wrap(
                    entries.joinToString("\n") { record ->
                        val time = formatter.format(record.date)
                        "$time ${record.tool} [${record.decision.name}, ${if (record.succeeded) "ok" else "failed"}] ${Untrusted.sanitizeName(record.summary)}"
                    },
                    "audit log",
                    nonce,
                )
            }
        }
        GetAppSettings -> {
            val controls = appControls ?: throw CatalogError.Malformed()
            val snapshot = controls.settings()
            val engagement = if (snapshot.engagementActive) "armed (${snapshot.engagementSummary})" else "off"
            """
            yolo_mode: ${snapshot.yolo}
            yolo_asks_after_reading_device_content: ${snapshot.yoloAsksAfterReading}
            auto_approve_medium: ${snapshot.autoApproveMedium}
            engagement_mode: $engagement
            read_replies_aloud: ${snapshot.readAloud}
            auto_connect_on_launch: ${snapshot.autoConnect}
            model: ${snapshot.model}
            ai_provider: ${snapshot.provider}
            api_base_url: ${snapshot.apiBaseURL}
            api_key_stored: ${snapshot.apiKeyStored}
            """.trimIndent()
        }
        is SetReadAloud -> {
            val controls = appControls ?: throw CatalogError.Malformed()
            controls.setReadAloud(call.enabled)
            if (call.enabled) "Replies are read aloud now." else "Replies are no longer read aloud."
        }
        is SetAutoConnect -> {
            val controls = appControls ?: throw CatalogError.Malformed()
            controls.setAutoConnect(call.enabled)
            if (call.enabled) "The app connects to the last Flipper on launch." else "Auto-connect is off."
        }
        is SetYoloMode -> {
            val controls = appControls ?: throw CatalogError.Malformed()
            controls.setYolo(call.enabled)
            if (call.enabled) "YOLO mode is on for this session." else "YOLO mode is off; risky actions ask again."
        }
        is SetYoloAsksAfterReading -> {
            val controls = appControls ?: throw CatalogError.Malformed()
            controls.setYoloAsksAfterReading(call.enabled)
            if (call.enabled) "YOLO mode asks again after device content was read." else "YOLO mode no longer asks after device content was read."
        }
        is SetAutoApproveMedium -> {
            val controls = appControls ?: throw CatalogError.Malformed()
            controls.setAutoApproveMedium(call.enabled)
            if (call.enabled) "Medium-risk actions no longer ask." else "Medium-risk actions ask again."
        }
        is SetModel -> {
            val controls = appControls ?: throw CatalogError.Malformed()
            controls.setModel(call.model)
            "The next chat uses ${call.model}."
        }
        is SetProvider -> {
            val controls = appControls ?: throw CatalogError.Malformed()
            controls.setProvider(call.provider, call.baseURL)
            "The next chat uses ${call.provider.displayName}. Previous conversation is not forwarded."
        }
        ListModels -> {
            val controls = appControls ?: throw CatalogError.Malformed()
            val models = controls.listModels()
            isTainted = true
            Untrusted.wrap(models.joinToString("\n") { "${it.id}: ${it.name}" }, "provider model catalog", nonce)
        }
        TestConnection -> {
            val controls = appControls ?: throw CatalogError.Malformed()
            controls.testConnection()
            "The selected model replied to the test prompt."
        }
        InstallFirmwareUpdate -> {
            val controls = appControls ?: throw CatalogError.Malformed()
            controls.startFirmwareUpdate()
        }
        FirmwareUpdateStatus -> {
            val controls = appControls ?: throw CatalogError.Malformed()
            controls.firmwareUpdateStatus()
        }
        CancelFirmwareUpdate -> {
            val controls = appControls ?: throw CatalogError.Malformed()
            controls.cancelFirmwareUpdate()
        }
        LookAtScreen -> attachScreen()
        is PressButtons -> {
            for (step in call.keys) {
                flipper.press(step.key, step.long)
                delay(120)
            }
            val pressed = call.keys.joinToString(", ") { if (it.long) "long ${it.key.wire}" else it.key.wire }
            if (!call.lookAfter) "Pressed $pressed."
            else {
                delay(250)
                "Pressed $pressed. " + attachScreen()
            }
        }
        is SetDeviceName -> {
            flipper.setDeviceName(call.name)
            "Renamed to ${call.name}. The Flipper shows the new name after a restart."
        }
        AlertDevice -> {
            flipper.playAlert()
            "The Flipper is beeping and blinking"
        }
    }

    private suspend fun previewDiff(call: AgentCall): String? {
        if (call !is CoreCall) return null
        return when (call) {
            is WriteFile -> {
                val existing = try {
                    val info = flipper.stat(call.path)
                    if (!info.isDirectory && info.size <= maxReadBytes) {
                        val data = flipper.read(call.path, maxReadBytes)
                        decodeUtf8(data)
                    } else null
                } catch (_: Exception) {
                    null
                }
                SimpleDiff.render(existing, call.content)
            }
            is InstallFapHubApp -> {
                val found = try { findApp(call.query) } catch (_: Exception) { null } ?: return null
                val (app, client, api) = found
                val data = try { client.downloadBuild(app.versionID, api) } catch (_: Exception) { return null }
                downloaded[app.versionID] = data
                "${app.name} v${app.version} by the official catalog\n${app.summary}\n${data.size} bytes"
            }
            is DownloadResource -> {
                val client = gitHub ?: return null
                val data = try { client.fetchRaw(call.url) } catch (_: Exception) { return null }
                downloaded[call.url] = data
                val text = if (data.any { it == 0.toByte() }) null else decodeUtf8(data)
                if (text != null) SimpleDiff.render(null, text, maxLines = 40) else "Binary file, ${data.size} bytes"
            }
            is ForgePayload -> {
                val forger = forge ?: return null
                val content = try { forger.forge(call.kind, call.description) } catch (_: Exception) { return null }
                forged[call.path] = content
                val existing = try { decodeUtf8(flipper.read(call.path, maxReadBytes)) } catch (_: Exception) { null }
                SimpleDiff.render(existing, content, maxLines = 60)
            }
            is DeletePath -> {
                val info = try { flipper.stat(call.path) } catch (_: Exception) { return null }
                if (info.isDirectory) "Folder ${Untrusted.sanitizeName(info.name)}" else "File ${Untrusted.sanitizeName(info.name)}, ${info.size} bytes"
            }
            else -> null
        }
    }

    companion object {
        const val maxReadBytes = 16 * 1024

        private fun decodeUtf8(data: ByteArray): String? = try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(data)).toString()
        } catch (_: Exception) {
            null
        }
    }
}
