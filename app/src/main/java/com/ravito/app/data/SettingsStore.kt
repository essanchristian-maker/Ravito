package com.ravito.app.data

import android.content.Context
import android.content.SharedPreferences
import com.ravito.app.domain.Operator
import com.ravito.app.domain.ReportGenerator
import com.ravito.app.domain.ReportPeriod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 0 = automatique, 1 = SIM 1, 2 = SIM 2, 3 = application (Wave). */
data class AppSettings(
    val onboardingDone: Boolean = false,
    val enabledOperators: Set<Operator> = setOf(Operator.ORANGE),
    val simByOperator: Map<Operator, Int> = emptyMap(),
    val extraSenders: Map<Operator, List<String>> = emptyMap(),
    val template: String = ReportGenerator.DEFAULT_TEMPLATE,
    val period: ReportPeriod = ReportPeriod(),
    val includeFees: Boolean = false,
    val showBalances: Boolean = true,
    val reminderEnabled: Boolean = true,
    val reminderMinutes: Int = 19 * 60 + 30,
    val biometricEnabled: Boolean = false,
    val lockTimeoutSeconds: Int = 60,
    val lastInboxSync: Long = 0L,
    val firstSyncDays: Int = 30,
    /** Date (ms) à laquelle l'utilisateur a coché le consentement ; 0 = pas encore. */
    val consentAt: Long = 0L,
    /** Autoriser captures et partage d'écran (désactivé par défaut : données financières). */
    val allowScreenCapture: Boolean = false,
) {
    /** Opérateurs actifs, dans l'ordre d'affichage du rapport. */
    val operatorsOrdered: List<Operator> get() = Operator.entries.filter { it in enabledOperators }

    fun sendersFor(op: Operator): List<String> = op.defaultSenders + (extraSenders[op] ?: emptyList())

    fun activeSenders(): Map<Operator, List<String>> = operatorsOrdered.associateWith { sendersFor(it) }

    fun reportOptions() = ReportGenerator.Options(
        template = template,
        operators = operatorsOrdered,
        includeFeesInTotal = includeFees,
        showBalances = showBalances,
        period = period,
    )
}

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ravito_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        write(next)
        _state.value = next
    }

    private fun read(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            onboardingDone = prefs.getBoolean("onboarding_done", d.onboardingDone),
            enabledOperators = prefs.getStringSet("operators", null)
                ?.mapNotNull { Operator.fromName(it) }?.toSet() ?: d.enabledOperators,
            simByOperator = Operator.entries.associateWith { prefs.getInt("sim_${it.name}", 0) },
            extraSenders = Operator.entries.associateWith { op ->
                prefs.getString("senders_${op.name}", "").orEmpty()
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            },
            // L'ancien modèle par défaut (par opérateur) passe au modèle en 3 rubriques.
            template = prefs.getString("template", null)
                ?.takeIf { it != ReportGenerator.LEGACY_TEMPLATE } ?: d.template,
            period = ReportPeriod(prefs.getInt("period_start", 0), prefs.getInt("period_end", 23 * 60 + 59)),
            includeFees = prefs.getBoolean("include_fees", d.includeFees),
            showBalances = prefs.getBoolean("show_balances", d.showBalances),
            reminderEnabled = prefs.getBoolean("reminder_enabled", d.reminderEnabled),
            reminderMinutes = prefs.getInt("reminder_minutes", d.reminderMinutes),
            biometricEnabled = prefs.getBoolean("biometric", d.biometricEnabled),
            lockTimeoutSeconds = prefs.getInt("lock_timeout", d.lockTimeoutSeconds),
            lastInboxSync = prefs.getLong("last_inbox_sync", 0L),
            firstSyncDays = prefs.getInt("first_sync_days", d.firstSyncDays),
            consentAt = prefs.getLong("consent_at", 0L),
            allowScreenCapture = prefs.getBoolean("allow_screen_capture", false),
        )
    }

    private fun write(s: AppSettings) {
        prefs.edit().apply {
            putBoolean("onboarding_done", s.onboardingDone)
            putStringSet("operators", s.enabledOperators.map { it.name }.toSet())
            Operator.entries.forEach { op ->
                putInt("sim_${op.name}", s.simByOperator[op] ?: 0)
                putString("senders_${op.name}", (s.extraSenders[op] ?: emptyList()).joinToString(","))
            }
            putString("template", s.template)
            putInt("period_start", s.period.startMinutes)
            putInt("period_end", s.period.endMinutes)
            putBoolean("include_fees", s.includeFees)
            putBoolean("show_balances", s.showBalances)
            putBoolean("reminder_enabled", s.reminderEnabled)
            putInt("reminder_minutes", s.reminderMinutes)
            putBoolean("biometric", s.biometricEnabled)
            putInt("lock_timeout", s.lockTimeoutSeconds)
            putLong("last_inbox_sync", s.lastInboxSync)
            putInt("first_sync_days", s.firstSyncDays)
            putLong("consent_at", s.consentAt)
            putBoolean("allow_screen_capture", s.allowScreenCapture)
        }.apply()
    }
}
