package hero.flipper.app

import android.content.Context
import android.os.Build
import hero.flipper.agent.HttpClient
import hero.flipper.agent.UrlHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** Only fixed screen/event identifiers can reach Rybbit. No free-form properties. */
internal class UsageAnalytics(context: Context, private val http: HttpClient = UrlHttp()) {
    enum class Event { app_open, agent_completed, agent_failed, report_submitted }
    private val preferences = context.getSharedPreferences("usage_analytics", Context.MODE_PRIVATE)
    @Volatile var enabled = preferences.getBoolean("enabled", false)
        private set
    @Volatile private var session = UUID.randomUUID().toString()
    private val queue = Channel<JSONObject>(16)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (payload in queue) if (enabled && payload.optString("anonymous_id") == session && !BuildConfig.DEBUG) {
                runCatching {
                    http.request("https://stats.2342.dev/api/track", mapOf(
                        "Content-Type" to "application/json",
                        "User-Agent" to "FlipperHero/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})",
                    ), payload.toString().encodeToByteArray())
                }
            }
        }
        event(Event.app_open)
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        session = UUID.randomUUID().toString()
        preferences.edit().putBoolean("enabled", value).apply()
        if (value) event(Event.app_open)
    }

    fun screen(section: hero.flipper.app.nav.HeroSection) {
        if (enabled) queue.trySend(payload(session, "/${section.name.lowercase()}", null))
    }

    fun event(event: Event) {
        if (enabled) queue.trySend(payload(session, "/app", event))
    }

    companion object {
        fun payload(session: String, path: String, event: Event?): JSONObject = JSONObject()
            .put("site_id", "55")
            .put("type", if (event == null) "pageview" else "custom_event")
            .put("hostname", "android.flipper-hero.net")
            .put("pathname", path)
            .put("anonymous_id", session)
            .put("properties", JSONObject().put("version", BuildConfig.VERSION_NAME)
                .put("edition", if (BuildConfig.FULL_EDITION) "full" else "play").toString())
            .apply { if (event != null) put("event_name", event.name) }
    }
}
