package hero.flipper.app

import android.Manifest
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) (application as HeroApp).model.attachPhoto(bitmap)
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.BLUETOOTH_SCAN] == true &&
            result[Manifest.permission.BLUETOOTH_CONNECT] == true) {
            val model = (application as HeroApp).model
            if (model.autoConnect && !model.connected) model.scan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = (application as HeroApp).model
        model.voiceContext = this
        model.requestPermissions = {
            permissions.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                ),
            )
        }
        model.openCamera = { camera.launch(null) }
        setContent { HeroAppRoot(model) }
        handleShortcut(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcut(intent)
    }

    private fun handleShortcut(intent: Intent?) {
        val kind = intent?.getStringExtra(HeroShortcuts.extra)?.let { raw ->
            HeroShortcut.entries.firstOrNull { it.name == raw }
        } ?: return
        (application as HeroApp).model.openShortcut(kind)
    }
}
