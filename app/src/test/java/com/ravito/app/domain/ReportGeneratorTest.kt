package com.ravito.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ReportGeneratorTest {

    private val zone = ZoneId.of("Africa/Abidjan")
    private val date = LocalDate.of(2026, 11, 24)

    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 11, 24, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun tx(
        h: Int,
        m: Int,
        amount: Long,
        number: String?,
        name: String? = null,
        balance: Long? = null,
        status: TxStatus = TxStatus.SUCCESS,
        excluded: Boolean = false,
        op: Operator = Operator.ORANGE,
        fee: Long? = 0,
    ) = ReportTx(op, at(h, m), amount, fee, balance, number, name, status, excluded)

    @Test
    fun exempleDuCahierDesCharges() {
        val txs = listOf(
            tx(9, 15, 50_000, "0102030405", "Boutique Awa", 325_000),
            tx(11, 40, 40_000, "0707080910", balance = 285_000),
            tx(16, 5, 30_000, "0505060708", "Kiosque Yao", 275_000),
            tx(17, 0, 10_000, "0101010101", status = TxStatus.FAILED),
            tx(18, 0, 5_000, "0101010101", excluded = true),
        )
        val expected = listOf(
            "RAPPORT DU 24/11/2026",
            "",
            "ORANGE MONEY : 3 envois - 120 000 F",
            "1. 09:15 - Boutique Awa (0102030405) - 50 000 F",
            "2. 11:40 - 0707080910 - 40 000 F",
            "3. 16:05 - Kiosque Yao (0505060708) - 30 000 F",
            "",
            "MTN : 0 envoi",
            "MOOV : 0 envoi",
            "WAVE : 0 envoi",
            "",
            "TOTAL : 3 envois - 120 000 F",
            "Solde Orange fin de journée : 275 000 F",
        ).joinToString("\n")
        assertEquals(
            expected,
            ReportGenerator.generate(date, txs, ReportGenerator.Options(template = ReportGenerator.OPERATORS_TEMPLATE), zone),
        )
    }

    @Test
    fun troisRubriques() {
        val txs = listOf(
            tx(7, 45, 30_000, "0501000001", "Cabine Koffi", op = Operator.MTN).copy(category = Category.CABINE),
            tx(8, 33, 10_000, "0501000003", op = Operator.MTN).copy(category = Category.CABINE),
            tx(9, 32, 100_000, "0501000002", op = Operator.MTN, balance = 1_110_000).copy(category = Category.AGENT),
            tx(10, 0, 15_000, "0501000004", op = Operator.MTN),
            tx(12, 10, 200_000, "0501000002", op = Operator.MTN).copy(kind = TxKind.RECEIVED, category = Category.AGENT),
        )
        val expected = listOf(
            "RAPPORT DU 24/11/2026",
            "",
            "1. CABINES : 2 envois - 40 000 F",
            "1. 07:45 - Cabine Koffi (0501000001) - 30 000 F",
            "2. 08:33 - 0501000003 - 10 000 F",
            "",
            "2. AGENTS MOBILE MONEY : 1 envoi - 100 000 F",
            "1. 09:32 - 0501000002 - 100 000 F",
            "",
            "3. UV REÇUES DES AGENTS : 1 réception - 200 000 F",
            "1. 12:10 - 0501000002 - 200 000 F",
            "",
            "À CLASSER : 1 envoi - 15 000 F",
            "1. 10:00 - 0501000004 - 15 000 F",
            "",
            "TOTAL ENVOYÉ : 4 envois - 155 000 F",
            "Solde MTN fin de journée : 1 110 000 F",
        ).joinToString("\n")
        val options = ReportGenerator.Options(operators = listOf(Operator.MTN))
        assertEquals(expected, ReportGenerator.generate(date, txs, options, zone))
        val s = ReportGenerator.summarize(txs, options)
        assertEquals(listOf("0501000004"), s.unclassifiedNumbers)
        assertEquals(200_000L, s.received.total)
    }

    @Test
    fun fraisInclus() {
        val txs = listOf(
            tx(9, 0, 1_000, "0102030405", fee = 10),
            tx(10, 0, 2_000, "0102030406", fee = 20, op = Operator.MTN),
        )
        val out = ReportGenerator.generate(
            date,
            txs,
            ReportGenerator.Options(template = "{nb_envois} {total} frais {frais}|{soldes}|", includeFeesInTotal = true),
            zone,
        )
        assertEquals("2 envois 3 030 F frais 30 F||", out)
    }

    @Test
    fun periodeQuiPasseMinuit() {
        val r = ReportPeriod(7 * 60, 2 * 60).range(date, zone)
        assertEquals(LocalDateTime.of(2026, 11, 24, 7, 0).atZone(zone).toInstant().toEpochMilli(), r.first)
        assertEquals(LocalDateTime.of(2026, 11, 25, 2, 1).atZone(zone).toInstant().toEpochMilli(), r.last + 1)
    }

    @Test
    fun formatMontant() {
        assertEquals("1 200 500 F", Formats.amount(1_200_500))
        assertEquals("0 F", Formats.amount(0))
        assertEquals("950 F", Formats.amount(950))
    }
}
