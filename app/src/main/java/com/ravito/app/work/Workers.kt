package com.ravito.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ravito.app.RavitoApp
import com.ravito.app.domain.Formats
import com.ravito.app.domain.ReportGenerator
import com.ravito.app.notif.Notifications
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Rattrapage automatique toutes les heures. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as RavitoApp
        val r = app.repository.syncInbox()
        return if (r.error == null) Result.success() else Result.retry()
    }
}

/** Rappel du soir : synchronise, puis prévient « Votre rapport est prêt ». */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as RavitoApp
        val s = app.settings.current
        try {
            app.repository.syncInbox()
            val zone = ZoneId.systemDefault()
            val range = s.period.range(LocalDate.now(zone), zone)
            val txs = app.repository.observeTransactions(range.first, range.last + 1).first().map { it.toReportTx() }
            val summary = ReportGenerator.summarize(txs, s.reportOptions())
            val pending = app.repository.observePendingReviewCount(range.first, range.last + 1).first()
            val text = buildString {
                append(Formats.plural(summary.count, "envoi"))
                append(" aujourd’hui - ")
                append(Formats.amount(summary.total))
                append('.')
                if (pending > 0) append(" ${Formats.plural(pending, "SMS", "SMS")} à vérifier.")
                append(" Touchez pour copier le rapport.")
            }
            if (s.reminderEnabled) Notifications.showReminder(applicationContext, text)
        } finally {
            Scheduler.scheduleReminder(applicationContext)
        }
        return Result.success()
    }
}

/** Replanifie le rappel et la synchro après un redémarrage ou une mise à jour. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as RavitoApp
        if (app.settings.current.onboardingDone) Scheduler.scheduleAll(context)
    }
}

object Scheduler {
    private const val SYNC = "ravito_sync_hourly"
    private const val REMINDER = "ravito_reminder"

    fun scheduleAll(context: Context) {
        scheduleSync(context)
        scheduleReminder(context)
    }

    fun scheduleSync(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(SYNC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun scheduleReminder(context: Context) {
        val app = context.applicationContext as RavitoApp
        val s = app.settings.current
        val wm = WorkManager.getInstance(context)
        if (!s.reminderEnabled) {
            wm.cancelUniqueWork(REMINDER)
            return
        }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(s.reminderMinutes / 60, s.reminderMinutes % 60)
        if (!next.isAfter(now.plusSeconds(30))) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()
        val req = OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        wm.enqueueUniqueWork(REMINDER, ExistingWorkPolicy.REPLACE, req)
    }
}
