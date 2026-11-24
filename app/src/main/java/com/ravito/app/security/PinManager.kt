package com.ravito.app.security

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Code PIN à 4 chiffres, stocké sous forme d'empreinte PBKDF2 salée (jamais en clair).
 * Après 5 erreurs, attente croissante avant un nouvel essai.
 */
class PinManager(context: Context) {
    private val prefs = context.getSharedPreferences("ravito_pin", Context.MODE_PRIVATE)

    val hasPin: Boolean get() = prefs.contains("hash")

    fun setPin(pin: String) {
        require(pin.length == 4 && pin.all { it.isDigit() })
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString("salt", b64(salt))
            .putString("hash", b64(hash(pin, salt)))
            .putInt("failures", 0)
            .putLong("locked_until", 0)
            .apply()
    }

    /** Secondes d'attente restantes avant de pouvoir réessayer (0 = possible). */
    fun waitSeconds(now: Long = System.currentTimeMillis()): Long =
        ((prefs.getLong("locked_until", 0) - now) / 1000).coerceAtLeast(0)

    fun check(pin: String): Boolean {
        if (waitSeconds() > 0) return false
        val salt = prefs.getString("salt", null)?.let { unb64(it) } ?: return false
        val expected = prefs.getString("hash", null)?.let { unb64(it) } ?: return false
        val ok = MessageDigest.isEqual(expected, hash(pin, salt))
        if (ok) {
            prefs.edit().putInt("failures", 0).putLong("locked_until", 0).apply()
        } else {
            val failures = prefs.getInt("failures", 0) + 1
            val edit = prefs.edit().putInt("failures", failures)
            if (failures >= 5) {
                val seconds = 30L shl (failures - 5).coerceAtMost(6) // 30 s, 1 min, 2 min … 32 min
                edit.putLong("locked_until", System.currentTimeMillis() + seconds * 1000)
            }
            edit.apply()
        }
        return ok
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 20_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
