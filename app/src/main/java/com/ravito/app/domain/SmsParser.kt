package com.ravito.app.domain

/**
 * Lecture des SMS / notifications mobile money par règles.
 *
 * Principe (cahier des charges §6) :
 *  1. l'expéditeur a déjà été reconnu (voir [Operator.matchSender]) ;
 *  2. le texte est classé grâce à des mots-clés : envoi, échec, argent reçu, code, promo ;
 *  3. les champs utiles (montant, bénéficiaire, frais, solde, référence) sont extraits.
 *
 * Les règles sont regroupées ici pour être faciles à ajuster quand un opérateur change son texte.
 * Chaque cas réel rencontré doit être ajouté dans SmsParserTest.
 */
object SmsParser {

    data class Parsed(
        val operator: Operator,
        val status: TxStatus,
        val kind: TxKind,
        val amount: Long,
        val fee: Long?,
        val balanceAfter: Long?,
        /** Numéro de l'autre personne : bénéficiaire d'un envoi, expéditeur d'une réception. */
        val recipientNumber: String?,
        val recipientName: String?,
        val reference: String?,
    )

    enum class IgnoreReason { RECEIVED, CODE, PROMO, NO_AMOUNT, INFO }

    sealed interface Result {
        /** Envoi reconnu (réussi ou échoué) ou argent reçu ([TxKind.RECEIVED]). */
        data class Transaction(val tx: Parsed) : Result

        /** Message hors sujet : jamais enregistré. */
        data class Ignored(val reason: IgnoreReason) : Result

        /** Message de l'opérateur au format inconnu : rangé dans « À vérifier ». [hint] = extraction partielle. */
        data class Unrecognized(val hint: Parsed) : Result
    }

    private val opts = setOf(RegexOption.IGNORE_CASE)
    private fun rx(vararg patterns: String) = patterns.map { Regex(it, opts) }

    // ---------- Classement ----------

    /**
     * Argent reçu d'une personne (UV renvoyées par un agent) : enregistré comme réception.
     * Testé AVANT les envois (« Vous avez reçu un transfert de … »).
     */
    private val receivedTransfer = rx(
        "\\bvous avez recu\\b",
        "\\bavez bien recu\\b",
        "\\brecu (un|une|de|du)\\b",
        "\\breception d",
        "\\b(a|avez) ete credite",
        "\\bcredite de\\b",
        "\\bcredit(e)? sur votre (compte|solde)",
        "\\b(depot|transfert) recu\\b",
        "\\bvous a (envoye|transfere|fait un)",
        "\\bvous a depose",
        "\\byou have received\\b",
        "\\bhas sent you\\b",
        "\\breceived\\b.*\\bfrom\\b",
    )

    /** Entrées d'argent qui ne viennent pas d'une personne : ignorées. */
    private val receivedOther = rx(
        "\\bpaiement recu\\b",
        "\\bremboursement\\b",
        "\\bbonus\\b",
        "\\bcashback\\b",
        "\\bremise\\b",
    )

    /** Envoi d'argent fait par le marchand. */
    private val sendTransfer = rx(
        "\\btransfert (d'argent )?(de|d') ?.{0,40}?\\b(vers|a|au|pour)\\b",
        "\\bvous avez (envoye|transfere|effectue un transfert|fait un transfert)\\b",
        "\\bvotre transfert (de|d')\\b",
        "\\benvoi (d'argent )?(de|d') ?.{0,40}?\\b(vers|a|au)\\b",
        "\\b(a|ont) ete (transfere|envoye)s? (avec succes )?(vers|a|au)\\b",
        "\\btransfert (effectue|reussi)",
        "\\byou have (sent|transferred)\\b",
        "\\btransfer of\\b.{0,40}\\bto\\b",
    )

    /** Dépôt fait par un marchand/agent vers un numéro (sortie de crédit). */
    private val sendDeposit = rx(
        "\\bvous avez (effectue|fait) un depot\\b",
        "\\bdepot (de|d') ?.{0,40}?\\b(vers|au|a)\\b",
        "\\bdepot (effectue|reussi)",
        "\\bcash.?in\\b",
    )

    private val failed = rx(
        "\\bechec\\b",
        "\\bechoue",
        "\\bannule",
        "\\bimpossible\\b",
        "\\binsuffisant",
        "\\bn'a pas (pu )?(etre )?abouti",
        "\\bnon abouti",
        "\\brefuse",
        "\\brejete",
        "\\bexpire",
        "\\bfailed\\b",
        "\\bn'a pas pu etre",
        "\\bpas ete effectue",
    )

    private val sendContext = rx("\\btransf", "\\benvoi", "\\benvoy", "\\bdepot\\b", "\\bsent\\b")

    private val codes = rx(
        "\\bcode\\b", "\\botp\\b", "\\bmot de passe\\b", "\\bpassword\\b", "\\bpin\\b", "\\bverification\\b",
    )

    private val promos = rx(
        "\\bpromo", "\\bbonus\\b", "\\bgagne", "\\boffre\\b", "\\bprofite", "\\bcadeau", "\\btirage\\b",
        "\\bconcours\\b", "\\breduction\\b", "\\bremise\\b", "\\bsouscri", "\\bforfait\\b", "\\bpass\\b",
        "\\bachat de (credit|forfait|pass)", "\\brecharge", "\\bpaiement\\b", "\\bpaye\\b", "\\bfacture\\b",
        "\\bretrait\\b", "\\bcashback\\b",
    )

    private val balanceOnly = rx(
        "^\\s*(votre |le )?solde (actuel |disponible |principal )?(est|:|de)",
        "^\\s*your balance\\b",
    )

    // ---------- Extraction ----------

    /** Montant suivi d'une devise : "50000 FCFA", "50 000F", "10.000 F", "1,500.00 XOF". */
    private val money = Regex(
        "(?<![\\d.,])(\\d{1,3}(?:[ .,]\\d{3})+|\\d+)(?:[.,](\\d{1,2}))?\\s?(fcfa|f\\s?cfa|xof|cfa|francs?|f)(?![a-z])",
        opts,
    )
    private val feeLabel = Regex("frais|fees?|commission|taxe|tva|charges?", opts)
    private val balanceLabel = Regex("solde|balance", opts)

    /** Numéro ivoirien à 10 chiffres, avec ou sans +225 et séparateurs. */
    private val ciPhone = Regex("(?<![\\d])(?:(?:\\+|00)?225[\\s.-]?)?(0\\d(?:[\\s.-]?\\d\\d){4})(?![\\d])")
    private val genericPhone = Regex("(?<![\\d\\w.])(\\+?\\d{8,13})(?![\\d])")

    private val references = rx(
        "\\b(?:ref(?:erence)?(?:\\s*(?:de\\s*)?(?:la\\s*)?(?:transaction|trans))?|(?:id|n°|no|num|numero)\\s*(?:de\\s*)?(?:la\\s*)?transaction|(?:financial\\s*)?transaction\\s*id|trans\\s*id|txn\\s*id|tid|id)\\b\\s*[:.#=-]?\\s*([A-Za-z0-9][A-Za-z0-9._/-]{3,40})",
    )

    private val nameBeforeNumber = Regex(
        "\\b(?:a|au|vers|pour|chez)\\s+(?:le\\s+|la\\s+|M\\.?\\s+|Mme\\.?\\s+)?([A-Za-z][A-Za-z .'&-]{1,40}?)\\s*\\(\\s*(?:\\+?225)?\\s*\\d",
        opts,
    )
    private val nameAfterNumber = Regex("\\d\\s*\\)?\\s*\\(\\s*([A-Za-z][A-Za-z .'&-]{1,40}?)\\s*\\)")
    private val nameCapitalized = Regex("\\b(?:a|au|vers|pour)\\s+([A-Z][A-Za-z'&-]+(?:\\s[A-Z][A-Za-z'&-]+){0,3})")
    private val nameStopWords = setOf(
        "le", "la", "les", "votre", "vous", "un", "une", "frais", "nouveau", "solde", "ref", "fcfa", "cfa",
        "partir", "compter", "date", "heure", "orange", "mtn", "moov", "wave", "momo", "money", "id",
        "numero", "destinataire", "beneficiaire", "ce", "cette", "l",
    )

    // ---------- API ----------

    fun parse(operator: Operator, body: String): Result {
        val text = TextNormalizer.fold(body)
        val hint = extract(operator, body, text, TxStatus.SUCCESS)

        if (receivedTransfer.any { it.containsMatchIn(text) }) {
            if (receivedOther.any { it.containsMatchIn(text) }) return Result.Ignored(IgnoreReason.RECEIVED)
            if (failed.any { it.containsMatchIn(text) } || hint.amount <= 0) return Result.Ignored(IgnoreReason.RECEIVED)
            return Result.Transaction(hint.copy(kind = TxKind.RECEIVED, status = TxStatus.SUCCESS))
        }

        val isTransfer = sendTransfer.any { it.containsMatchIn(text) }
        val isDeposit = !isTransfer && sendDeposit.any { it.containsMatchIn(text) }
        val isFailed = failed.any { it.containsMatchIn(text) }

        if (isTransfer || isDeposit) {
            val kind = if (isTransfer) TxKind.TRANSFER else TxKind.DEPOSIT
            val status = if (isFailed) TxStatus.FAILED else TxStatus.SUCCESS
            if (hint.amount <= 0 && status == TxStatus.SUCCESS) return Result.Unrecognized(hint)
            return Result.Transaction(hint.copy(kind = kind, status = status))
        }

        if (isFailed && sendContext.any { it.containsMatchIn(text) }) {
            return Result.Transaction(hint.copy(kind = TxKind.TRANSFER, status = TxStatus.FAILED))
        }

        if (codes.any { it.containsMatchIn(text) }) return Result.Ignored(IgnoreReason.CODE)
        if (balanceOnly.any { it.containsMatchIn(text) }) return Result.Ignored(IgnoreReason.INFO)
        if (promos.any { it.containsMatchIn(text) }) return Result.Ignored(IgnoreReason.PROMO)
        if (hint.amount <= 0) return Result.Ignored(IgnoreReason.NO_AMOUNT)

        return Result.Unrecognized(hint)
    }

    /** Extraction « au mieux », aussi utilisée pour pré-remplir la saisie manuelle depuis « À vérifier ». */
    fun extract(operator: Operator, original: String, text: String = TextNormalizer.fold(original), status: TxStatus = TxStatus.SUCCESS): Parsed {
        val reference = findReference(text)
        val refRange = reference?.let { r -> text.indexOf(r).takeIf { it >= 0 }?.let { it until it + r.length } }

        var amount: Long? = null
        var fee: Long? = null
        var balance: Long? = null
        val moneySpans = mutableListOf<IntRange>()
        var prevEnd = 0
        for (m in money.findAll(text)) {
            if (refRange != null && m.range.first in refRange) continue
            moneySpans += m.range
            val value = parseMoney(m.groupValues[1])
            val windowStart = maxOf(prevEnd, m.range.first - 30)
            val window = text.substring(windowStart, m.range.first)
            prevEnd = m.range.last + 1
            val feeAt = feeLabel.findAll(window).lastOrNull()?.range?.first ?: -1
            val balAt = balanceLabel.findAll(window).lastOrNull()?.range?.first ?: -1
            when {
                feeAt < 0 && balAt < 0 -> if (amount == null) amount = value
                feeAt > balAt -> if (fee == null) fee = value
                else -> balance = value
            }
        }

        val number = findPhone(text, moneySpans, refRange)
        val name = findName(original, text)

        return Parsed(
            operator = operator,
            status = status,
            kind = TxKind.TRANSFER,
            amount = amount ?: 0L,
            fee = fee,
            balanceAfter = balance,
            recipientNumber = number,
            recipientName = name,
            reference = reference,
        )
    }

    /** Identifiant unique d'une transaction pour éviter les doublons. */
    fun dedupKey(operator: Operator, reference: String?, body: String, salt: String = ""): String {
        if (!reference.isNullOrBlank()) return "${operator.name}:ref:${reference.uppercase()}"
        val normalized = TextNormalizer.fold(body).lowercase()
        return "${operator.name}:h:" + sha256(normalized + "|" + salt)
    }

    // ---------- Aides ----------

    internal fun parseMoney(raw: String): Long {
        val digits = raw.filter { it.isDigit() }
        return digits.toLongOrNull() ?: 0L
    }

    private fun findReference(text: String): String? {
        for (r in references) {
            for (m in r.findAll(text)) {
                val value = m.groupValues[1].trimEnd('.', ',', ';', ':', '-', '/')
                if (value.length >= 4 && value.any { it.isDigit() }) {
                    // Un montant ou un numéro de téléphone n'est pas une référence.
                    val after = text.substring(m.range.last + 1).trimStart()
                    if (after.startsWith("F", ignoreCase = true) && value.all { it.isDigit() }) continue
                    return value
                }
            }
        }
        return null
    }

    private fun findPhone(text: String, moneySpans: List<IntRange>, refRange: IntRange?): String? {
        fun free(range: IntRange) =
            moneySpans.none { it.first <= range.last && range.first <= it.last } &&
                (refRange == null || !(refRange.first <= range.last && range.first <= refRange.last))

        ciPhone.findAll(text).firstOrNull { free(it.range) }?.let { return Formats.normalizePhone(it.groupValues[1]) }
        genericPhone.findAll(text).firstOrNull { free(it.range) }?.let { return Formats.normalizePhone(it.groupValues[1]) }
        return null
    }

    private fun findName(original: String, text: String): String? {
        // Les index du texte normalisé ne correspondent à l'original que si les longueurs sont égales.
        val origFolded = original.trim()
        val sameLength = origFolded.length == text.length
        fun pick(m: MatchResult): String? {
            val g = m.groups[1] ?: return null
            val raw = if (sameLength) origFolded.substring(g.range) else g.value
            val cleaned = raw.trim().trim('.', ',', '-', ' ')
            if (cleaned.length < 2) return null
            val words = cleaned.lowercase().split(' ', '-', '\'').filter { it.isNotBlank() }
            if (words.isEmpty() || words.all { it in nameStopWords }) return null
            if (words.first() in nameStopWords) return null
            return cleaned
        }
        nameBeforeNumber.find(text)?.let { m -> pick(m)?.let { return it } }
        nameAfterNumber.find(text)?.let { m -> pick(m)?.let { return it } }
        nameCapitalized.findAll(text).forEach { m -> pick(m)?.let { return it } }
        return null
    }

    private fun sha256(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
    }
}
