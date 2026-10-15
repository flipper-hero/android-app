package hero.flipper.agent

import hero.flipper.kit.FlipperApp
import hero.flipper.kit.FlipperDirEntry
import hero.flipper.kit.FlipperKey
import hero.flipper.kit.FlipperPath
import hero.flipper.kit.FlipperScreenFrame
import hero.flipper.kit.FlipperStorageInfo
import hero.flipper.kit.ScreenPng
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface FlipperControlling {
    suspend fun deviceInfo(): Map<String, String>
    suspend fun powerInfo(): Map<String, String>
    suspend fun storageInfo(path: String): FlipperStorageInfo
    suspend fun list(path: String): List<FlipperDirEntry>
    suspend fun stat(path: String): FlipperDirEntry
    suspend fun read(path: String, maxBytes: Int): ByteArray
    suspend fun write(path: String, data: ByteArray)
    suspend fun makeDirectory(path: String)
    suspend fun delete(path: String, recursive: Boolean)
    suspend fun rename(from: String, to: String)
    suspend fun startApp(name: String, args: String)
    suspend fun transmitOnce(app: FlipperApp, path: String, button: String, hold: Duration)
    suspend fun emulate(app: FlipperApp, path: String)
    suspend fun loadBadKeyboardScript(path: String)
    suspend fun exitApp()
    suspend fun playAlert()
    suspend fun setDeviceName(name: String)
    suspend fun customDeviceName(): String?
    suspend fun captureScreen(timeout: Duration): FlipperScreenFrame
    suspend fun press(key: FlipperKey, long: Boolean)
    suspend fun headerSetMode(pin: String, output: Boolean, pullUp: Boolean?) {
        throw UnsupportedOperationException("not in this edition")
    }
    suspend fun headerRead(pin: String): Boolean = throw UnsupportedOperationException("not in this edition")
    suspend fun headerWrite(pin: String, level: Boolean) {
        throw UnsupportedOperationException("not in this edition")
    }
    suspend fun sendRaw(request: String): String = throw UnsupportedOperationException("not in this edition")
    suspend fun marauder(action: String, mode: String, seconds: Int, index: Int?): String =
        throw UnsupportedOperationException("not in this edition")
}

data class AppSettingsSnapshot(
    var yolo: Boolean,
    var yoloAsksAfterReading: Boolean,
    var autoApproveMedium: Boolean,
    var readAloud: Boolean,
    var autoConnect: Boolean,
    var model: String,
    var apiKeyStored: Boolean,
    var provider: String = "openrouter",
    var apiBaseURL: String = "https://openrouter.ai/api/v1",
    var engagementActive: Boolean = false,
    var engagementSummary: String = "off",
)

interface AppControls {
    suspend fun settings(): AppSettingsSnapshot
    suspend fun setReadAloud(enabled: Boolean)
    suspend fun setAutoConnect(enabled: Boolean)
    suspend fun setYolo(enabled: Boolean)
    suspend fun setYoloAsksAfterReading(enabled: Boolean)
    suspend fun setAutoApproveMedium(enabled: Boolean)
    suspend fun setModel(model: String)
    suspend fun setProvider(provider: AIProvider, baseURL: String?)
    suspend fun listModels(): List<AIModel>
    suspend fun testConnection()
    suspend fun setEngagement(state: EngagementState)
    suspend fun restartDevice()
    suspend fun startFirmwareUpdate(): String
    suspend fun firmwareUpdateStatus(): String
    suspend fun cancelFirmwareUpdate(): String
}

enum class AIProvider(val wire: String, val displayName: String, val baseURL: String, val defaultModel: String) {
    OpenRouter("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-4.5"),
    Ai2342("ai2342", "2342.ai", "https://2342.ai/v1", ""),
    Blackbit("blackbit", "Blackbit", "https://void.blackbit.sh/v1", "zai_glm_5_2"),
    OrcaRouter("orcarouter", "OrcaRouter", "https://api.orcarouter.ai/v1", "orcarouter/auto"),
    Zai("zai", "Z.ai", "https://api.z.ai/api/paas/v4", "glm-5.3-flash"),
    Kimi("kimi", "Kimi", "https://api.moonshot.ai/v1", "kimi-k3"),
    Qwen("qwen", "Qwen Cloud", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    Minimax("minimax", "MiniMax", "https://api.minimax.io/v1", "MiniMax-M3"),
    ;

    fun validatedBaseURL(address: String): String {
        val trimmed = address.trim()
        val uri = try { URI(trimmed) } catch (_: Exception) { throw ProviderError.InvalidEndpoint() }
        val host = uri.host?.lowercase() ?: throw ProviderError.InvalidEndpoint()
        if (uri.scheme != "https" || uri.userInfo != null || uri.query != null || uri.fragment != null) {
            throw ProviderError.InvalidEndpoint()
        }
        if (uri.port != -1 && uri.port != 443) throw ProviderError.InvalidEndpoint()
        val allowed = when (this) {
            OpenRouter -> host == "openrouter.ai"
            Ai2342 -> host == "2342.ai"
            Blackbit -> host == "void.blackbit.sh"
            OrcaRouter -> host == "api.orcarouter.ai"
            Zai -> host == "api.z.ai" || host == "open.bigmodel.cn"
            Kimi -> host == "api.moonshot.ai" || host == "api.moonshot.cn"
            Qwen -> host == "dashscope-intl.aliyuncs.com" || host == "dashscope-us.aliyuncs.com" ||
                host == "dashscope.aliyuncs.com" || host.endsWith(".maas.aliyuncs.com")
            Minimax -> host == "api.minimax.io" || host == "api.minimaxi.com"
        }
        if (!allowed) throw ProviderError.InvalidEndpoint()
        return trimmed
    }

    companion object {
        fun from(raw: String): AIProvider? = entries.firstOrNull { it.wire == raw }
    }
}

sealed class ProviderError(message: String) : Exception(message) {
    class InvalidEndpoint : ProviderError("Use an HTTPS API URL from this provider.")
    class MissingKey : ProviderError("Add an API key in Settings first.")
    class MissingModel : ProviderError("Choose a model first.")
    class EmptyReply : ProviderError("The model did not reply. Try another model.")
    class ModelsUnavailable : ProviderError("No model list is available. Enter a model ID from the provider documentation.")
    class Status(code: Int) : ProviderError("API request failed (HTTP $code). Check your key, model and account credit.")
}

data class AIModel(val id: String, val name: String)

enum class ChatRole { system, user, assistant, tool }

data class ToolCall(val id: String, val name: String, val arguments: String)

data class ChatMessage(
    val role: ChatRole,
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallID: String? = null,
    val images: List<ByteArray> = emptyList(),
)

interface LlmClient {
    suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage
}

class ChatCompletionsClient(
    val apiKey: String,
    val model: String,
    val provider: AIProvider = AIProvider.OpenRouter,
    var connectionTest: Boolean = false,
) {
    fun makeRequestBody(messages: List<ChatMessage>, tools: List<ToolSpec>): ByteArray {
        val wire = JSONArray()
        for (message in messages) {
            val item = JSONObject().put("role", message.role.name)
            if (message.images.isNotEmpty()) {
                val parts = JSONArray()
                if (!message.content.isNullOrEmpty()) {
                    parts.put(JSONObject().put("type", "text").put("text", message.content))
                }
                for (image in message.images) {
                    val mime = if (image.size >= 4 && image[0] == 0x89.toByte() && image[1] == 0x50.toByte()) "image/png" else "image/jpeg"
                    val b64 = java.util.Base64.getEncoder().encodeToString(image)
                    parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$mime;base64,$b64")))
                }
                item.put("content", parts)
            } else if (message.content != null) {
                item.put("content", message.content)
            } else if (message.role == ChatRole.assistant) {
                item.put("content", JSONObject.NULL)
            }
            if (message.toolCalls.isNotEmpty()) {
                val calls = JSONArray()
                for (call in message.toolCalls) {
                    calls.put(
                        JSONObject()
                            .put("id", call.id)
                            .put("type", "function")
                            .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments)),
                    )
                }
                item.put("tool_calls", calls)
            }
            if (message.toolCallID != null) item.put("tool_call_id", message.toolCallID)
            wire.put(item)
        }
        val body = JSONObject().put("model", model).put("messages", wire)
        if (provider == AIProvider.OpenRouter) body.put("temperature", 0.2)
        if (provider == AIProvider.Minimax) body.put("reasoning_split", true)
        if (connectionTest) {
            body.put("max_tokens", 1024)
            if (provider == AIProvider.Minimax && model == "MiniMax-M3") body.put("thinking", JSONObject().put("type", "disabled"))
            if (provider == AIProvider.Kimi && model == "kimi-k3") body.put("reasoning_effort", "low")
        }
        if (tools.isNotEmpty()) {
            val encoded = JSONArray()
            for (spec in tools) {
                encoded.put(
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject().put("name", spec.name).put("description", spec.description).put("parameters", spec.parameters),
                    ),
                )
            }
            body.put("tools", encoded)
            body.put("tool_choice", "auto")
        }
        return body.toString().encodeToByteArray()
    }

    companion object {
        fun parseResponse(data: ByteArray): ChatMessage {
            val root = try { JSONObject(data.decodeToString()) } catch (_: Exception) {
                throw LLMError.Malformed("not JSON")
            }
            val choices = root.optJSONArray("choices") ?: throw LLMError.Malformed("no choices")
            if (choices.length() == 0) throw LLMError.Malformed("no choices")
            val message = choices.getJSONObject(0).optJSONObject("message") ?: throw LLMError.Malformed("no choices")
            val calls = ArrayList<ToolCall>()
            val rawCalls = message.optJSONArray("tool_calls")
            if (rawCalls != null) {
                for (i in 0 until rawCalls.length()) {
                    val raw = rawCalls.getJSONObject(i)
                    val fn = raw.optJSONObject("function") ?: continue
                    val id = raw.optString("id", "")
                    val name = fn.optString("name", "")
                    if (id.isEmpty() || name.isEmpty()) continue
                    calls.add(ToolCall(id, name, fn.optString("arguments", "{}")))
                }
            }
            val content = if (message.isNull("content")) null else message.optString("content", null)
            return ChatMessage(ChatRole.assistant, content, calls)
        }
    }
}

sealed class LLMError(message: String) : Exception(message) {
    class Malformed(why: String) : LLMError("Unexpected model response: $why")
}

data class HttpResponse(val status: Int, val body: ByteArray)

interface HttpClient {
    suspend fun request(url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): HttpResponse
}

class ScriptedHttp : HttpClient {
    var routes: List<Triple<String, Int, ByteArray>> = emptyList()
    override suspend fun request(url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        val route = routes.firstOrNull { url.contains(it.first) } ?: return HttpResponse(404, ByteArray(0))
        return HttpResponse(route.second, route.third)
    }
}

/** POST or GET through HttpURLConnection. Used by the app; tests use [ScriptedHttp]. */
class UrlHttp : HttpClient {
    override suspend fun request(url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse = withContext(Dispatchers.IO) {
        val connection = (URI(url).toURL().openConnection() as java.net.HttpURLConnection)
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.requestMethod = if (body == null) "GET" else "POST"
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            HttpResponse(status, bytes)
        } finally {
            connection.disconnect()
        }
    }
}

class CompletingClient(
    private val http: HttpClient,
    private val apiKey: String,
    private val model: String,
    private val provider: AIProvider,
    private val baseURL: String = provider.baseURL,
) : LlmClient {
    override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage {
        val wire = ChatCompletionsClient(apiKey, model, provider)
        val url = provider.validatedBaseURL(baseURL).trimEnd('/') + "/chat/completions"
        val response = http.request(
            url,
            mapOf("Authorization" to "Bearer $apiKey", "Content-Type" to "application/json"),
            wire.makeRequestBody(messages, tools),
        )
        if (response.status !in 200..299) throw ProviderError.Status(response.status)
        return ChatCompletionsClient.parseResponse(response.body)
    }
}

data class CatalogApp(
    val id: String,
    val alias: String,
    val name: String,
    val version: String,
    val versionID: String,
    val summary: String,
    val categoryID: String,
)

sealed class CatalogError(message: String) : Exception(message) {
    class Http(code: Int) : CatalogError("App catalog returned HTTP $code")
    class Malformed : CatalogError("Unexpected response from the app catalog")
    class NotFound(query: String) : CatalogError("No app found for '$query'")
    class TooLarge(n: Int) : CatalogError("Download is $n bytes, which is above the limit")
}

class FapHubClient(
    private val http: HttpClient,
    val base: String = "https://catalog.flipperzero.one/api/v0",
) {
    suspend fun search(query: String, api: String, target: String = "f7", limit: Int = 8): List<CatalogApp> {
        val url = "$base/application?query=${enc(query)}&limit=$limit&api=${enc(api)}&target=$target"
        return parseApps(get(url))
    }

    suspend fun categories(api: String, target: String = "f7"): Map<String, String> {
        val data = get("$base/category?api=${enc(api)}&target=$target")
        val raw = JSONArray(data.decodeToString())
        val out = LinkedHashMap<String, String>()
        for (i in 0 until raw.length()) {
            val item = raw.getJSONObject(i)
            val id = item.optString("_id", "")
            val name = item.optString("name", "")
            if (id.isNotEmpty() && name.isNotEmpty()) out[id] = name
        }
        return out
    }

    suspend fun downloadBuild(versionID: String, api: String, target: String = "f7"): ByteArray {
        val data = get("$base/application/version/$versionID/build/compatible?target=$target&api=${enc(api)}")
        if (data.size > maxDownloadBytes) throw CatalogError.TooLarge(data.size)
        if (data.size < 4 || data[0] != 0x7F.toByte() || data[1] != 0x45.toByte() || data[2] != 0x4C.toByte() || data[3] != 0x46.toByte()) {
            throw CatalogError.Malformed()
        }
        return data
    }

    private suspend fun get(url: String): ByteArray {
        val response = http.request(url)
        if (response.status !in 200..299) throw CatalogError.Http(response.status)
        return response.body
    }

    companion object {
        const val maxDownloadBytes = 4 * 1024 * 1024
        fun parseApps(data: ByteArray): List<CatalogApp> {
            val raw = try { JSONArray(data.decodeToString()) } catch (_: Exception) { throw CatalogError.Malformed() }
            val out = ArrayList<CatalogApp>()
            for (i in 0 until raw.length()) {
                val item = raw.optJSONObject(i) ?: continue
                val id = item.optString("_id", "")
                val version = item.optJSONObject("current_version") ?: continue
                val versionID = version.optString("_id", "")
                if (id.isEmpty() || versionID.isEmpty()) continue
                out.add(
                    CatalogApp(
                        id = id,
                        alias = item.optString("alias", ""),
                        name = version.optString("name", item.optString("alias", "app")),
                        version = version.optString("version", "?"),
                        versionID = versionID,
                        summary = version.optString("short_description", ""),
                        categoryID = item.optString("category_id", ""),
                    ),
                )
            }
            return out
        }
        private fun enc(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8)
    }
}

class GitHubClient(private val http: HttpClient) {
    data class Hit(val repository: String, val path: String, val rawURL: String)

    suspend fun searchCode(query: String, fileExtension: String?, limit: Int = 8): List<Hit> {
        var q = query
        if (!fileExtension.isNullOrEmpty()) q += " extension:$fileExtension"
        val url = "https://api.github.com/search/code?q=${java.net.URLEncoder.encode(q, Charsets.UTF_8)}&per_page=$limit"
        val response = http.request(url, mapOf("Accept" to "application/vnd.github+json"))
        if (response.status !in 200..299) throw CatalogError.Http(response.status)
        val root = try { JSONObject(response.body.decodeToString()) } catch (_: Exception) { throw CatalogError.Malformed() }
        val items = root.optJSONArray("items") ?: throw CatalogError.Malformed()
        val hits = ArrayList<Hit>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val path = item.optString("path", "")
            val repo = item.optJSONObject("repository")?.optString("full_name", "") ?: ""
            val html = item.optString("html_url", "")
            if (path.isEmpty() || repo.isEmpty() || html.isEmpty()) continue
            val raw = html.replace("https://github.com/", "https://raw.githubusercontent.com/").replace("/blob/", "/")
            hits.add(Hit(repo, path, raw))
        }
        return hits
    }

    suspend fun fetchRaw(urlString: String): ByteArray {
        val uri = try { URI(urlString) } catch (_: Exception) { throw CatalogError.Malformed() }
        val host = uri.host ?: throw CatalogError.Malformed()
        if (uri.scheme != "https" || (host != "raw.githubusercontent.com" && host != "github.com")) throw CatalogError.Malformed()
        val response = http.request(urlString)
        if (response.status !in 200..299) throw CatalogError.Http(response.status)
        if (response.body.size > maxDownloadBytes) throw CatalogError.TooLarge(response.body.size)
        return response.body
    }

    companion object {
        const val maxDownloadBytes = 1024 * 1024
    }
}

data class FirmwareDistribution(val name: String, val repo: String, val notes: String) {
    val releasesURL: String get() = "https://github.com/$repo/releases"
}

object FirmwareCatalog {
    val known = listOf(
        FirmwareDistribution("Momentum", "Next-Flip/Momentum-Firmware", "Feature-rich fork with asset packs and extra apps"),
        FirmwareDistribution("Unleashed", "DarkFlippers/unleashed-firmware", "Region-unlocked fork, large protocol set"),
        FirmwareDistribution("RogueMaster", "RogueMaster/flipperzero-firmware-wPlugins", "Unleashed-based, ships a very large app bundle"),
        FirmwareDistribution("Xtreme", "Flipper-XFW/Xtreme-Firmware", "Predecessor of Momentum, archived"),
        FirmwareDistribution("Official", "flipperdevices/flipperzero-firmware", "Stock firmware from Flipper Devices"),
    )

    fun identify(fork: String?, version: String?): FirmwareDistribution? {
        val haystack = listOfNotNull(fork, version).joinToString(" ").lowercase()
        if (haystack.isEmpty()) return null
        known.firstOrNull { haystack.contains(it.name.lowercase()) }?.let { return it }
        if (haystack.contains("mntm")) return known.first { it.name == "Momentum" }
        if (haystack.contains("unlshd") || haystack.contains("unleashed")) return known.first { it.name == "Unleashed" }
        if (haystack.contains("rm ") || haystack.contains("roguemaster")) return known.first { it.name == "RogueMaster" }
        return null
    }
}

data class FirmwareStatus(
    val distribution: FirmwareDistribution,
    val installedVersion: String,
    val latestRelease: String?,
    val updateAvailable: Boolean?,
) {
    val summary: String
        get() {
            val latest = latestRelease ?: return "${distribution.name} $installedVersion"
            return when (updateAvailable) {
                true -> "${distribution.name} $installedVersion; $latest is available"
                false -> "${distribution.name} $installedVersion is the newest release"
                null -> "${distribution.name} $installedVersion (dev build); newest release is $latest"
            }
        }
}

class FirmwareUpdateChecker(private val http: HttpClient) {
    suspend fun latestRelease(distribution: FirmwareDistribution): String? {
        val response = http.request(
            "https://api.github.com/repos/${distribution.repo}/releases/latest",
            mapOf("Accept" to "application/vnd.github+json"),
        )
        if (response.status !in 200..299) return null
        val root = try { JSONObject(response.body.decodeToString()) } catch (_: Exception) { return null }
        return root.optString("tag_name", "").ifEmpty { root.optString("name", "").ifEmpty { null } }
    }

    suspend fun status(fork: String?, version: String?): FirmwareStatus? {
        val distribution = FirmwareCatalog.identify(fork, version) ?: return null
        val installed = version ?: "unknown"
        val latest = try { latestRelease(distribution) } catch (_: Exception) { null }
        return FirmwareStatus(distribution, installed, latest, compare(installed, latest))
    }

    companion object {
        fun compare(installed: String, latest: String?): Boolean? {
            if (latest.isNullOrEmpty()) return null
            val normalizedInstalled = installed.trim().lowercase()
            if (normalizedInstalled.isEmpty() || normalizedInstalled == "unknown") return null
            for (marker in listOf("dev", "dirty", "local", "rc")) {
                if (normalizedInstalled.contains(marker)) return null
            }
            val normalizedLatest = latest.trim().lowercase()
            fun digits(value: String) = value.dropWhile { !it.isDigit() }
            if (normalizedInstalled == normalizedLatest) return false
            if (digits(normalizedInstalled) == digits(normalizedLatest) && digits(normalizedLatest).isNotEmpty()) return false
            return true
        }
    }
}

sealed class ForgeError(message: String) : Exception(message) {
    class Unavailable : ForgeError("Payload generation needs an API key in Settings")
    class Empty : ForgeError("The model returned no content")
    class WrongFormat(why: String) : ForgeError("The generated file does not look valid: $why")
}

class PayloadForge(private val llm: LlmClient) {
    suspend fun forge(kind: PayloadKind, description: String): String {
        val reply = llm.complete(
            listOf(
                ChatMessage(ChatRole.system, "You write Flipper Zero ${kind.wire} files. Output ONLY the file contents."),
                ChatMessage(ChatRole.user, description),
            ),
            emptyList(),
        )
        val content = stripFences(reply.content ?: "")
        if (content.isEmpty()) throw ForgeError.Empty()
        validate(content, kind)
        return content
    }

    companion object {
        fun stripFences(text: String): String {
            val lines = text.trim().split('\n').toMutableList()
            if (lines.firstOrNull()?.startsWith("```") == true) lines.removeAt(0)
            if (lines.lastOrNull()?.startsWith("```") == true) lines.removeAt(lines.lastIndex)
            return lines.joinToString("\n").trim()
        }

        private val badUsbKeywords = setOf(
            "REM", "DELAY", "DEFAULTDELAY", "DEFAULT_DELAY", "STRINGDELAY", "STRING_DELAY",
            "DEFAULTSTRINGDELAY", "DEFAULT_STRING_DELAY", "STRING", "STRINGLN", "REPEAT", "HOLD",
            "RELEASE", "WAIT_FOR_BUTTON_PRESS", "ALTCHAR", "ALTCODE", "ALTSTRING", "CTRL", "CONTROL",
            "ALT", "SHIFT", "GUI", "WINDOWS", "FN", "ENTER", "ESC", "ESCAPE", "TAB", "SPACE",
            "BACKSPACE", "BACK", "DELETE", "INSERT", "HOME", "END", "PAGEUP", "PAGEDOWN", "UP",
            "UPARROW", "DOWN", "DOWNARROW", "LEFT", "LEFTARROW", "RIGHT", "RIGHTARROW", "CAPSLOCK",
            "NUMLOCK", "SCROLLLOCK", "PRINTSCREEN", "PAUSE", "BREAK", "MENU", "APP", "POWER",
            "MUTE", "VOLUME_UP", "VOLUME_DOWN", "PLAY", "PLAY_PAUSE", "STOP", "NEXT_TRACK",
            "PREV_TRACK", "EJECT", "LEFTCLICK", "LEFT_CLICK", "RIGHTCLICK", "RIGHT_CLICK",
            "MIDDLECLICK", "MIDDLE_CLICK", "WHEELCLICK", "WHEEL_CLICK", "MOUSEMOVE", "MOUSE_MOVE",
            "MOUSESCROLL", "MOUSE_SCROLL", "SLEEP", "REFRESH", "LOGOFF", "REBOOT", "EXIT", "ID",
            "BT_ID", "BLE_ID", "MEDIA", "GLOBE", "SYSRQ", "SNAPSHOT", "FORWARD", "BRIGHT_UP",
            "BRIGHT_DOWN", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11",
            "F12", "F13", "F14", "F15", "F16", "F17", "F18", "F19", "F20", "F21", "F22", "F23", "F24",
        )

        fun validate(content: String, kind: PayloadKind) {
            val lines = content.split('\n')
            when (kind) {
                PayloadKind.Badusb -> {
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue
                        val head = trimmed.substringBefore(' ')
                        if (head.uppercase() !in badUsbKeywords) throw ForgeError.WrongFormat("unknown command '$head'")
                    }
                }
                else -> {
                    if (lines.firstOrNull()?.startsWith("Filetype:") != true) {
                        throw ForgeError.WrongFormat("must start with a Filetype: header")
                    }
                    if (!content.contains("Version:")) throw ForgeError.WrongFormat("missing Version: line")
                }
            }
        }
    }
}

data class DeviceProfile(
    var deviceName: String = "",
    var firmware: String = "",
    var firmwareCommit: String = "",
    var apiVersion: String = "",
    var hardwareUID: String = "",
    var freeSpaceBytes: ULong = 0u,
    var totalSpaceBytes: ULong = 0u,
    val apps: MutableList<String> = mutableListOf(),
    val folders: MutableList<Folder> = mutableListOf(),
    var updated: Instant = Instant.now(),
) {
    data class Folder(val path: String, val fileCount: Int, val samples: List<String>)
    val isEmpty: Boolean get() = firmware.isEmpty() && apps.isEmpty() && folders.isEmpty()

    fun promptSummary(nonce: String): String {
        if (isEmpty) return ""
        val lines = ArrayList<String>()
        lines.add("Device: ${Untrusted.sanitizeName(deviceName)}, firmware ${Untrusted.sanitizeName(firmware)} ($firmwareCommit), SDK $apiVersion")
        if (totalSpaceBytes > 0u) lines.add("SD card: $freeSpaceBytes free of $totalSpaceBytes")
        if (apps.isNotEmpty()) lines.add("Installed apps (${apps.size}): " + apps.joinToString(", ") { Untrusted.sanitizeName(it) })
        for (folder in folders) {
            if (folder.fileCount <= 0) continue
            val names = folder.samples.joinToString(", ") { Untrusted.sanitizeName(it) }
            val more = if (folder.fileCount > folder.samples.size) ", +${folder.fileCount - folder.samples.size} more" else ""
            lines.add("${folder.path} (${folder.fileCount}): $names$more")
        }
        lines.add("Snapshot taken $updated; it can be out of date, verify before acting on it.")
        return Untrusted.wrap(lines.joinToString("\n"), "device inventory", nonce)
    }

    companion object {
        val scanned = listOf("/ext/subghz", "/ext/nfc", "/ext/lfrfid", "/ext/infrared", "/ext/ibutton", "/ext/badusb")
        const val maxApps = 60
        const val maxSamplesPerFolder = 12
    }
}

class DeviceProfileStore(private val directory: File) {
    init { directory.mkdirs() }

    suspend fun cached(uid: String): DeviceProfile? = withContext(Dispatchers.IO) {
        val file = profileFile(uid) ?: return@withContext null
        val raw = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@withContext null
        runCatching {
            val profile = DeviceProfile(
                deviceName = raw.getString("deviceName"),
                firmware = raw.getString("firmware"),
                firmwareCommit = raw.getString("firmwareCommit"),
                apiVersion = raw.getString("apiVersion"),
                hardwareUID = raw.getString("hardwareUID"),
                freeSpaceBytes = raw.getString("freeSpaceBytes").toULong(),
                totalSpaceBytes = raw.getString("totalSpaceBytes").toULong(),
                updated = Instant.parse(raw.getString("updated")),
            )
            if (profile.hardwareUID != uid) return@runCatching null
            val apps = raw.getJSONArray("apps")
            for (index in 0 until minOf(apps.length(), DeviceProfile.maxApps)) profile.apps.add(apps.getString(index))
            val folders = raw.getJSONArray("folders")
            for (index in 0 until minOf(folders.length(), DeviceProfile.scanned.size)) {
                val folder = folders.getJSONObject(index)
                val names = folder.getJSONArray("samples")
                val samples = (0 until minOf(names.length(), DeviceProfile.maxSamplesPerFolder)).map { names.getString(it) }
                profile.folders.add(DeviceProfile.Folder(folder.getString("path"), folder.getInt("fileCount"), samples))
            }
            profile
        }.getOrNull()
    }

    private fun profileFile(uid: String): File? =
        uid.takeIf { it.matches(Regex("[0-9A-Fa-f]{8,64}")) }?.let { File(directory, "$it.json") }

    private suspend fun save(profile: DeviceProfile) = withContext(Dispatchers.IO) {
        val file = profileFile(profile.hardwareUID) ?: return@withContext
        val apps = JSONArray()
        profile.apps.forEach { apps.put(it) }
        val folders = JSONArray()
        profile.folders.forEach { folder ->
            val samples = JSONArray()
            folder.samples.forEach { samples.put(it) }
            folders.put(JSONObject().put("path", folder.path).put("fileCount", folder.fileCount).put("samples", samples))
        }
        val raw = JSONObject()
            .put("deviceName", profile.deviceName)
            .put("firmware", profile.firmware)
            .put("firmwareCommit", profile.firmwareCommit)
            .put("apiVersion", profile.apiVersion)
            .put("hardwareUID", profile.hardwareUID)
            .put("freeSpaceBytes", profile.freeSpaceBytes.toString())
            .put("totalSpaceBytes", profile.totalSpaceBytes.toString())
            .put("updated", profile.updated.toString())
            .put("apps", apps)
            .put("folders", folders)
        file.writeText(raw.toString())
    }

    suspend fun build(flipper: FlipperControlling): DeviceProfile {
        val profile = DeviceProfile()
        try {
            val info = flipper.deviceInfo()
            profile.deviceName = info["hardware_name"] ?: ""
            profile.hardwareUID = info["hardware_uid"] ?: ""
            profile.firmwareCommit = info["firmware_commit"] ?: ""
            profile.apiVersion = "${info["firmware_api_major"] ?: "0"}.${info["firmware_api_minor"] ?: "0"}"
            profile.firmware = listOf(info["firmware_origin_fork"], info["firmware_version"]).mapNotNull { it }.filter { it.isNotEmpty() }.joinToString(" ")
        } catch (_: Exception) {
        }
        try {
            val storage = flipper.storageInfo("/ext")
            profile.freeSpaceBytes = storage.freeSpace
            profile.totalSpaceBytes = storage.totalSpace
        } catch (_: Exception) {
        }
        try {
            val categories = flipper.list("/ext/apps")
            for (category in categories) {
                if (!category.isDirectory || profile.apps.size >= DeviceProfile.maxApps) continue
                val path = FlipperPath.join("/ext/apps", category.name)
                val entries = try { flipper.list(path) } catch (_: Exception) { continue }
                for (entry in entries) {
                    if (profile.apps.size >= DeviceProfile.maxApps) break
                    if (entry.name.lowercase().endsWith(".fap")) {
                        profile.apps.add("${category.name}/${entry.name.removeSuffix(".fap")}")
                    }
                }
            }
        } catch (_: Exception) {
        }
        for (path in DeviceProfile.scanned) {
            val entries = try { flipper.list(path) } catch (_: Exception) { continue }
            val files = entries.filter { !it.isDirectory }
            val folders = entries.filter { it.isDirectory }
            val samples = ArrayList<String>()
            samples.addAll(files.take(DeviceProfile.maxSamplesPerFolder).map { it.name })
            if (samples.size < DeviceProfile.maxSamplesPerFolder) {
                samples.addAll(folders.take(DeviceProfile.maxSamplesPerFolder - samples.size).map { "${it.name}/" })
            }
            profile.folders.add(DeviceProfile.Folder(path, entries.size, samples))
        }
        profile.updated = Instant.now()
        save(profile)
        return profile
    }
}

fun renderScreen(frame: FlipperScreenFrame): ByteArray? = try {
    ScreenPng.png(frame)
} catch (_: Exception) {
    null
}
