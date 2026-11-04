package hero.flipper.app

import android.app.Application

class HeroApp : Application() {
    lateinit var model: HeroModel
        private set

    override fun onCreate() {
        super.onCreate()
        hero.flipper.agent.AgentLocalization.resolve = { key, arguments -> Catalog.text(this, key, *arguments.toTypedArray()) }
        model = HeroModel(this)
        runCatching { HeroShortcuts.publish(this) }
    }
}
