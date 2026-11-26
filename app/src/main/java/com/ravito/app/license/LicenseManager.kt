package com.ravito.app.license

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.ravito.app.BuildConfig
import com.ravito.app.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** État de l'abonnement, tel que l'écran doit le montrer. */
sealed interface LicenseState {
    /** Aucun code saisi sur ce téléphone. */
    data object NeedsActivation : LicenseState

    /** Le code est lié à un autre téléphone (copie, ou licence transférée). */
    data object Unauthorized : LicenseState

    data class Active(val license: License, val daysLeft: Int, val inGrace: Boolean) : LicenseState
    data class Expired(val license: License) : LicenseState
    data class Blocked(val license: License) : LicenseState

    /** Trop longtemps sans Internet : impossible de vérifier l'abonnement. */
    data class NeedsConnection(val license: License) : LicenseState
}

/** Dernière version publiée, renvoyée par le serveur. */
data class Release(val versionCode: Int, val versionName: String, val url: String, val size: Long)

/**
 * Licence du téléphone : activation par code d'invitation, vérification hors ligne, mise à jour au contact du serveur.
 * L'heure de référence ne recule jamais (on garde la plus récente vue : serveur, SMS opérateur),
 * pour qu'avancer ou reculer la date du téléphone ne prolonge pas l'essai.
 */
class LicenseManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("ravito_license", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(evaluate())
    val state: StateFlow<LicenseState> = _state.asStateFlow()

    private val _latest = MutableStateFlow(readLatest())
    val latest: StateFlow<Release?> = _latest.asStateFlow()

    val code: String? get() = prefs.getString(KEY_CODE, null)

    val license: License? get() = prefs.getString(KEY_TOKEN, null)?.let { LicenseToken.verify(it) }

    /** Identifiant stable de ce téléphone pour Ravito (survit à une réinstallation). */
    val deviceId: String by lazy {
        @SuppressLint("HardwareIds")
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val digest = MessageDigest.getInstance("SHA-256").digest("ravito:$androidId:${context.packageName}".toByteArray())
        digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /** Heure de référence : jamais en arrière. */
    fun trustedNow(): Long {
        val now = System.currentTimeMillis()
        val seen = prefs.getLong(KEY_TRUSTED, 0L)
        if (now > seen) prefs.edit().putLong(KEY_TRUSTED, now).apply()
        return maxOf(now, seen)
    }

    /** Une heure sûre (SMS d'opérateur, serveur) fait avancer l'heure de référence. */
    fun observeTrustedTime(ms: Long) {
        if (ms > prefs.getLong(KEY_TRUSTED, 0L) && ms <= System.currentTimeMillis() + 86_400_000L) {
            prefs.edit().putLong(KEY_TRUSTED, ms).apply()
        }
    }

    fun refresh() {
        _state.value = evaluate()
    }

    private fun evaluate(): LicenseState {
        if (prefs.getBoolean(KEY_REVOKED, false)) return LicenseState.Unauthorized
        val token = prefs.getString(KEY_TOKEN, null) ?: return LicenseState.NeedsActivation
        val lic = LicenseToken.verify(token) ?: return LicenseState.NeedsActivation
        if (lic.deviceId != deviceId) return LicenseState.Unauthorized
        if (lic.status == "blocked") return LicenseState.Blocked(lic)
        val now = trustedNow()
        if (lic.expiresAt > 0 && now > lic.expiresAt + lic.graceMs) return LicenseState.Expired(lic)
        if (now > lic.checkBefore) return LicenseState.NeedsConnection(lic)
        val daysLeft = if (lic.expiresAt > 0) ((lic.expiresAt - now + DAY - 1) / DAY).toInt() else 99
        return LicenseState.Active(lic, daysLeft.coerceAtLeast(0), lic.expiresAt in 1 until now)
    }

    sealed interface Result {
        data object Ok : Result
        data class Error(val message: String) : Result
    }

    /** Active ce téléphone avec un code d'invitation (Internet nécessaire). */
    suspend fun activate(rawCode: String): Result = withContext(Dispatchers.IO) {
        val code = rawCode.trim().uppercase().replace(" ", "")
        if (code.isEmpty()) return@withContext Result.Error("Entrez votre code.")
        val body = JSONObject()
            .put("code", code)
            .put("deviceId", deviceId)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE)
            .put("appVersion", BuildConfig.VERSION_CODE)
        when (val r = post("/api/activate", body)) {
            is Http.Failure -> Result.Error(r.message)
            is Http.Response -> when (r.status) {
                200 -> {
                    val token = r.json.optString("token")
                    if (!accept(token, code)) Result.Error("Réponse du serveur invalide. Réessayez.")
                    else Result.Ok
                }
                404 -> Result.Error("Code inconnu. Vérifiez le code reçu par WhatsApp.")
                409 -> Result.Error(
                    "Ce code est déjà utilisé sur un autre téléphone. Ravito est une licence personnelle : " +
                        "chaque code ne fonctionne que sur un seul téléphone.",
                )
                403 -> Result.Error("Accès suspendu. Contactez le vendeur de Ravito.")
                else -> Result.Error("Erreur du serveur (${r.status}). Réessayez plus tard.")
            }
        }
    }

    /**
     * Contact avec le serveur : envoie les compteurs de fonctionnement, reçoit la licence à jour
     * et la dernière version. Sans Internet, ne fait rien (la licence reste valable jusqu'à sa date limite).
     */
    suspend fun checkin(operators: List<String>): Boolean = withContext(Dispatchers.IO) {
        val code = code ?: return@withContext false
        prefs.edit().putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis()).apply()
        val report = Telemetry.pending(context)
        val body = JSONObject()
            .put("code", code)
            .put("deviceId", deviceId)
            .put("appVersion", BuildConfig.VERSION_CODE)
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE)
            .put("operators", JSONArray(operators))
            .put("stats", report.stats)
            .put("errors", report.errors)
            .put("unrecognized", report.unrecognized)
        when (val r = post("/api/checkin", body)) {
            is Http.Failure -> false
            is Http.Response -> when (r.status) {
                200 -> {
                    Telemetry.sent(context, report)
                    accept(r.json.optString("token"), code)
                    r.json.optJSONObject("latest")?.let { saveLatest(it) }
                    true
                }
                409 -> { // code transféré vers un autre téléphone
                    prefs.edit().putBoolean(KEY_REVOKED, true).apply()
                    refresh(); true
                }
                404 -> { // code supprimé
                    prefs.edit().remove(KEY_TOKEN).remove(KEY_CODE).apply()
                    refresh(); true
                }
                else -> false
            }
        }
    }

    /** « J'ai payé » : prévient le vendeur (notification) pour qu'il valide le paiement. */
    suspend fun claimPayment(months: Int): Result = withContext(Dispatchers.IO) {
        val code = code ?: return@withContext Result.Error("Aucun code sur ce téléphone.")
        val body = JSONObject().put("code", code).put("deviceId", deviceId).put("months", months)
        when (val r = post("/api/claim", body)) {
            is Http.Failure -> Result.Error(r.message)
            is Http.Response -> if (r.status == 200) Result.Ok else Result.Error("Envoi impossible (${r.status}). Réessayez.")
        }
    }

    /**
     * Code PIN oublié : vérifie le code personnel Ravito OU le code à 6 chiffres donné par le vendeur.
     * Seul le téléphone lié au code peut le faire.
     */
    suspend fun verifyPinReset(value: String): Result = withContext(Dispatchers.IO) {
        val code = code ?: return@withContext Result.Error("Aucun code sur ce téléphone.")
        val body = JSONObject().put("code", code).put("deviceId", deviceId).put("value", value.trim())
        when (val r = post("/api/pinreset", body)) {
            is Http.Failure -> Result.Error(r.message)
            is Http.Response -> if (r.status == 200) Result.Ok else Result.Error("Code incorrect. Utilisez votre code Ravito (ex. KOFFI-4821) ou le code envoyé par le vendeur.")
        }
    }

    /** Faut-il contacter le serveur maintenant ? (au plus souvent toutes les [minMinutes] minutes) */
    fun checkinDue(minMinutes: Int): Boolean =
        code != null && System.currentTimeMillis() - prefs.getLong(KEY_LAST_ATTEMPT, 0L) >= minMinutes * 60_000L

    private fun accept(token: String, code: String): Boolean {
        val lic = LicenseToken.verify(token) ?: return false
        if (lic.deviceId != deviceId) return false
        observeTrustedTime(lic.issuedAt)
        prefs.edit().putString(KEY_TOKEN, token).putString(KEY_CODE, code).putBoolean(KEY_REVOKED, false).apply()
        refresh()
        return true
    }

    private fun saveLatest(j: JSONObject) {
        val url = j.optString("url")
        if (url.isBlank()) return
        prefs.edit().putString(KEY_LATEST, j.toString()).apply()
        _latest.value = readLatest()
    }

    private fun readLatest(): Release? = prefs.getString(KEY_LATEST, null)?.let {
        try {
            val j = JSONObject(it)
            Release(j.getInt("versionCode"), j.optString("versionName"), j.getString("url"), j.optLong("size"))
        } catch (e: Exception) {
            null
        }
    }

    private sealed interface Http {
        data class Response(val status: Int, val json: JSONObject) : Http
        data class Failure(val message: String) : Http
    }

    private fun post(path: String, body: JSONObject): Http = try {
        val conn = (URL(BuildConfig.SERVER_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val status = conn.responseCode
        val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        Http.Response(status, try { JSONObject(text) } catch (e: Exception) { JSONObject() })
    } catch (e: Exception) {
        Http.Failure("Pas de connexion Internet. Activez les données mobiles ou le Wi-Fi, puis réessayez.")
    }

    companion object {
        const val DAY = 86_400_000L
        private const val KEY_TOKEN = "token"
        private const val KEY_CODE = "code"
        private const val KEY_REVOKED = "revoked"
        private const val KEY_TRUSTED = "trusted_now"
        private const val KEY_LAST_ATTEMPT = "last_checkin_attempt"
        private const val KEY_LATEST = "latest_release"
    }
}
