package net.montierth.avogadro

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.montierth.avogadro.data.Api
import net.montierth.avogadro.data.Updater

class AvogadroApplication : Application() {
    /** Updates the app from the instance; it outlives the activity so it can install once the app is left. */
    lateinit var updater: Updater
        private set

    override fun onCreate() {
        super.onCreate()
        updater = Updater(this, Api.defaultClient(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        updater.start()
    }
}
