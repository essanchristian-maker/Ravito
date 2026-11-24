package com.ravito.app

import android.app.Application
import com.ravito.app.data.RavitoDatabase
import com.ravito.app.data.RavitoRepository
import com.ravito.app.data.SettingsStore
import com.ravito.app.notif.Notifications
import com.ravito.app.security.PinManager
import com.ravito.app.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RavitoApp : Application() {

    /** Portée des traitements de fond (récepteur SMS, notifications). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings by lazy { SettingsStore(this) }
    val pin by lazy { PinManager(this) }
    val database by lazy { RavitoDatabase.create(this) }
    val repository by lazy { RavitoRepository(this, database, settings) }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        if (settings.current.onboardingDone) Scheduler.scheduleAll(this)
    }
}
