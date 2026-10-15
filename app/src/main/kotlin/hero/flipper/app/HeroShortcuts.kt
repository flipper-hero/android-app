package hero.flipper.app

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/** Launcher shortcuts use the same in-app selector and approval gate as chat tools. */
object HeroShortcuts {
    const val extra = "hero.flipper.app.SHORTCUT"

    fun publish(context: Context) {
        val actions = listOf(
            Triple(HeroShortcut.Ask, "Ask FlipperHero", "Ask the Agent"),
            Triple(HeroShortcut.Battery, "Battery", "Battery"),
            Triple(HeroShortcut.Emulate, "Emulate Card or Key", "Emulate Card or Key"),
            Triple(HeroShortcut.Send, "Send Signal", "Send Signal"),
            Triple(HeroShortcut.Stop, "Stop Flipper App", "Stop Flipper App"),
        )
        val shortcuts = actions.mapIndexed { index, (kind, label, description) ->
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(extra, kind.name)
            ShortcutInfoCompat.Builder(context, "hero_${kind.name.lowercase()}")
                .setShortLabel(Catalog.text(context, label))
                .setLongLabel(Catalog.text(context, description))
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(intent)
                .setRank(index)
                .build()
        }
        val maximum = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        if (maximum > 0) ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts.take(maximum))
    }
}
