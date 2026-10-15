package hero.flipper.app

import android.content.Context
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.first

private val Context.heroStore by preferencesDataStore(name = "hero")

data class StoredPrefs(
    val autoApproveMedium: Boolean = false,
    val autoConnect: Boolean = true,
    val readAloud: Boolean = false,
    val model: String = "anthropic/claude-sonnet-4.5",
    val provider: String = "openrouter",
    val baseURL: String = "https://openrouter.ai/api/v1",
    val lastDeviceAddress: String = "",
)

class HeroPrefs(private val context: Context) {
    private val autoApprove = booleanPreferencesKey("auto_approve_medium")
    private val autoConnect = booleanPreferencesKey("auto_connect")
    private val readAloud = booleanPreferencesKey("read_aloud")
    private val model = stringPreferencesKey("model")
    private val provider = stringPreferencesKey("provider")
    private fun modelFor(id: String) = stringPreferencesKey("model.$id")
    private fun urlFor(id: String) = stringPreferencesKey("base_url.$id")
    private val lastDeviceAddress = stringPreferencesKey("last_device_address")

    suspend fun load(): StoredPrefs {
        val prefs = context.heroStore.data.first()
        return StoredPrefs(
            autoApproveMedium = prefs[autoApprove] ?: false,
            autoConnect = prefs[autoConnect] ?: true,
            readAloud = prefs[readAloud] ?: false,
            model = prefs[modelFor(prefs[provider] ?: "openrouter")]
                ?: prefs[model]
                ?: (hero.flipper.agent.AIProvider.from(prefs[provider] ?: "openrouter")?.defaultModel ?: ""),
            provider = prefs[provider] ?: "openrouter",
            baseURL = prefs[urlFor(prefs[provider] ?: "openrouter")]
                ?: (hero.flipper.agent.AIProvider.from(prefs[provider] ?: "openrouter")?.baseURL ?: ""),
            lastDeviceAddress = prefs[lastDeviceAddress] ?: "",
        )
    }

    suspend fun providerSettings(id: String): Pair<String, String> {
        val prefs = context.heroStore.data.first()
        val selected = hero.flipper.agent.AIProvider.from(id) ?: hero.flipper.agent.AIProvider.OpenRouter
        return (prefs[modelFor(id)] ?: selected.defaultModel) to (prefs[urlFor(id)] ?: selected.baseURL)
    }

    suspend fun save(value: StoredPrefs) {
        context.heroStore.edit { prefs ->
            prefs[autoApprove] = value.autoApproveMedium
            prefs[autoConnect] = value.autoConnect
            prefs[readAloud] = value.readAloud
            prefs[model] = value.model
            prefs[provider] = value.provider
            prefs[modelFor(value.provider)] = value.model
            prefs[urlFor(value.provider)] = value.baseURL
            prefs[lastDeviceAddress] = value.lastDeviceAddress
        }
    }
}

/** Keystore-backed provider keys. Tool results never receive these values. */
class ProviderSecrets(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "provider_keys",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun store(provider: String, key: String) {
        prefs.edit().putString(provider, key).apply()
    }

    fun remove(provider: String) {
        prefs.edit().remove(provider).apply()
    }

    fun stored(provider: String): Boolean = !prefs.getString(provider, null).isNullOrEmpty()

    fun read(provider: String): String? = prefs.getString(provider, null)?.takeIf { it.isNotEmpty() }
}

class ReadAloud(context: Context) {
    private val tts = TextToSpeech(context) { }
    var speaking: Boolean = false
        private set

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { speaking = true }
            override fun onDone(utteranceId: String?) { speaking = false }
            override fun onError(utteranceId: String?) { speaking = false }
        })
    }

    fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hero")
    }

    fun stop() {
        tts.stop()
        speaking = false
    }
}

class VoiceInput(context: Context) {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

    fun listen(onText: (String) -> Unit, onError: (String) -> Unit) {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { onError("Speech input failed ($error)") }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotEmpty()) onText(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        recognizer.startListening(intent)
    }

    fun stop() {
        recognizer.stopListening()
    }
}
