package com.ravito.app.domain

/**
 * Opérateurs mobile money pris en charge.
 *
 * [defaultSenders] : identifiants d'expéditeur SMS connus. L'utilisateur peut en ajouter
 * dans Paramètres si son opérateur utilise un autre nom.
 */
enum class Operator(
    val label: String,
    val reportLabel: String,
    val shortLabel: String,
    val balanceLabel: String,
    val badge: String,
    val color: Long,
    val defaultSenders: List<String>,
    val notificationPackages: List<String> = emptyList(),
) {
    ORANGE(
        label = "Orange Money",
        reportLabel = "ORANGE MONEY",
        shortLabel = "OM",
        balanceLabel = "Orange",
        badge = "OM",
        color = 0xFFFF7900,
        defaultSenders = listOf("OrangeMoney", "Orange Money", "Orange-Money"),
    ),
    MTN(
        label = "MTN MoMo",
        reportLabel = "MTN",
        shortLabel = "MTN",
        balanceLabel = "MTN",
        badge = "MTN",
        color = 0xFFFFCB05,
        defaultSenders = listOf("MobileMoney", "MTN MoMo", "MTNMoMo", "MoMo", "MTN Money", "MTNMobileMoney", "MTN MobMoney"),
    ),
    MOOV(
        label = "Moov Money",
        reportLabel = "MOOV",
        shortLabel = "MOOV",
        balanceLabel = "Moov",
        badge = "MV",
        color = 0xFF0066B3,
        defaultSenders = listOf("MoovMoney", "Moov Money", "Flooz", "MoovAfrica", "Moov Africa", "MoovMoneyCI"),
    ),
    WAVE(
        label = "Wave",
        reportLabel = "WAVE",
        shortLabel = "WAVE",
        balanceLabel = "Wave",
        badge = "W",
        color = 0xFF1DC8F2,
        defaultSenders = listOf("Wave", "WaveCI", "Wave CI"),
        notificationPackages = listOf("com.wave.personal", "com.wave.business"),
    );

    companion object {
        fun fromName(name: String?): Operator? = entries.firstOrNull { it.name == name }

        /** Normalise un expéditeur SMS pour la comparaison : minuscules, sans espaces ni tirets. */
        fun normalizeSender(raw: String): String =
            TextNormalizer.fold(raw).lowercase().replace(Regex("[^a-z0-9]"), "")

        /**
         * Retrouve l'opérateur d'un expéditeur SMS.
         * @param sendersByOperator liste effective (défauts + ajouts de l'utilisateur) des opérateurs actifs.
         */
        fun matchSender(address: String?, sendersByOperator: Map<Operator, List<String>>): Operator? {
            if (address.isNullOrBlank()) return null
            val a = normalizeSender(address)
            if (a.isEmpty()) return null
            // 1. égalité stricte
            for ((op, senders) in sendersByOperator) {
                if (senders.any { normalizeSender(it) == a }) return op
            }
            // 2. l'expéditeur contient un identifiant assez long (ex. "OrangeMoneyCI")
            for ((op, senders) in sendersByOperator) {
                if (senders.any { s -> normalizeSender(s).let { it.length >= 6 && a.contains(it) } }) return op
            }
            return null
        }
    }
}

enum class TxStatus { SUCCESS, FAILED }

enum class TxKind(val label: String) {
    TRANSFER("Transfert"),
    DEPOSIT("Dépôt"),
    OTHER("Envoi"),

    /** Argent reçu (UV renvoyées par un agent Mobile Money contre des espèces). */
    RECEIVED("Reçu"),
    ;

    val isSend: Boolean get() = this != RECEIVED
}

/**
 * Rubrique du rapport pour un numéro (gardée dans le carnet) :
 * les cabines qu'on recharge et les agents Mobile Money (dépôts / retraits).
 */
enum class Category(val label: String, val reportLabel: String) {
    CABINE("Cabine", "CABINES"),
    AGENT("Agent Mobile Money", "AGENTS MOBILE MONEY"),
    ;

    companion object {
        fun fromName(name: String?): Category? = entries.firstOrNull { it.name == name }
    }
}

enum class TxSource(val label: String) {
    SMS("SMS"),
    NOTIFICATION("Notification"),
    MANUAL("Saisie manuelle"),
}
