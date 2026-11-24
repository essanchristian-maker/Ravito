package com.ravito.app.notif

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.ravito.app.RavitoApp
import com.ravito.app.data.RavitoRepository
import com.ravito.app.domain.Operator
import kotlinx.coroutines.launch

/** Lit uniquement les notifications de l'application Wave (les autres sont ignorées). */
class WaveNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val op = Operator.entries.firstOrNull { sbn.packageName in it.notificationPackages } ?: return
        val app = applicationContext as? RavitoApp ?: return
        if (op !in app.settings.current.enabledOperators) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (text.isBlank()) return
        val body = if (title.isNotBlank()) "$title. $text" else text
        val whenMs = if (n.`when` > 0) n.`when` else sbn.postTime
        val stableId = "${sbn.key}|$whenMs"
        app.appScope.launch {
            try {
                app.repository.processNotification(op, body, whenMs, stableId)
            } catch (e: Exception) {
                Log.e(RavitoRepository.TAG, "Notification Wave illisible", e)
            }
        }
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, WaveNotificationListener::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
