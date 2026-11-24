package com.ravito.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ajouter ici chaque vrai SMS reçu (numéros masqués) pour verrouiller les règles de lecture.
 */
class SmsParserTest {

    private fun tx(op: Operator, body: String): SmsParser.Parsed {
        val r = SmsParser.parse(op, body)
        assertTrue("Attendu une transaction, obtenu $r", r is SmsParser.Result.Transaction)
        return (r as SmsParser.Result.Transaction).tx
    }

    @Test
    fun orangeExempleDuCahierDesCharges() {
        val t = tx(
            Operator.ORANGE,
            "Transfert de 50000 FCFA vers le 0102030405 reussi. Frais: 0 FCFA. Nouveau solde: 325000 FCFA. Ref: CI241124.0915.A12345",
        )
        assertEquals(TxStatus.SUCCESS, t.status)
        assertEquals(50_000L, t.amount)
        assertEquals(0L, t.fee)
        assertEquals(325_000L, t.balanceAfter)
        assertEquals("0102030405", t.recipientNumber)
        assertEquals("CI241124.0915.A12345", t.reference)
        assertNull(t.recipientName)
    }

    @Test
    fun orangeAvecAccentsEtEspaces() {
        val t = tx(
            Operator.ORANGE,
            "Transfert de 25 000 FCFA vers le 07 48 86 96 03 réussi. Frais : 250 FCFA. Nouveau solde : 1 200 500 FCFA. Réf : CI241124.1402.B67890.",
        )
        assertEquals(25_000L, t.amount)
        assertEquals(250L, t.fee)
        assertEquals(1_200_500L, t.balanceAfter)
        assertEquals("0748869603", t.recipientNumber)
        assertEquals("CI241124.1402.B67890", t.reference)
    }

    @Test
    fun mtnAvecNomEtIndicatif() {
        val t = tx(
            Operator.MTN,
            "Vous avez transfere 10000 FCFA a JEAN KOUASSI (2250505060708) le 2026-11-24 10:12:01. Frais 100 FCFA. Votre nouveau solde: 90000 FCFA. ID transaction: 8812345678.",
        )
        assertEquals(10_000L, t.amount)
        assertEquals(100L, t.fee)
        assertEquals(90_000L, t.balanceAfter)
        assertEquals("0505060708", t.recipientNumber)
        assertEquals("JEAN KOUASSI", t.recipientName)
        assertEquals("8812345678", t.reference)
    }

    @Test
    fun moovEnvoi() {
        val t = tx(
            Operator.MOOV,
            "Vous avez envoye 15000 FCFA au 0102030405. Frais: 0 FCFA. Nouveau solde: 45000 FCFA. Ref: MV241124ABC123",
        )
        assertEquals(15_000L, t.amount)
        assertEquals("0102030405", t.recipientNumber)
        assertEquals(45_000L, t.balanceAfter)
        assertEquals("MV241124ABC123", t.reference)
    }

    @Test
    fun waveNotification() {
        val t = tx(Operator.WAVE, "Vous avez envoyé 5.000F à Awa Koné (07 07 08 09 10)")
        assertEquals(5_000L, t.amount)
        assertEquals("0707080910", t.recipientNumber)
        assertEquals("Awa Koné", t.recipientName)
    }

    @Test
    fun waveSansNumero() {
        val t = tx(Operator.WAVE, "Vous avez envoyé 12 500F à Boutique Yao")
        assertEquals(12_500L, t.amount)
        assertEquals("Boutique Yao", t.recipientName)
    }

    @Test
    fun depotAgent() {
        val t = tx(
            Operator.ORANGE,
            "Vous avez effectue un depot de 20000 FCFA au 0707080910. Nouveau solde: 100000 FCFA. Ref: CI2411.X1",
        )
        assertEquals(TxKind.DEPOSIT, t.kind)
        assertEquals(20_000L, t.amount)
    }

    @Test
    fun echecMarqueEchoue() {
        val t = tx(Operator.ORANGE, "Echec du transfert de 50000 FCFA vers le 0102030405. Solde insuffisant.")
        assertEquals(TxStatus.FAILED, t.status)
        assertEquals(50_000L, t.amount)
    }

    @Test
    fun argentRecuIgnore() {
        val r = SmsParser.parse(
            Operator.ORANGE,
            "Vous avez recu un transfert de 30000 FCFA du 0707080910. Nouveau solde: 355000 FCFA. Ref: CI2411.Y1",
        )
        assertEquals(SmsParser.Result.Ignored(SmsParser.IgnoreReason.RECEIVED), r)
        val r2 = SmsParser.parse(Operator.WAVE, "Awa Koné vous a envoyé 2.000F")
        assertEquals(SmsParser.Result.Ignored(SmsParser.IgnoreReason.RECEIVED), r2)
    }

    @Test
    fun codeEtPromoIgnores() {
        assertTrue(SmsParser.parse(Operator.ORANGE, "Votre code de confirmation est 123456. Ne le partagez pas.") is SmsParser.Result.Ignored)
        assertTrue(SmsParser.parse(Operator.ORANGE, "PROMO! Gagnez 1000 FCFA de bonus pour 3 operations aujourd'hui") is SmsParser.Result.Ignored)
        assertTrue(SmsParser.parse(Operator.ORANGE, "Votre solde est de 12000 FCFA.") is SmsParser.Result.Ignored)
    }

    @Test
    fun formatInconnuAVerifier() {
        val r = SmsParser.parse(Operator.ORANGE, "Operation 4500 FCFA effectuee vers 0102030405 le 24/11")
        assertTrue("Attendu À vérifier, obtenu $r", r is SmsParser.Result.Unrecognized)
        assertEquals(4_500L, (r as SmsParser.Result.Unrecognized).hint.amount)
    }

    @Test
    fun dedupParReference() {
        val a = SmsParser.dedupKey(Operator.ORANGE, "ci2411.a1", "x")
        val b = SmsParser.dedupKey(Operator.ORANGE, "CI2411.A1", "y")
        assertEquals(a, b)
        val c = SmsParser.dedupKey(Operator.ORANGE, null, "Transfert de 100 F", "20261124")
        val d = SmsParser.dedupKey(Operator.ORANGE, null, "Transfert  de 100 F", "20261124")
        assertEquals(c, d)
        assertNotEquals(c, SmsParser.dedupKey(Operator.ORANGE, null, "Transfert de 100 F", "20261125"))
    }

    @Test
    fun reconnaissanceExpediteur() {
        val senders = Operator.entries.associateWith { it.defaultSenders }
        assertEquals(Operator.ORANGE, Operator.matchSender("OrangeMoney", senders))
        assertEquals(Operator.ORANGE, Operator.matchSender("Orange Money CI", senders))
        assertEquals(Operator.MTN, Operator.matchSender("MobileMoney", senders))
        assertEquals(Operator.MOOV, Operator.matchSender("MoovMoney", senders))
        assertEquals(Operator.WAVE, Operator.matchSender("WAVE", senders))
        assertNull(Operator.matchSender("+2250707080910", senders))
        assertNull(Operator.matchSender("Orange", senders))
    }
}
