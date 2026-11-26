package com.ravito.app.license

import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Licence délivrée par le serveur Ravito et signée (ECDSA P-256 / SHA-256).
 * Le téléphone la vérifie SANS Internet grâce à la clé publique intégrée : impossible de la fabriquer.
 */
data class License(
    val code: String,
    val deviceId: String,
    val name: String,
    /** "trial", "paid" ou "blocked". */
    val status: String,
    /** Fin de l'essai ou de l'abonnement (ms). */
    val expiresAt: Long,
    /** Tolérance après la fin (ms). */
    val graceMs: Long,
    /** Date limite pour se reconnecter à Internet (ms). */
    val checkBefore: Long,
    val issuedAt: Long,
    val flags: Map<String, Boolean>,
    val priceFcfa: Long,
    val omNumber: String,
    val notice: String,
) {
    val isTrial: Boolean get() = status == "trial"

    /** Une fonction est active sauf si le portail l'a explicitement désactivée. */
    fun feature(key: String): Boolean = flags[key] != false
}

object LicenseToken {

    /** Clé publique du serveur (SPKI, base64). La clé privée n'existe que sur le serveur. */
    const val PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE8wFunTgBBE8R/5NSn5H2JO2wRsuI7tA8takbOekHuSG11XqG1IKYip3zKXx8AGwPIIubwETS4A/ya1C4pvrf2w=="

    /** Vérifie la signature et lit la licence ; null si le jeton est faux ou modifié. */
    fun verify(token: String, publicKeyB64: String = PUBLIC_KEY): License? = try {
        val parts = token.trim().split('.')
        require(parts.size == 2)
        val body = Base64Url.decode(parts[0])
        val sig = Base64Url.decode(parts[1])
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64Url.decode(publicKeyB64)))
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(body)
            verify(sig)
        }
        if (!ok) null else parse(JSONObject(String(body, Charsets.UTF_8)))
    } catch (e: Exception) {
        null
    }

    private fun parse(j: JSONObject): License {
        val flags = mutableMapOf<String, Boolean>()
        j.optJSONObject("flags")?.let { f -> f.keys().forEach { k -> flags[k] = f.optBoolean(k, true) } }
        return License(
            code = j.getString("code"),
            deviceId = j.getString("dev"),
            name = j.optString("name"),
            status = j.optString("st", "trial"),
            expiresAt = j.optLong("exp"),
            graceMs = j.optLong("grace"),
            checkBefore = j.optLong("chk"),
            issuedAt = j.optLong("iat"),
            flags = flags,
            priceFcfa = j.optLong("price", 5000),
            omNumber = j.optString("om"),
            notice = j.optString("notice"),
        )
    }
}

/** Base64 « URL » sans dépendre de java.util.Base64 (absent avant Android 8). Accepte aussi le base64 standard. */
object Base64Url {
    private val table = IntArray(128) { -1 }.also { t ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".forEachIndexed { i, c -> t[c.code] = i }
        t['-'.code] = 62; t['_'.code] = 63; t['+'.code] = 62; t['/'.code] = 63
    }

    fun decode(s: String): ByteArray {
        val clean = s.trim().trimEnd('=')
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = if (c.code < 128) table[c.code] else -1
            require(v >= 0) { "caractère base64 invalide" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}

/**
 * Masque un SMS non reconnu avant de l'envoyer au portail : chiffres -> N, noms propres -> NOM.
 * Seule la FORME du message remonte (pour améliorer la lecture), jamais les montants, numéros ou noms.
 */
object SmsMasker {
    private val keep = setOf("FCFA", "CFA", "XOF", "ID", "MTN", "OM", "SMS", "USSD", "CI", "UV", "OK", "MOMO", "WAVE", "MOOV", "ORANGE", "N")
    private val common = setOf(
        "transaction", "ref", "reference", "référence", "solde", "frais", "nouveau", "votre", "vous", "montant",
        "money", "mobile", "transfert", "depot", "dépôt", "retrait", "de", "du", "le", "la", "les", "au", "à", "a",
    )
    private val digits = Regex("\\d+")
    private val properNames = Regex("\\b\\p{Lu}[\\p{L}'’-]*(?:\\s+\\p{Lu}[\\p{L}'’-]*)+")

    fun mask(text: String): String {
        // Noms d'abord (sur le texte d'origine), puis les chiffres : « 2.000F » devient « N.NF ».
        val noNames = properNames.replace(text) { m ->
            val words = m.value.split(Regex("\\s+"))
            val out = mutableListOf<String>()
            for (w in words) {
                if (w.uppercase() in keep || w.lowercase() in common) out += w
                else if (out.lastOrNull() != "NOM") out += "NOM"
            }
            // Un seul mot capitalisé isolé (début de phrase, « Votre »…) n'est pas un nom.
            if (out == listOf("NOM") && words.size < 2) m.value else out.joinToString(" ")
        }
        return digits.replace(noNames, "N").take(500)
    }
}
