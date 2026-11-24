package com.ravito.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ligne d'historique telle que vue par le rapport (nom déjà résolu via le carnet). */
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

    const val DEFAULT_TEMPLATE = """RAPPORT DU {date}

{operateurs}

TOTAL : {nb_envois} - {total}
{soldes}"""

    /** Aide affichée dans l'éditeur de modèle. */
    val PLACEHOLDERS: List<Pair<String, String>> = listOf(
        "{date}" to "Date du rapport (24/11/2026)",
        "{jour}" to "Date en toutes lettres (lundi 24 novembre 2026)",
        "{periode}" to "Période couverte (00:00 - 23:59)",
        "{operateurs}" to "Un bloc par opérateur actif, avec la liste numérotée",
        "{liste}" to "Tous les envois, tous opérateurs confondus",
        "{total}" to "Montant total (120 000 F)",
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
    )

    data class OperatorSummary(val count: Int, val total: Long, val fees: Long, val lastBalance: Long?)

    /** Envois qui comptent dans le rapport : réussis et non exclus, triés par heure. */
    fun counted(txs: List<ReportTx>): List<ReportTx> =
        txs.filter { it.status == TxStatus.SUCCESS && !it.excluded }.sortedBy { it.timestamp }

    fun summarize(txs: List<ReportTx>, options: Options): Summary {
        val ok = counted(txs)
        val per = options.operators.associateWith { op ->
            val list = ok.filter { it.operator == op }
            val fees = list.sumOf { it.fee ?: 0L }
            val total = list.sumOf { it.amount } + if (options.includeFeesInTotal) fees else 0L
            val lastBalance = txs.filter { it.operator == op && it.balanceAfter != null && it.status == TxStatus.SUCCESS }
                .maxByOrNull { it.timestamp }?.balanceAfter
            OperatorSummary(list.size, total, fees, lastBalance)
        }
        return Summary(
            count = per.values.sumOf { it.count },
            total = per.values.sumOf { it.total },
            fees = per.values.sumOf { it.fees },
            failed = txs.count { it.status == TxStatus.FAILED && it.operator in options.operators },
            perOperator = per,
        )
    }

    fun generate(date: LocalDate, txs: List<ReportTx>, options: Options, zone: ZoneId = ZoneId.systemDefault()): String {
        val summary = summarize(txs, options)
        val ok = counted(txs).filter { it.operator in options.operators }

        fun line(index: Int, tx: ReportTx, withOperator: Boolean): String {
            val time = Instant.ofEpochMilli(tx.timestamp).atZone(zone).format(timeFmt)
            val who = recipientLabel(tx)
            val amount = tx.amount + if (options.includeFeesInTotal) (tx.fee ?: 0L) else 0L
            val op = if (withOperator) "${tx.operator.shortLabel} - " else ""
            return "$index. $time - $op$who - ${Formats.amount(amount)}"
        }

        fun opLines(op: Operator): List<String> =
            ok.filter { it.operator == op }.mapIndexed { i, tx -> line(i + 1, tx, false) }

        fun opHeader(op: Operator): String {
            val s = summary.perOperator[op] ?: OperatorSummary(0, 0, 0, null)
            return if (s.count == 0) "${op.reportLabel} : 0 envoi"
            else "${op.reportLabel} : ${Formats.plural(s.count, "envoi")} - ${Formats.amount(s.total)}"
        }

        val blocks = StringBuilder()
        options.operators.forEachIndexed { i, op ->
            val lines = opLines(op)
            if (i > 0) {
                val prevHadLines = opLines(options.operators[i - 1]).isNotEmpty()
                blocks.append(if (prevHadLines || lines.isNotEmpty()) "\n\n" else "\n")
            }
            blocks.append(opHeader(op))
            lines.forEach { blocks.append('\n').append(it) }
        }

        val soldes = if (!options.showBalances) "" else options.operators.mapNotNull { op ->
            summary.perOperator[op]?.lastBalance?.let { "Solde ${op.balanceLabel} fin de journée : ${Formats.amount(it)}" }
        }.joinToString("\n")

        val values = mutableMapOf(
            "date" to date.format(dateFmt),
            "jour" to date.format(longDateFmt),
            "periode" to options.period.label(),
            "operateurs" to blocks.toString(),
            "liste" to ok.mapIndexed { i, tx -> line(i + 1, tx, true) }.joinToString("\n").ifEmpty { "Aucun envoi" },
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
            else -> "Bénéficiaire inconnu"
        }
    }
}
