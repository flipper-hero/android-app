package hero.flipper.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The merged manifest is what the APK ships. Network and speech binding depend on it. */
class ManifestContractTest {
    @Test
    fun mergedManifestAllowsNetworkAndSpeech() {
        val flavor = BuildConfig.FLAVOR
        val cap = flavor.replaceFirstChar { it.uppercase() }
        val manifest = File(
            "build/intermediates/merged_manifests/${flavor}Debug/process${cap}DebugManifest/AndroidManifest.xml",
        )
        assertTrue(manifest.isFile, manifest.path)
        val text = manifest.readText()
        assertTrue(text.contains("android.permission.INTERNET"), text)
        assertTrue(text.contains("android.speech.RecognitionService"), text)
        assertTrue(text.contains("android.intent.action.TTS_SERVICE"), text)
    }
}
