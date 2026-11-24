package com.ravito.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.ravito.app.RavitoApp
import com.ravito.app.data.RavitoRepository
import com.ravito.app.domain.Operator
import kotlinx.coroutines.launch

/**
 * Lecture en direct : chaque SMS entrant est examiné ; seuls ceux des expéditeurs mobile money
 * actifs sont traités, les autres sont ignorés sans être stockés.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            Log.w(RavitoRepository.TAG, "SMS illisible", e)
            return
        } ?: return
        if (messages.isEmpty()) return

        val extras = intent.extras
        val subId = when {
            extras == null -> -1
            extras.containsKey(EXTRA_SUB_INDEX) -> extras.getInt(EXTRA_SUB_INDEX, -1)
            extras.containsKey("subscription") -> extras.getInt("subscription", -1)
            else -> -1
        }.takeIf { it >= 0 }

        val app = context.applicationContext as RavitoApp
        val senders = app.settings.current.activeSenders()
        // Un SMS long arrive en plusieurs morceaux : on les recolle par expéditeur.
        val relevant = messages.filterNotNull()
            .groupBy { it.originatingAddress ?: it.displayOriginatingAddress ?: "" }
            .filterKeys { Operator.matchSender(it, senders) != null }
        if (relevant.isEmpty()) return

        val now = System.currentTimeMillis()
        val pending = goAsync()
        app.appScope.launch {
            try {
                for ((sender, parts) in relevant) {
                    val body = parts.joinToString("") { it.messageBody ?: it.displayMessageBody ?: "" }
                    app.repository.processSms(sender, body, now, subId)
                }
            } catch (e: Exception) {
                Log.e(RavitoRepository.TAG, "Traitement SMS impossible", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val EXTRA_SUB_INDEX = "android.telephony.extra.SUBSCRIPTION_INDEX"
    }
}
