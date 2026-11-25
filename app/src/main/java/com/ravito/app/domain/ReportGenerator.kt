package com.ravito.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ligne d'historique telle que vue par le rapport (nom et rubrique déjà résolus via le carnet). */
data class ReportTx(
    val operator: Operator,
    val timestamp: Long,
    val amount: Long,
    val fee: Long?,
    val balanceAfter: Long?,
    val recipientNumber: String?,
    val recipientName: String?,
    val status: TxStatus,
    val excluded: Boolean,
    val kind: TxKind = TxKind.TRANSFER,
    val category: Category? = null,
)

/** Période du rapport, en minutes depuis minuit. Si fin <= début, la période se termine le lendemain. */
data class ReportPeriod(val startMinutes: Int = 0, val endMinutes: Int = 23 * 60 + 59) {

    fun range(date: LocalDate, zone: ZoneId): LongRange {
        val start = LocalDateTime.of(date, LocalTime.of(startMinutes / 60, startMinutes % 60))
        var endDate = date
        if (endMinutes <= startMinutes) endDate = date.plusDays(1)
        // la minute de fin est incluse : 23:59 -> jusqu'à minuit
        val end = LocalDateTime.of(endDate, LocalTime.of(endMinutes / 60, endMinutes % 60)).plusMinutes(1)
        return start.atZone(zone).toInstant().toEpochMilli() until end.atZone(zone).toInstant().toEpochMilli()
    }

    fun label(): String = "%02d:%02d - %02d:%02d".format(startMinutes / 60, startMinutes % 60, endMinutes / 60, endMinutes % 60)

    val isFullDay: Boolean get() = startMinutes == 0 && endMinutes == 23 * 60 + 59
}

object ReportGenerator {

    /** Rapport en 3 rubriques : cabines, agents Mobile Money, UV reçues des agents. */
    const val DEFAULT_TEMPLATE = """RAPPORT DU {date}

1. {cabines}

2. {agents}

3. {uv_recues}
{a_classer}
TOTAL ENVOYÉ : {nb_envois} - {total}
{soldes}"""

    /** Ancien modèle par défaut (par opérateur) : remplacé automatiquement s'il n'a pas été modifié. */
    const val LEGACY_TEMPLATE = """RAPPORT DU {date}

{operateurs}

TOTAL : {nb_envois} - {total}
{soldes}"""

    /** Modèle par opérateur, toujours disponible dans l'éditeur. */
    const val OPERATORS_TEMPLATE = LEGACY_TEMPLATE

    /** Aide affichée dans l'éditeur de modèle. */
    val PLACEHOLDERS: List<Pair<String, String>> = listOf(
        "{date}" to "Date du rapport (24/11/2026)",
        "{jour}" to "Date en toutes lettres (lundi 24 novembre 2026)",
        "{periode}" to "Période couverte (00:00 - 23:59)",
        "{cabines}" to "Rubrique des envois aux cabines, avec la liste",
        "{agents}" to "Rubrique des envois aux agents Mobile Money, avec la liste",
        "{uv_recues}" to "Rubrique des UV reçues des agents, avec la liste",
        "{a_classer}" to "Envois à des numéros pas encore classés (vide s'il n'y en a pas)",
        "{total_cabines} {nb_cabines} {total_agents} {nb_agents} {total_uv} {nb_uv}" to "Totaux et nombres de chaque rubrique",
        "{operateurs}" to "Un bloc par opérateur actif, avec la liste numérotée",
        "{liste}" to "Tous les envois, tous opérateurs confondus",
        "{total}" to "Montant total envoyé (120 000 F)",
        "{nb}" to "Nombre d'envois (3)",
        "{nb_envois}" to "Nombre d'envois avec le mot (3 envois)",
        "{frais}" to "Total des frais",
        "{soldes}" to "Solde de fin de journée de chaque opérateur",
        "{nb_echecs}" to "Nombre d'envois échoués",
        "{orange_liste} {orange_total} {orange_nb} {orange_solde}" to "Idem pour un opérateur (orange, mtn, moov, wave)",
    )

    private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val longDateFmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    data class Options(
        val template: String = DEFAULT_TEMPLATE,
        val operators: List<Operator> = Operator.entries,
        val includeFeesInTotal: Boolean = false,
        val showBalances: Boolean = true,
        val period: ReportPeriod = ReportPeriod(),
    )

    data class Summary(
        val count: Int,
        val total: Long,
        val fees: Long,
        val failed: Int,
        val perOperator: Map<Operator, OperatorSummary>,
        val received: Bucket = Bucket(0, 0),
        val perCategory: Map<Category, Bucket> = emptyMap(),
        val unclassified: Bucket = Bucket(0, 0),
        /** Numéros des envois pas encore classés (cabine ou agent), dans l'ordre d'apparition. */
        val unclassifiedNumbers: List<String> = emptyList(),
    )

    data class Bucket(val count: Int, val total: Long)

    data class OperatorSummary(val count: Int, val total: Long, val fees: Long, val lastBalance: Long?)

    private fun ok(tx: ReportTx) = tx.status == TxStatus.SUCCESS && !tx.excluded

    /** Envois qui comptent dans le rapport : réussis, non exclus, hors argent reçu, triés par heure. */
    fun counted(txs: List<ReportTx>): List<ReportTx> =
        txs.filter { ok(it) && it.kind.isSend }.sortedBy { it.timestamp }

    /** Argent reçu qui compte dans le rapport (UV des agents). */
    fun receipts(txs: List<ReportTx>): List<ReportTx> =
        txs.filter { ok(it) && it.kind == TxKind.RECEIVED }.sortedBy { it.timestamp }

    fun summarize(txs: List<ReportTx>, options: Options): Summary {
        val inScope = txs.filter { it.operator in options.operators }
        val sends = counted(inScope)
        fun value(tx: ReportTx) = tx.amount + if (options.includeFeesInTotal) (tx.fee ?: 0L) else 0L

        val per = options.operators.associateWith { op ->
            val list = sends.filter { it.operator == op }
            val fees = list.sumOf { it.fee ?: 0L }
            val lastBalance = inScope.filter { it.operator == op && it.balanceAfter != null && it.status == TxStatus.SUCCESS }
                .maxByOrNull { it.timestamp }?.balanceAfter
            OperatorSummary(list.size, list.sumOf(::value), fees, lastBalance)
        }
        val rec = receipts(inScope)
        val unclassified = sends.filter { it.category == null }
        return Summary(
            count = per.values.sumOf { it.count },
            total = per.values.sumOf { it.total },
            fees = per.values.sumOf { it.fees },
            failed = inScope.count { it.status == TxStatus.FAILED && it.kind.isSend },
            perOperator = per,
            received = Bucket(rec.size, rec.sumOf { it.amount }),
            perCategory = Category.entries.associateWith { c ->
                sends.filter { it.category == c }.let { l -> Bucket(l.size, l.sumOf(::value)) }
            },
            unclassified = Bucket(unclassified.size, unclassified.sumOf(::value)),
            unclassifiedNumbers = unclassified.mapNotNull { it.recipientNumber }.distinct(),
        )
    }

    fun generate(date: LocalDate, txs: List<ReportTx>, options: Options, zone: ZoneId = ZoneId.systemDefault()): String {
        val inScope = txs.filter { it.operator in options.operators }
        val summary = summarize(inScope, options)
        val sends = counted(inScope)
        val rec = receipts(inScope)
        val multiOperator = options.operators.size > 1

        fun value(tx: ReportTx) = tx.amount + if (options.includeFeesInTotal && tx.kind.isSend) (tx.fee ?: 0L) else 0L

        fun line(index: Int, tx: ReportTx, withOperator: Boolean): String {
            val time = Instant.ofEpochMilli(tx.timestamp).atZone(zone).format(timeFmt)
            val op = if (withOperator) "${tx.operator.shortLabel} - " else ""
            return "$index. $time - $op${recipientLabel(tx)} - ${Formats.amount(value(tx))}"
        }

        fun lines(list: List<ReportTx>, withOperator: Boolean) =
            list.mapIndexed { i, tx -> line(i + 1, tx, withOperator) }

        /** « TITRE : 3 envois - 45 000 F » suivi de la liste numérotée. */
        fun rubric(title: String, list: List<ReportTx>, one: String, many: String): String {
            val head = if (list.isEmpty()) "$title : 0 $one"
            else "$title : ${Formats.plural(list.size, one, many)} - ${Formats.amount(list.sumOf(::value))}"
            return (listOf(head) + lines(list, multiOperator)).joinToString("\n")
        }

        fun opLines(op: Operator): List<String> = lines(sends.filter { it.operator == op }, false)

        fun opHeader(op: Operator): String {
            val s = summary.perOperator[op] ?: OperatorSummary(0, 0, 0, null)
            return if (s.count == 0) "${op.reportLabel} : 0 envoi"
            else "${op.reportLabel} : ${Formats.plural(s.count, "envoi")} - ${Formats.amount(s.total)}"
        }

        val blocks = StringBuilder()
        options.operators.forEachIndexed { i, op ->
            val l = opLines(op)
            if (i > 0) {
                val prevHadLines = opLines(options.operators[i - 1]).isNotEmpty()
                blocks.append(if (prevHadLines || l.isNotEmpty()) "\n\n" else "\n")
            }
            blocks.append(opHeader(op))
            l.forEach { blocks.append('\n').append(it) }
        }

        val soldes = if (!options.showBalances) "" else options.operators.mapNotNull { op ->
            summary.perOperator[op]?.lastBalance?.let { "Solde ${op.balanceLabel} fin de journée : ${Formats.amount(it)}" }
        }.joinToString("\n")

        val cabines = sends.filter { it.category == Category.CABINE }
        val agents = sends.filter { it.category == Category.AGENT }
        val toClassify = sends.filter { it.category == null }

        val values = mutableMapOf(
            "date" to date.format(dateFmt),
            "jour" to date.format(longDateFmt),
            "periode" to options.period.label(),
            "cabines" to rubric(Category.CABINE.reportLabel, cabines, "envoi", "envois"),
            "agents" to rubric(Category.AGENT.reportLabel, agents, "envoi", "envois"),
            "uv_recues" to rubric("UV REÇUES DES AGENTS", rec, "réception", "réceptions"),
            "a_classer" to if (toClassify.isEmpty()) "" else "\n" + rubric("À CLASSER", toClassify, "envoi", "envois") + "\n",
            "total_cabines" to Formats.amount(cabines.sumOf(::value)),
            "nb_cabines" to cabines.size.toString(),
            "total_agents" to Formats.amount(agents.sumOf(::value)),
            "nb_agents" to agents.size.toString(),
            "total_uv" to Formats.amount(rec.sumOf(::value)),
            "nb_uv" to rec.size.toString(),
            "operateurs" to blocks.toString(),
            "liste" to lines(sends, true).joinToString("\n").ifEmpty { "Aucun envoi" },
            "total" to Formats.amount(summary.total),
            "nb" to summary.count.toString(),
            "nb_envois" to Formats.plural(summary.count, "envoi"),
            "frais" to Formats.amount(summary.fees),
            "soldes" to soldes,
            "nb_echecs" to summary.failed.toString(),
        )
        Operator.entries.forEach { op ->
            val key = op.name.lowercase()
            val s = summary.perOperator[op]
            values["${key}_liste"] = opLines(op).joinToString("\n").ifEmpty { "Aucun envoi" }
            values["${key}_total"] = Formats.amount(s?.total ?: 0)
            values["${key}_nb"] = (s?.count ?: 0).toString()
            values["${key}_solde"] = s?.lastBalance?.let { Formats.amount(it) } ?: "-"
        }

        var out = Regex("\\{([a-z_]+)\\}").replace(options.template) { m -> values[m.groupValues[1]] ?: m.value }
        out = out.lines().joinToString("\n") { it.trimEnd() }
        out = Regex("\n{3,}").replace(out, "\n\n")
        return out.trim()
    }

    fun recipientLabel(tx: ReportTx): String {
        val number = tx.recipientNumber
        val name = tx.recipientName?.takeIf { it.isNotBlank() }
        return when {
            name != null && number != null -> "$name ($number)"
            name != null -> name
            number != null -> number
            else -> if (tx.kind == TxKind.RECEIVED) "Expéditeur inconnu" else "Bénéficiaire inconnu"
        }
    }
}
