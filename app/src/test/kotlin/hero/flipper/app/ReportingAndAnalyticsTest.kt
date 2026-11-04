package hero.flipper.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import org.json.JSONObject

class ReportingAndAnalyticsTest {
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
