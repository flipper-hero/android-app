package hero.flipper.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import org.json.JSONObject
import hero.flipper.agent.HttpClient
import hero.flipper.agent.HttpResponse
import kotlinx.coroutines.runBlocking

class ReportingAndAnalyticsTest {
    @Test fun submitsToFlipperHeroWithoutProviderCredentialsAndRejectsFailedDelivery() = runBlocking<Unit> {
        var response = HttpResponse(201, "{\"id\":\"receipt\"}".encodeToByteArray())
        val http = object : HttpClient {
            override suspend fun request(url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
                assertEquals("https://flipper-hero.net/api/content-reports", url)
                assertEquals(mapOf("Content-Type" to "application/json"), headers)
                assertEquals("explicit excerpt", JSONObject(body!!.decodeToString()).getString("content"))
                return response
            }
        }
        val service = ContentReports(http)
        val reply = ReportedReply("explicit excerpt", "ai2342", "review-model")
        assertEquals("receipt", service.submit(reply, ContentReports.Category.other, ""))
        response = HttpResponse(503, "{}".encodeToByteArray())
        assertFailsWith<IllegalStateException> { service.submit(reply, ContentReports.Category.other, "") }
    }

    @Test fun reportContainsOnlyExplicitContentAndNoAuthenticationOrArchive() {
        val payload = ContentReports.payload(ReportedReply("selected reply", "ai2342", "review-model"),
            ContentReports.Category.offensive, "review this")
        assertEquals(setOf("app", "category", "provider", "model", "app_version", "content", "comment"), payload.keySet())
        assertEquals("selected reply", payload.getString("content"))
        assertEquals("review this", payload.getString("comment"))
        assertFalse(payload.has("messages"))
        assertFalse(payload.has("api_key"))
        assertFailsWith<IllegalArgumentException> {
            ContentReports.payload(ReportedReply(" ", "ai2342", "model"), ContentReports.Category.other, "")
        }
        assertFailsWith<IllegalArgumentException> {
            ContentReports.payload(ReportedReply("reply", "ai2342", "model"), ContentReports.Category.other, "ü".repeat(1001))
        }
    }

    @Test fun analyticsPayloadHasNoFreeFormUserProperties() {
        val payload = UsageAnalytics.payload("temporary-session", "/agent", UsageAnalytics.Event.agent_completed)
        assertEquals(setOf("site_id", "type", "hostname", "pathname", "anonymous_id", "properties", "event_name"), payload.keySet())
        assertEquals(setOf("version", "edition"), JSONObject(payload.getString("properties")).keySet())
        assertEquals("55", payload.getString("site_id"))
        assertEquals("agent_completed", payload.getString("event_name"))
        val page = UsageAnalytics.payload("temporary-session", "/settings", null)
        assertEquals("pageview", page.getString("type"))
        assertFalse(page.has("event_name"))
    }
}
