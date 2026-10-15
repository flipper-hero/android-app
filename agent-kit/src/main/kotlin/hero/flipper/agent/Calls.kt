package hero.flipper.agent

import hero.flipper.kit.FlipperKey
import org.json.JSONArray
import org.json.JSONObject

interface AgentCall {
    val toolName: String
    val summary: String
    val requiresExplicitConsent: Boolean get() = false
    val alwaysAsk: Boolean get() = false
}

sealed interface CoreCall : AgentCall

data class ListDirectory(val path: String) : CoreCall {
    override val toolName = "list_directory"
    override val summary = "List $path"
}
data class ReadFile(val path: String) : CoreCall {
    override val toolName = "read_file"
    override val summary = "Read $path"
}
data object GetDeviceInfo : CoreCall {
    override val toolName = "get_device_info"
    override val summary = "Read device info"
}
data object GetPowerInfo : CoreCall {
    override val toolName = "get_power_info"
    override val summary = "Read battery info"
}
data class GetStorageInfo(val path: String) : CoreCall {
    override val toolName = "get_storage_info"
    override val summary = "Read storage info for $path"
}
data class CreateDirectory(val path: String) : CoreCall {
    override val toolName = "create_directory"
    override val summary = "Create folder $path"
}
data class WriteFile(val path: String, val content: String) : CoreCall {
    override val toolName = "write_file"
    override val summary = "Write ${content.encodeToByteArray().size} bytes to $path"
}
data class RenamePath(val from: String, val to: String) : CoreCall {
    override val toolName = "rename"
    override val summary = "Move $from to $to"
}
data class LaunchApp(val name: String, val args: String) : CoreCall {
    override val toolName = "launch_app"
    override val summary = if (args.isEmpty()) "Launch app $name" else "Launch app $name with argument $args"
}
data class DeletePath(val path: String, val recursive: Boolean) : CoreCall {
    override val toolName = "delete"
    override val summary = if (recursive) "Delete $path and everything inside" else "Delete $path"
}
data class TransmitSubGhz(val path: String) : CoreCall {
    override val toolName = "transmit_subghz"
    override val summary = "Transmit the radio signal $path"
}
data class TransmitInfrared(val path: String, val button: String) : CoreCall {
    override val toolName = "transmit_infrared"
    override val summary = if (button.isEmpty()) "Send the infrared remote $path" else "Send infrared button '$button' from $path"
}
data class EmulateNfc(val path: String) : CoreCall {
    override val toolName = "emulate_nfc"
    override val summary = "Emulate the NFC card $path"
}
data class EmulateRfid(val path: String) : CoreCall {
    override val toolName = "emulate_rfid"
    override val summary = "Emulate the 125 kHz tag $path"
}
data class EmulateIButton(val path: String) : CoreCall {
    override val toolName = "emulate_ibutton"
    override val summary = "Emulate the iButton key $path"
}
data class LoadBadKeyboardScript(val path: String) : CoreCall {
    override val toolName = "load_badusb_script"
    override val summary = "Open Bad KB with the script $path loaded (not started)"
}
data object StopApp : CoreCall {
    override val toolName = "stop_app"
    override val summary = "Close the app running on the Flipper"
}
data object AlertDevice : CoreCall {
    override val toolName = "alert_device"
    override val summary = "Make the Flipper beep and blink"
}
data class ForgePayload(val kind: PayloadKind, val description: String, val path: String) : CoreCall {
    override val toolName = "forge_payload"
    override val summary = "Generate a ${kind.wire} file from \"$description\" and write it to $path"
}
data class SearchFapHub(val query: String) : CoreCall {
    override val toolName = "search_faphub"
    override val summary = "Search the Flipper app catalog for \"$query\""
}
data class InstallFapHubApp(val query: String) : CoreCall {
    override val toolName = "install_faphub_app"
    override val summary = "Download and install the app \"$query\" from the official catalog"
}
data class SearchGitHub(val query: String, val fileExtension: String) : CoreCall {
    override val toolName = "github_search"
    override val summary = if (fileExtension.isEmpty()) "Search GitHub for \"$query\"" else "Search GitHub for \"$query\" (.$fileExtension files)"
}
data class DownloadResource(val url: String, val path: String) : CoreCall {
    override val toolName = "download_resource"
    override val summary = "Download $url to $path"
}
data object RefreshDeviceKnowledge : CoreCall {
    override val toolName = "refresh_device_knowledge"
    override val summary = "Re-read what is installed and saved on the Flipper"
}
data class SetDeviceName(val name: String) : CoreCall {
    override val toolName = "set_device_name"
    override val summary = "Rename the Flipper to \"$name\" (applies after a restart)"
}
data object CheckFirmware : CoreCall {
    override val toolName = "check_firmware"
    override val summary = "Check the firmware for updates"
}
data object RestartDevice : CoreCall {
    override val toolName = "restart_device"
    override val summary = "Restart the Flipper"
}
data class GetAuditLog(val limit: Int) : CoreCall {
    override val toolName = "get_audit_log"
    override val summary = "Show the last $limit actions from the audit log"
}
data object GetAppSettings : CoreCall {
    override val toolName = "get_app_settings"
    override val summary = "Read the app settings"
}
data class SetReadAloud(val enabled: Boolean) : CoreCall {
    override val toolName = "set_read_aloud"
    override val summary = if (enabled) "Read replies aloud" else "Stop reading replies aloud"
}
data class SetAutoConnect(val enabled: Boolean) : CoreCall {
    override val toolName = "set_auto_connect"
    override val summary = if (enabled) "Connect to the last Flipper on launch" else "Stop connecting automatically on launch"
}
data class SetYoloMode(val enabled: Boolean) : CoreCall {
    override val toolName = "set_yolo_mode"
    override val summary = if (enabled) "Turn on YOLO mode: changes, deletes and transmissions run without asking" else "Turn off YOLO mode"
    override val requiresExplicitConsent = enabled
}
data class SetYoloAsksAfterReading(val enabled: Boolean) : CoreCall {
    override val toolName = "set_yolo_asks_after_reading"
    override val summary = if (enabled) "Ask again after reading Flipper content, even in YOLO mode" else "Stop asking after reading Flipper content in YOLO mode"
    override val requiresExplicitConsent = !enabled
}
data class SetAutoApproveMedium(val enabled: Boolean) : CoreCall {
    override val toolName = "set_auto_approve_medium"
    override val summary = if (enabled) "Skip prompts for medium-risk actions" else "Ask again for medium-risk actions"
    override val requiresExplicitConsent = enabled
}
data class SetModel(val model: String) : CoreCall {
    override val toolName = "set_model"
    override val summary = "Switch the model to $model"
}
data class SetProvider(val provider: AIProvider, val baseURL: String?) : CoreCall {
    override val toolName = "set_ai_provider"
    override val summary = "Switch the AI provider to ${provider.displayName}"
}
data object ListModels : CoreCall {
    override val toolName = "list_models"
    override val summary = "Choose model"
}
data object TestConnection : CoreCall {
    override val toolName = "test_model_connection"
    override val summary = "Test connection"
}
data object LookAtScreen : CoreCall {
    override val toolName = "look_at_screen"
    override val summary = "Look at the Flipper's screen"
}
data object InstallFirmwareUpdate : CoreCall {
    override val toolName = "install_firmware_update"
    override val summary = "Download and install the newest firmware release on the Flipper"
}
data object FirmwareUpdateStatus : CoreCall {
    override val toolName = "firmware_update_status"
    override val summary = "Check how the firmware update is going"
}
data object CancelFirmwareUpdate : CoreCall {
    override val toolName = "cancel_firmware_update"
    override val summary = "Cancel the running firmware update"
}
data class PressButtons(val keys: List<ButtonStep>, val lookAfter: Boolean) : CoreCall {
    override val toolName = "press_buttons"
    override val summary = pressSummary(keys)
}

data class ButtonStep(val key: FlipperKey, val long: Boolean = false) {
    companion object {
        const val maxPerCall = 12
        fun parse(text: String): ButtonStep? {
            var name = text.lowercase().trim()
            val long = name.startsWith("long_") || name.startsWith("long ")
            if (long) name = name.drop(5)
            val key = FlipperKey.entries.firstOrNull { it.wire == name } ?: return null
            return ButtonStep(key, long)
        }
    }
}

private fun pressSummary(keys: List<ButtonStep>): String {
    val list = keys.joinToString(", ") { if (it.long) "long ${it.key.wire}" else it.key.wire }
    return "Press $list on the Flipper"
}

enum class PayloadKind(val wire: String, val fileExtension: String) {
    Badusb("badusb", "txt"),
    Subghz("subghz", "sub"),
    Infrared("infrared", "ir"),
    Nfc("nfc", "nfc"),
    Rfid("rfid", "rfid"),
    Ibutton("ibutton", "ibtn"),
    ;

    companion object {
        fun from(raw: String): PayloadKind? = entries.firstOrNull { it.wire == raw.lowercase() }
    }
}

class ToolSpec(val name: String, val description: String, val parameters: JSONObject)

object ToolCatalog {
    const val maxWriteBytes = 32 * 1024

    fun specs(edition: AgentEdition): List<ToolSpec> = coreSpecs + edition.extraSpecs

    private fun objectSpec(props: Map<String, Pair<String, String>>, required: List<String>): JSONObject {
        val properties = JSONObject()
        for ((key, typeDoc) in props) {
            properties.put(key, JSONObject().put("type", typeDoc.first).put("description", typeDoc.second))
        }
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(required))
            .put("additionalProperties", false)
    }

    fun spec(name: String, description: String, props: Map<String, Pair<String, String>> = emptyMap(), required: List<String> = emptyList()) =
        ToolSpec(name, description, objectSpec(props, required))

    val coreSpecs: List<ToolSpec> = listOf(
        spec("list_directory", "List files and folders at an absolute Flipper path such as /ext/nfc.", mapOf("path" to ("string" to "Absolute path under /ext")), listOf("path")),
        spec("read_file", "Read a small text file (max 16 KB). Binary files return only a summary.", mapOf("path" to ("string" to "Absolute file path")), listOf("path")),
        spec("get_device_info", "Hardware name, firmware version and commit of the connected Flipper."),
        spec("get_power_info", "Battery charge and charging state."),
        spec("get_storage_info", "Total and free space of the SD card (/ext).", mapOf("path" to ("string" to "Storage root, default /ext"))),
        spec("create_directory", "Create a folder. Requires user approval.", mapOf("path" to ("string" to "Absolute folder path")), listOf("path")),
        spec("write_file", "Create or overwrite a text file (max 32 KB). Requires user approval and shows a diff.", mapOf("path" to ("string" to "Absolute file path"), "content" to ("string" to "Full new file content")), listOf("path", "content")),
        spec("rename", "Move or rename a file or folder. Requires user approval.", mapOf("from" to ("string" to "Existing absolute path"), "to" to ("string" to "New absolute path")), listOf("from", "to")),
        spec("launch_app", "Start an app on the Flipper by its name. Passing an argument always needs explicit user approval.", mapOf("name" to ("string" to "App name, e.g. Sub-GHz"), "args" to ("string" to "Optional app argument")), listOf("name")),
        spec("delete", "Delete a file or folder. Always requires explicit user approval.", mapOf("path" to ("string" to "Absolute path"), "recursive" to ("boolean" to "Delete folder contents too")), listOf("path")),
        spec("transmit_subghz", "Transmit a saved .sub radio signal once. Real-world effect, always needs user approval.", mapOf("path" to ("string" to "Absolute path to a .sub file")), listOf("path")),
        spec("transmit_infrared", "Send a saved .ir remote command once. Real-world effect, always needs user approval.", mapOf("path" to ("string" to "Absolute path to an .ir file"), "button" to ("string" to "Button name inside the remote, e.g. Power")), listOf("path")),
        spec("emulate_nfc", "Emulate a saved .nfc card until stopped. Real-world effect, always needs user approval.", mapOf("path" to ("string" to "Absolute path to a .nfc file")), listOf("path")),
        spec("emulate_rfid", "Emulate a saved .rfid tag until stopped. Real-world effect, always needs user approval.", mapOf("path" to ("string" to "Absolute path to a .rfid file")), listOf("path")),
        spec("emulate_ibutton", "Emulate a saved .ibtn key until stopped. Real-world effect, always needs user approval.", mapOf("path" to ("string" to "Absolute path to an .ibtn file")), listOf("path")),
        spec("load_badusb_script", "Open Bad KB with a script loaded. It is NOT started; the user still presses Run on the Flipper itself.", mapOf("path" to ("string" to "Absolute path to a .txt script")), listOf("path")),
        spec("stop_app", "Close whatever app is currently running on the Flipper, which also stops emulation."),
        spec("alert_device", "Make the Flipper beep, blink and vibrate so the user can find it."),
        spec(
            "forge_payload",
            "Generate a Flipper file from a description and write it to the Flipper. The full generated content is shown to the user for approval before anything is written.",
            mapOf(
                "type" to ("string" to "One of: badusb, subghz, infrared, nfc, rfid, ibutton"),
                "description" to ("string" to "What the file should do, in plain language"),
                "path" to ("string" to "Absolute target path, e.g. /ext/badusb/demo.txt"),
            ),
            listOf("type", "description", "path"),
        ),
        spec("search_faphub", "Search the official Flipper app catalog. Only returns apps built for the firmware the connected device is running.", mapOf("query" to ("string" to "What to search for")), listOf("query")),
        spec("install_faphub_app", "Download an app from the official catalog and install it on the Flipper. Installs executable code, so it always needs user approval.", mapOf("query" to ("string" to "App name or alias, as returned by search_faphub")), listOf("query")),
        spec("github_search", "Search GitHub for Flipper-compatible files, for example .sub or .ir remotes. Returns repository, path and a raw URL.", mapOf("query" to ("string" to "Search terms"), "extension" to ("string" to "Restrict to a file extension, e.g. ir or sub")), listOf("query")),
        spec("download_resource", "Download a raw file from GitHub and write it to the Flipper. The content is shown to the user before anything is written.", mapOf("url" to ("string" to "Raw GitHub URL from github_search"), "path" to ("string" to "Absolute target path on the Flipper")), listOf("url", "path")),
        spec("refresh_device_knowledge", "Re-read the Flipper's firmware, installed apps and saved files. Use when the inventory looks stale or something you expected is missing."),
        spec("set_device_name", "Rename the Flipper. At most 8 characters, letters, digits, hyphen and underscore. Takes effect after the device restarts.", mapOf("name" to ("string" to "New device name, max 8 characters")), listOf("name")),
        spec("check_firmware", "Identify the installed firmware distribution and look up its newest release."),
        spec("restart_device", "Restart the Flipper, for example to apply a new name. The connection drops and the app reconnects."),
        spec("get_audit_log", "List the most recent actions taken in this app, with the approval decision for each.", mapOf("limit" to ("integer" to "How many entries, 1 to 50, default 10"))),
        spec("get_app_settings", "Read the app settings: YOLO mode, approval switches, read-aloud, auto-connect, model, and whether an API key is stored."),
        spec("set_read_aloud", "Turn reading replies aloud on or off.", mapOf("enabled" to ("boolean" to "true to read replies aloud")), listOf("enabled")),
        spec("set_auto_connect", "Turn connecting to the last Flipper on app launch on or off.", mapOf("enabled" to ("boolean" to "true to auto-connect")), listOf("enabled")),
        spec("set_yolo_mode", "Turn YOLO mode on or off. Turning it on always shows the user a permission dialog; you cannot enable it yourself.", mapOf("enabled" to ("boolean" to "true to request YOLO mode")), listOf("enabled")),
        spec("set_yolo_asks_after_reading", "Whether YOLO mode still asks after you read content from the Flipper. Turning this off always needs the user's permission.", mapOf("enabled" to ("boolean" to "true to keep asking after reading device content")), listOf("enabled")),
        spec("set_auto_approve_medium", "Skip prompts for medium-risk actions. Turning it on always needs the user's permission.", mapOf("enabled" to ("boolean" to "true to skip medium-risk prompts")), listOf("enabled")),
        spec("set_model", "Switch the model used for the next chat. Use an ID from list_models or provider documentation.", mapOf("model" to ("string" to "Provider model ID")), listOf("model")),
        spec("set_ai_provider", "Select the AI provider for the next chat. Optional base_url must be an HTTPS API base on that provider's own domain. Never reads or writes keys.", mapOf("provider" to ("string" to "Provider ID"), "base_url" to ("string" to "Optional regional API base URL")), listOf("provider")),
        spec("list_models", "Fetch the selected provider's model catalog using its stored key. Credentials are never returned."),
        spec("test_model_connection", "Send a tiny fixed OK prompt to the selected model using the stored key. May incur provider charges. Sends no conversation or device data."),
        spec("install_firmware_update", "Download the newest release of the installed firmware distribution and install it over Bluetooth."),
        spec("firmware_update_status", "Progress of a running firmware update, or the result of the last one."),
        spec("cancel_firmware_update", "Cancel a running firmware update. Works while downloading or uploading."),
        spec("look_at_screen", "Capture the Flipper's 128x64 screen and look at it as an image."),
        ToolSpec(
            "press_buttons",
            "Press buttons on the Flipper like a finger would. Keys: up, down, left, right, ok, back; prefix with long_ for a long press.",
            objectSpec(mapOf("keys" to ("array" to "Sequence, at most 12"), "look_after" to ("boolean" to "Capture the screen afterwards, default true")), listOf("keys")),
        ),
    )
}

object RiskAssessor {
    private val executableRoots = listOf("/ext/apps", "/ext/update", "/ext/badusb", "/ext/apps_data/js_app")
    private val executableExtensions = setOf("fap", "js", "dfu", "bin", "elf", "tgz", "fuf")

    fun assess(call: AgentCall, edition: AgentEdition): RiskAssessment {
        if (call is CoreCall) return assessCore(call)
        return edition.risk(call) ?: RiskAssessment(RiskLevel.Blocked, listOf("invalid path"))
    }

    private fun assessCore(call: CoreCall): RiskAssessment = when (call) {
        GetDeviceInfo, GetPowerInfo -> low("read-only")
        is GetStorageInfo -> pathRead(call.path)
        is ListDirectory -> pathRead(call.path)
        is ReadFile -> {
            val read = pathRead(call.path)
            if (read.level != RiskLevel.Blocked && FlipperGuardsExt.sensitive(call.path)) {
                RiskAssessment(RiskLevel.Blocked, listOf("reading key or secret files is not allowed"))
            } else read
        }
        is CreateDirectory -> pathWrite(call.path, "creates a folder")
        is WriteFile -> pathWrite(call.path, "writes a file")
        is RenamePath -> combine(pathWrite(call.from, "moves a file"), pathWrite(call.to, "moves a file"))
        is LaunchApp -> if (call.args.isNotEmpty()) {
            RiskAssessment(RiskLevel.High, listOf("app '${call.name}' receives an argument (it may run or transmit a file)"))
        } else {
            RiskAssessment(RiskLevel.Medium, listOf("starts an app on the device"))
        }
        is TransmitSubGhz -> physical(call.path, "transmits a radio signal")
        is TransmitInfrared -> physical(call.path, "sends an infrared command")
        is EmulateNfc -> physical(call.path, "emulates this card to any nearby reader")
        is EmulateRfid -> physical(call.path, "emulates this 125 kHz tag to any nearby reader")
        is EmulateIButton -> physical(call.path, "emulates this iButton key")
        is LoadBadKeyboardScript -> physical(call.path, "loads a keystroke-injection script (you still start it on the Flipper)")
        StopApp -> low("closes the app running on the Flipper")
        InstallFirmwareUpdate -> RiskAssessment(RiskLevel.High, listOf("replaces the firmware; takes 15 to 25 minutes and the Flipper restarts"))
        FirmwareUpdateStatus -> low("read-only")
        CancelFirmwareUpdate -> low("stops an update before anything is installed")
        LookAtScreen -> low("takes a screenshot of the Flipper")
        is PressButtons -> RiskAssessment(RiskLevel.High, listOf("presses buttons in whatever app is open, which can start transmissions or delete data"))
        CheckFirmware, is GetAuditLog, GetAppSettings, ListModels -> low("read-only")
        is SetReadAloud, is SetAutoConnect -> low("changes a harmless app preference")
        RestartDevice -> RiskAssessment(RiskLevel.Medium, listOf("restarts the Flipper; whatever runs on it stops and the connection drops briefly"))
        is SetModel, is SetProvider -> RiskAssessment(RiskLevel.Medium, listOf("changes which model provider receives your conversation and file contents"))
        TestConnection -> RiskAssessment(RiskLevel.Medium, listOf("sends a paid test message to your model provider"))
        is SetYoloMode -> if (call.enabled) RiskAssessment(RiskLevel.High, listOf("the agent could then change, delete and transmit without asking you")) else low("makes the agent ask again")
        is SetAutoApproveMedium -> if (call.enabled) RiskAssessment(RiskLevel.High, listOf("medium-risk changes would no longer ask you")) else low("makes the agent ask again")
        is SetYoloAsksAfterReading -> if (call.enabled) low("makes the agent ask again after reading device content") else RiskAssessment(RiskLevel.High, listOf("removes the protection against instructions planted in files on the Flipper"))
        is SetDeviceName -> RiskAssessment(RiskLevel.Medium, listOf("changes the device name and its Bluetooth identity"))
        RefreshDeviceKnowledge -> low("reads the device inventory")
        is SearchFapHub, is SearchGitHub -> low("searches the internet, changes nothing")
        is InstallFapHubApp -> RiskAssessment(RiskLevel.High, listOf("installs an app '${call.query}' that can run code on the device"))
        is DownloadResource -> pathWrite(call.path, "writes a file downloaded from the internet")
        is ForgePayload -> pathWrite(call.path, "writes a generated file")
        AlertDevice -> low("beeps and blinks so you can find the device")
        is DeletePath -> {
            val normalized = normalizedOrNull(call.path)
            val depth = normalized?.split('/')?.count { it.isNotEmpty() } ?: 0
            when {
                normalized == null -> invalid()
                hero.flipper.kit.FlipperGuards.blockedRoot(normalized) -> RiskAssessment(RiskLevel.Blocked, listOf("protected path"))
                call.recursive && depth <= 2 -> RiskAssessment(RiskLevel.Blocked, listOf("refusing recursive delete of a top-level folder"))
                else -> RiskAssessment(RiskLevel.High, listOf(if (call.recursive) "deletes a folder and its contents" else "deletes data"))
            }
        }
    }

    private fun low(why: String) = RiskAssessment(RiskLevel.Low, listOf(why))
    private fun invalid() = RiskAssessment(RiskLevel.Blocked, listOf("invalid path"))

    private fun pathRead(path: String): RiskAssessment {
        val normalized = normalizedOrNull(path) ?: return invalid()
        if (hero.flipper.kit.FlipperGuards.blockedRoot(normalized)) return RiskAssessment(RiskLevel.Blocked, listOf("protected path (internal storage)"))
        return low("read-only")
    }

    private fun pathWrite(path: String, why: String): RiskAssessment {
        val normalized = normalizedOrNull(path) ?: return invalid()
        if (hero.flipper.kit.FlipperGuards.blockedRoot(normalized)) return RiskAssessment(RiskLevel.Blocked, listOf("protected path (internal storage)"))
        var level = RiskLevel.Medium
        val reasons = mutableListOf(why)
        val ext = normalized.substringAfterLast('.', "").lowercase()
        val underExecutable = executableRoots.any { normalized == it || normalized.startsWith("$it/") }
        if (underExecutable || ext in executableExtensions) {
            level = RiskLevel.High
            reasons.add("target can execute code or reconfigure the device")
        }
        val comps = normalized.split('/').filter { it.isNotEmpty() }
        if (comps.size == 2 && comps[1].startsWith(".")) {
            level = RiskLevel.High
            reasons.add("hidden configuration file at the SD card root")
        }
        return RiskAssessment(level, reasons)
    }

    private fun physical(path: String, why: String): RiskAssessment {
        val normalized = normalizedOrNull(path) ?: return invalid()
        if (hero.flipper.kit.FlipperGuards.blockedRoot(normalized)) return RiskAssessment(RiskLevel.Blocked, listOf("protected path (internal storage)"))
        return RiskAssessment(RiskLevel.High, listOf(why))
    }

    private fun combine(a: RiskAssessment, b: RiskAssessment): RiskAssessment {
        val level = if (a.level.ordinal > b.level.ordinal) a.level else b.level
        return RiskAssessment(level, (a.reasons + b.reasons).toSet().sorted())
    }
}

private object FlipperGuardsExt {
    private val sensitive = setOf("key", "pem", "priv", "secret", "p12")
    fun sensitive(path: String): Boolean {
        val normalized = normalizedOrNull(path) ?: return false
        return normalized.substringAfterLast('.', "").lowercase() in sensitive
    }
}

fun parseCall(name: String, arguments: String, edition: AgentEdition): AgentCall {
    val args = parseArguments(arguments)
    return parseCore(name, args, edition) ?: edition.tryParse(name, args) ?: throw ToolError.UnknownTool(name)
}

private fun parseCore(name: String, args: JSONObject, edition: AgentEdition): AgentCall? {
    fun string(key: String, optional: Boolean = false) = args.stringArg(key, optional)
    fun bool(key: String) = args.boolArg(key)
    return when (name) {
        "list_directory" -> ListDirectory(string("path"))
        "read_file" -> ReadFile(string("path"))
        "get_device_info" -> GetDeviceInfo
        "get_power_info" -> GetPowerInfo
        "get_storage_info" -> {
            val path = string("path", optional = true)
            GetStorageInfo(if (path.isEmpty()) "/ext" else path)
        }
        "create_directory" -> CreateDirectory(string("path"))
        "write_file" -> {
            val content = string("content")
            if (content.encodeToByteArray().size > ToolCatalog.maxWriteBytes) {
                throw ToolError.BadArguments("content larger than ${ToolCatalog.maxWriteBytes} bytes")
            }
            WriteFile(string("path"), content)
        }
        "rename", "move" -> RenamePath(string("from"), string("to"))
        "launch_app" -> {
            val appName = string("name")
            if (appName.isEmpty() || appName.length > 128 || appName.any { it.code < 0x20 || it.code == 0x7F }) {
                throw ToolError.BadArguments("invalid app name")
            }
            LaunchApp(appName, string("args", optional = true))
        }
        "delete" -> DeletePath(string("path"), args.optionalBool("recursive") ?: false)
        "transmit_subghz" -> TransmitSubGhz(string("path"))
        "transmit_infrared" -> TransmitInfrared(string("path"), string("button", optional = true))
        "emulate_nfc" -> EmulateNfc(string("path"))
        "emulate_rfid" -> EmulateRfid(string("path"))
        "emulate_ibutton" -> EmulateIButton(string("path"))
        "load_badusb_script" -> LoadBadKeyboardScript(string("path"))
        "stop_app" -> StopApp
        "alert_device" -> AlertDevice
        "search_faphub" -> SearchFapHub(string("query"))
        "install_faphub_app" -> InstallFapHubApp(string("query"))
        "github_search" -> SearchGitHub(string("query"), string("extension", optional = true))
        "refresh_device_knowledge" -> RefreshDeviceKnowledge
        "set_device_name" -> SetDeviceName(string("name"))
        "check_firmware" -> CheckFirmware
        "look_at_screen" -> LookAtScreen
        "install_firmware_update" -> InstallFirmwareUpdate
        "firmware_update_status" -> FirmwareUpdateStatus
        "cancel_firmware_update" -> CancelFirmwareUpdate
        "press_buttons" -> {
            val raw = args.optJSONArray("keys") ?: throw ToolError.BadArguments("keys must be a non-empty array")
            if (raw.length() == 0) throw ToolError.BadArguments("keys must be a non-empty array")
            if (raw.length() > ButtonStep.maxPerCall) throw ToolError.BadArguments("at most ${ButtonStep.maxPerCall} presses per call")
            val steps = (0 until raw.length()).map { index ->
                val text = raw.optString(index, "")
                ButtonStep.parse(text) ?: throw ToolError.BadArguments("unknown key '$text'")
            }
            PressButtons(steps, args.optionalBool("look_after") ?: true)
        }
        "restart_device" -> RestartDevice
        "get_audit_log" -> GetAuditLog((args.optionalInt("limit") ?: 10).coerceIn(1, 50))
        "get_app_settings" -> GetAppSettings
        "set_read_aloud" -> SetReadAloud(bool("enabled"))
        "set_auto_connect" -> SetAutoConnect(bool("enabled"))
        "set_yolo_mode" -> SetYoloMode(bool("enabled"))
        "set_yolo_asks_after_reading" -> SetYoloAsksAfterReading(bool("enabled"))
        "set_auto_approve_medium" -> SetAutoApproveMedium(bool("enabled"))
        "set_model" -> {
            val model = string("model").trim()
            if (model.isEmpty() || model.length > 120 || model.any { !(it.isLetterOrDigit() || "/-_.:".contains(it)) }) {
                throw ToolError.BadArguments("invalid model id")
            }
            SetModel(model)
        }
        "set_ai_provider" -> {
            val provider = AIProvider.from(string("provider")) ?: throw ToolError.BadArguments("unknown AI provider")
            val base = if (args.has("base_url") && args.get("base_url") is String) args.getString("base_url") else null
            if (base != null) provider.validatedBaseURL(base)
            SetProvider(provider, base)
        }
        "list_models" -> ListModels
        "test_model_connection" -> TestConnection
        "download_resource" -> DownloadResource(string("url"), string("path"))
        "forge_payload" -> {
            val raw = string("type")
            val kind = PayloadKind.from(raw) ?: throw ToolError.BadArguments("unknown payload type '$raw'")
            if (kind == PayloadKind.Badusb && !edition.forgeBadUsb) {
                throw ToolError.BadArguments("badusb payloads are not available in this edition")
            }
            val description = string("description")
            var target = string("path", optional = true)
            if (target.isEmpty()) throw ToolError.BadArguments("missing target 'path'")
            if (!target.lowercase().endsWith(".${kind.fileExtension}")) target += ".${kind.fileExtension}"
            ForgePayload(kind, description, target)
        }
        else -> null
    }
}
