package com.bleural.croissant.esim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationCodeTest {
    @Test fun truphonesFreeTestCode() {
        // The one step 1 installed.
        assertEquals(
            ActivationCode("rsp.truphone.com", "QRF-EXAMPLE-ABCDEFGH12345678", false),
            ActivationCode.parse("LPA:1\$rsp.truphone.com\$QRF-EXAMPLE-ABCDEFGH12345678"),
        )
    }

    @Test fun prefixCaseAndBlanksForgiven() {
        assertEquals("smdp.example.com", ActivationCode.parse("  lpa:1\$smdp.example.com\$ABC\n")?.address)
    }

    @Test fun oidAndConfirmationFlag() {
        val code = ActivationCode.parse("LPA:1\$smdp.example.com\$ABC\$1.3.6.1.4.1.31746\$1")!!
        assertTrue(code.confirmationRequired)
        assertFalse(ActivationCode.parse("LPA:1\$smdp.example.com\$ABC\$1.3.6.1.4.1.31746")!!.confirmationRequired)
    }

    @Test fun emptyMatchingIdIsAbsent() {
        assertNull(ActivationCode.parse("LPA:1\$smdp.example.com\$")!!.matchingId)
    }

    @Test fun aQrCodeWithoutThePrefix() {
        // SGP.22 makes LPA: optional in the QR code, and some operators leave it out.
        assertEquals(
            ActivationCode("rsp.truphone.com", "QRF-SPEEDTEST", false),
            ActivationCode.parse("1\$rsp.truphone.com\$QRF-SPEEDTEST"),
        )
    }

    @Test fun otherQrCodesAreNotCodes() {
        // What the scanner meets when it is pointed at something else.
        assertNull(ActivationCode.parse("https://example.com/esim"))
        assertNull(ActivationCode.parse("WIFI:S:home;T:WPA;P:secret;;"))
        assertNull(ActivationCode.parse("1\$example\$ABC"))
    }

    @Test fun notCodes() {
        assertNull(ActivationCode.parse("rsp.truphone.com"))
        assertNull(ActivationCode.parse("LPA:2\$smdp.example.com\$ABC"))
        assertNull(ActivationCode.parse("LPA:1\$smdp.example.com"))
        assertNull(ActivationCode.parse("LPA:1\$not a host\$ABC"))
        assertNull(ActivationCode.parse("LPA:1\$https://smdp.example.com\$ABC"))
    }

    @Test fun addressesWithAPort() {
        assertTrue(ActivationCode.validAddress("smdp.example.com:8443"))
        assertFalse(ActivationCode.validAddress("localhost"))
    }

    @Test fun pastingACodeFillsBothFields() {
        val draft = EsimDraft(confirmation = "1234")
            .with(EsimDraft.Field.MatchingId, "LPA:1\$rsp.truphone.com\$QRF-SPEEDTEST")
        assertEquals(EsimDraft("rsp.truphone.com", "QRF-SPEEDTEST", "1234"), draft)
        assertTrue(draft.ready)
    }

    @Test fun typingFillsOneField() {
        val draft = EsimDraft().with(EsimDraft.Field.Address, " rsp.truphone.com ")
        assertEquals("rsp.truphone.com", draft.address)
        assertFalse(EsimDraft(address = "rsp").ready)
    }
}
