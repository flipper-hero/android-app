package hero.flipper.app

import android.os.Bundle
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : ComponentActivity() {
    private var afterPermission: (() -> Unit)? = null
    private var requestedPermissions = emptyArray<String>()
    private val cameraFile get() = File(cacheDir, "camera/chat.jpg")
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        try {
            if (saved) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(cameraFile.path, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 2048) inSampleSize *= 2
                }
                BitmapFactory.decodeFile(cameraFile.path, options)?.let { bitmap ->
                    val matrix = Matrix().apply {
                        when (ExifInterface(cameraFile.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                        }
                    }
                    val photo = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    (application as HeroApp).model.attachPhoto(photo)
                }
            }
        } finally { cameraFile.delete() }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val continuation = afterPermission
        afterPermission = null
        if (requestedPermissions.all { result[it] == true }) continuation?.invoke()
        else (application as HeroApp).model.statusMessage = getString(R.string.permission_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val model = (application as HeroApp).model
        model.voiceContext = this
        model.requestPermissions = { requested, continuation ->
            if (afterPermission == null) {
                requestedPermissions = requested
                afterPermission = continuation
                permissions.launch(requested)
            }
        }
        model.openCamera = {
            cameraFile.parentFile?.mkdirs()
            camera.launch(FileProvider.getUriForFile(this, "$packageName.files", cameraFile))
        }
        setContent { HeroAppRoot(model) }
        handleShortcut(intent)
    }

    override fun onDestroy() {
        val model = (application as HeroApp).model
        if (model.voiceContext === this) {
            model.voiceContext = null
            model.requestPermissions = null
            model.openCamera = null
        }
        super.onDestroy()
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
