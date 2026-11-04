package hero.flipper.app

import hero.flipper.agent.HttpClient
import hero.flipper.agent.UrlHttp
import org.json.JSONObject

data class ReportedReply(val text: String, val provider: String, val model: String)

/** Explicit reports contain the selected reply, never the chat archive or provider key. */
internal class ContentReports(private val http: HttpClient = UrlHttp()) {
    enum class Category { offensive, harmful, incorrect, other }

    suspend fun submit(reply: ReportedReply, category: Category, comment: String): String {
        val response = http.request("https://2342.ai/v1/content-reports",
            mapOf("Content-Type" to "application/json"), payload(reply, category, comment).toString().encodeToByteArray())
        check(response.status in 200..299) { "HTTP ${response.status}" }
        return JSONObject(response.body.decodeToString()).getString("id")
    }

    companion object {
        fun payload(reply: ReportedReply, category: Category, comment: String): JSONObject {
            require(reply.text.isNotBlank() && reply.text.encodeToByteArray().size <= 24_000)
            require(comment.encodeToByteArray().size <= 2_000)
            return JSONObject().put("app", "flipperhero_android").put("category", category.name)
                .put("provider", reply.provider).put("model", reply.model.take(200))
                .put("app_version", BuildConfig.VERSION_NAME).put("content", reply.text).put("comment", comment)
        }
    }
}
