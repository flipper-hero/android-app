package hero.flipper.app

import android.app.Application

class HeroApp : Application() {
    lateinit var model: HeroModel
        private set

    override fun onCreate() {
        super.onCreate()
        model = HeroModel(this)
        runCatching { HeroShortcuts.publish(this) }
    }
}
