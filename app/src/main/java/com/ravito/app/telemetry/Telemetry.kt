package com.ravito.app.telemetry

import android.content.Context
import com.ravito.app.license.SmsMasker
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compteurs de FONCTIONNEMENT envoyés au portail (jamais le contenu) :
 * ouvertures, envois/UV reconnus (en nombre), SMS non reconnus (forme masquée), rapports copiés/partagés, erreurs.
 */
object Telemetry {
    private const val PREFS = "ravito_telemetry"
    private const val KEY_STATS = "stats"
    private const val KEY_ERRORS = "errors"
    private const val KEY_UNREC = "unrecognized"
    private const val KEEP_DAYS = 14
    private const val MAX_ITEMS = 20

    const val OPENS = "opens"
    const val SENDS = "sends"
    const val RECEIPTS = "receipts"
    const val UNRECOGNIZED = "unrecognized"
    const val SHARES = "shares"
    const val COPIES = "copies"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    @Synchronized
    fun count(ctx: Context, key: String, n: Int = 1) {
        val p = prefs(ctx)
        val stats = JSONObject(p.getString(KEY_STATS, "{}") ?: "{}")
        val day = stats.optJSONObject(today()) ?: JSONObject()
        day.put(key, day.optInt(key) + n)
        stats.put(today(), day)
        // On ne garde que les derniers jours.
        val keys = stats.keys().asSequence().toList().sorted()
        keys.dropLast(KEEP_DAYS).forEach { stats.remove(it) }
        p.edit().putString(KEY_STATS, stats.toString()).apply()
    }

    @Synchronized
    fun error(ctx: Context, e: Throwable) {
        val msg = (e.toString() + "\n" + e.stackTrace.take(12).joinToString("\n") { "  at $it" }).take(1500)
        push(ctx, KEY_ERRORS, JSONObject().put("ts", System.currentTimeMillis()).put("msg", msg))
    }

    /** SMS d'opérateur au format inconnu : seule sa forme masquée est gardée. */
    @Synchronized
    fun unrecognized(ctx: Context, operator: String, body: String) {
        count(ctx, UNRECOGNIZED)
        push(ctx, KEY_UNREC, JSONObject().put("ts", System.currentTimeMillis()).put("op", operator).put("text", SmsMasker.mask(body)))
    }

    private fun push(ctx: Context, key: String, item: JSONObject) {
        val p = prefs(ctx)
        val arr = JSONArray(p.getString(key, "[]") ?: "[]")
        arr.put(item)
        val trimmed = JSONArray()
        val start = maxOf(0, arr.length() - MAX_ITEMS)
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        p.edit().putString(key, trimmed.toString()).commit()
    }

    data class Report(val stats: JSONObject, val errors: JSONArray, val unrecognized: JSONArray)

    /** Ce qui sera envoyé : compteurs des 3 derniers jours (complets) + erreurs et SMS non reconnus en attente. */
    @Synchronized
    fun pending(ctx: Context): Report {
        val p = prefs(ctx)
        val all = JSONObject(p.getString(KEY_STATS, "{}") ?: "{}")
        val recent = JSONObject()
        all.keys().asSequence().toList().sorted().takeLast(3).forEach { recent.put(it, all.get(it)) }
        return Report(recent, JSONArray(p.getString(KEY_ERRORS, "[]") ?: "[]"), JSONArray(p.getString(KEY_UNREC, "[]") ?: "[]"))
    }

    /** Après un envoi réussi : on vide les erreurs et SMS déjà transmis. */
    @Synchronized
    fun sent(ctx: Context, report: Report) {
        val p = prefs(ctx)
        fun drop(key: String, sent: JSONArray) {
            val arr = JSONArray(p.getString(key, "[]") ?: "[]")
            val rest = JSONArray()
            for (i in sent.length() until arr.length()) rest.put(arr.get(i))
            p.edit().putString(key, rest.toString()).apply()
        }
        drop(KEY_ERRORS, report.errors)
        drop(KEY_UNREC, report.unrecognized)
    }

    /** Enregistre les plantages pour les remonter au prochain contact. */
    fun installCrashHandler(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { error(ctx, e) } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }
}
