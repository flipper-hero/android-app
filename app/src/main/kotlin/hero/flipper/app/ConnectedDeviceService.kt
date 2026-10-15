package hero.flipper.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Ongoing notification while a Flipper is connected. Actions: Stop, Cancel, Disarm. */
class ConnectedDeviceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val model = (application as HeroApp).model
        if (!model.connected) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        startForeground(NOTIFICATION_ID, notification())
        when (intent?.action) {
            ACTION_CANCEL -> model.cancelFirmware()
            ACTION_DISARM -> model.disarm()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "Connected Flipper", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val model = (application as HeroApp).model
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("FlipperHero")
            .setContentText(when {
                model.firmwareJobRunning -> model.firmwareStatus
                model.engagement.active -> "Engaged: ${model.engagement.profile.note.ifEmpty { model.engagement.profile.summary }}"
                model.activeOperation != null -> model.activeOperation
                else -> model.connection
            })
            .setOngoing(true)
            .addAction(stopAction())
            .apply {
                if (model.firmwareJobRunning) addAction(action("Cancel", ACTION_CANCEL, 2))
                if (model.engagement.active) addAction(action("Disarm", ACTION_DISARM, 3))
            }
            .build()
    }

    private fun stopAction(): NotificationCompat.Action {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(HeroShortcuts.extra, HeroShortcut.Stop.name)
        val pending = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(0, "Stop app", pending).build()
    }

    private fun action(label: String, action: String, request: Int): NotificationCompat.Action {
        val intent = Intent(this, ConnectedDeviceService::class.java).setAction(action)
        val pending = PendingIntent.getService(this, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(0, label, pending).build()
    }

    companion object {
        const val CHANNEL = "connected-device"
        const val NOTIFICATION_ID = 7
        const val ACTION_CANCEL = "hero.flipper.app.CANCEL"
        const val ACTION_DISARM = "hero.flipper.app.DISARM"
        const val ACTION_REFRESH = "hero.flipper.app.REFRESH"
    }
}
