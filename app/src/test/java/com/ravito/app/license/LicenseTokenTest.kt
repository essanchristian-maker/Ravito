package com.ravito.app.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseTokenTest {

    /** Licence de test signée par le vrai serveur (appareil fictif « device-abc »). */
    private val token =
        "eyJ2IjoxLCJjb2RlIjoiVEVTVC0wMDAxIiwiZGV2IjoiZGV2aWNlLWFiYyIsIm5hbWUiOiJUZXN0Iiwic3QiOiJ0cmlhbCIsImV4cCI6MjAwMDAwMDAwMDAwMCwiZ3JhY2UiOjE3MjgwMDAwMCwiY2hrIjoxNzkwNjA0ODAwMDAwLCJpYXQiOjE3OTAwMDAwMDAwMDAsImZsYWdzIjp7ImV4cG9ydCI6ZmFsc2V9LCJwcmljZSI6NTAwMCwib20iOiIwNyA0OCA4NiA5NiAwMyIsIm5vdGljZSI6IiJ9" +
            ".MEYCIQCNhKAmYsnFBjlHTbRzxkCMP0CFAZoTJzSvNLXLXNd4qgIhAI4-D32sEStzeV8cys3P4G8ktJnjsnpQkEvYMlLLDml0"

    @Test
    fun licenceSigneeParLeServeurAcceptee() {
        val l = LicenseToken.verify(token)
        assertNotNull(l)
        l!!
        assertEquals("TEST-0001", l.code)
        assertEquals("device-abc", l.deviceId)
        assertTrue(l.isTrial)
        assertEquals(2_000_000_000_000L, l.expiresAt)
        assertEquals(2 * 86_400_000L, l.graceMs)
        assertEquals("07 48 86 96 03", l.omNumber)
        assertEquals(5000L, l.priceFcfa)
        assertFalse(l.feature("export"))
        assertTrue(l.feature("test_sms"))
    }

    @Test
    fun licenceModifieeRefusee() {
        // On remplace "trial" par "paid " dans le JSON : la signature ne correspond plus.
        val (body, sig) = token.split('.')
        val json = String(Base64Url.decode(body)).replace("\"trial\"", "\"paid\"")
        val forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray()) + "." + sig
        assertNull(LicenseToken.verify(forged))
        assertNull(LicenseToken.verify("n'importe quoi"))
    }

    @Test
    fun masquageDesSms() {
        val m = SmsMasker.mask("Vous avez recu 2.000F De BROU A NOEL (0707080910) le 24/11/2026 Nouveau solde: 15.500F ID Transaction: 18556226079.")
        assertFalse(m.contains("BROU"))
        assertFalse(m.contains("NOEL"))
        assertFalse(m.any { it.isDigit() })
        assertTrue(m, m.startsWith("Vous avez recu N.NF De NOM"))
        assertTrue(m, m.contains("ID Transaction: N."))
    }
}
