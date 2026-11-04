package hero.flipper.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real Compose controls on Android. No provider requests or Bluetooth hardware needed. */
class SetupAndApprovalTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun settings() {
        compose.runOnIdle {
            val model = (compose.activity.application as HeroApp).model
            model.provider = "openrouter"
            model.keyDraft = ""
            model.keyStored = false
            model.autoApproveMedium = false
            model.yolo = false
            model.transcript.clear()
            model.changeUsageAnalyticsEnabled(false)
            model.nav.select(hero.flipper.app.nav.HeroSection.Device)
            model.touch()
        }
        compose.onNodeWithText("Settings").performClick()
    }

    @Test fun modelDiscoveryAcceptsAnUnsavedKeyAndProviderSwitchClearsIt() {
        compose.onNodeWithText("Choose model").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("API key").performScrollTo().performTextInput("unsaved-test-key")
        compose.onNodeWithText("Choose model").assertIsEnabled()
        compose.onNodeWithText("Provider: OpenRouter").performScrollTo().performClick()
        compose.onNodeWithText("2342.ai").performClick()
        compose.waitUntil { (compose.activity.application as HeroApp).model.provider == "ai2342" }
        compose.onNodeWithText("Choose model").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            check((compose.activity.application as HeroApp).model.keyDraft.isEmpty())
        }
    }

    @Test fun permissionLooseningRequiresApprovalAndATapCannotApproveIt() {
        compose.onNodeWithText("Skip prompts for medium-risk actions").performScrollTo().performClick()
        compose.onNodeWithText("Allow permission change?").assertExists()
        compose.onNodeWithContentDescription("Hold to allow").performTouchInput { click() }
        compose.onNodeWithText("Allow permission change?").assertExists()
        compose.onNodeWithText("Deny").performClick()
        compose.onNodeWithText("Allow permission change?").assertDoesNotExist()
        compose.onNodeWithText("Skip prompts for medium-risk actions").assertIsOff()
    }

    @Test fun chatCannotSendWhileDisconnected() {
        compose.onNodeWithText("Agent").performClick()
        compose.onNodeWithText("Ask about your Flipper...").performTextInput("read the battery")
        compose.onNodeWithText("Send").assertIsNotEnabled()
    }

    @Test fun reportingPreviewsOnlyTheSelectedReplyAndCanBeCancelled() {
        compose.runOnIdle {
            (compose.activity.application as HeroApp).model.transcript.add(
                Transcript("assistant", "Selected reply for review", provider = "ai2342", model = "test-model"))
        }
        compose.onNodeWithText("Agent").performClick()
        compose.onNodeWithText("Report reply").performClick()
        compose.onNodeWithText("Selected reply excerpt (editable)").assertExists()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            val file = java.io.File(compose.activity.getExternalFilesDir(null), "report-preview.png")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNode(hasText("Selected reply for review") and hasSetTextAction()).assertExists()
        compose.onNodeWithText("Send report").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Selected reply excerpt (editable)").assertDoesNotExist()
    }

    @Test fun analyticsAreOffByDefaultAndCanBeToggled() {
        compose.onNodeWithText("Share usage statistics").performScrollTo().assertIsOff()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            val file = java.io.File(compose.activity.getExternalFilesDir(null), "analytics-settings.png")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithText("Share usage statistics").performClick().assertIsOn()
        compose.onNodeWithText("Share usage statistics").performClick().assertIsOff()
    }
}
