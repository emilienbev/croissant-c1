package com.bleural.croissant.esim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import net.typeblog.lpac_jni.LocalProfileAssistant
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class EsimErrorsTest {
    @Test fun renameRefusals() {
        assertEquals(
            "That name is too long. The card takes 64 characters.",
            EsimErrors.rename(LocalProfileAssistant.ProfileNameTooLongException()).sentence,
        )
        val other = EsimErrors.rename(LocalProfileAssistant.ProfileRenameException())
        assertEquals("The card refused the new name.", other.sentence)
    }

    private fun body(status: String, subject: String?, reason: String?, message: String?): ByteArray {
        val data = listOfNotNull(
            subject?.let { "\"subjectCode\":\"$it\"" },
            reason?.let { "\"reasonCode\":\"$it\"" },
            message?.let { "\"message\":\"$it\"" },
        ).joinToString(",")
        return """{"header":{"functionExecutionStatus":{"status":"$status","statusCodeData":{$data}}}}""".toByteArray()
    }

    @Test fun serverRefusalWinsWithItsOwnWords() {
        // RedteaGO's answer on the R1, 2026-09-17: codes outside SGP.22's table.
        val e = EsimErrors.download("ES10B_ERROR_REASON_UNDEFINED", body("Failed", "9.4", "10.1", "campaign resource pool is empty"), null, null)
        assertEquals(EsimError("The server refused the download.", "campaign resource pool is empty (9.4 10.1)"), e)
    }

    @Test fun knownCodesGetTheirSentence() {
        assertEquals("The server has no profile left for this code.", EsimErrors.serverSentence("8.2.5", "3.7"))
        assertEquals("This profile needs a confirmation code.", EsimErrors.serverSentence("8.2.7", "2.2"))
    }

    @Test fun refusedCode() {
        assertEquals(
            "The server refused the code. It may be mistyped, used already or cancelled.",
            EsimErrors.download("ES10B_ERROR_REASON_UNDEFINED", body("Failed", "8.2.6", "3.8", null), null, null).sentence,
        )
    }

    @Test fun successBodyIsNotAFailure() {
        assertNull(EsimErrors.serverStatus(body("Executed-Success", null, null, null)))
        assertNull(EsimErrors.serverStatus("not json".toByteArray()))
    }

    @Test fun cardReasons() {
        assertEquals(EsimError("This profile is already on the card."),
            EsimErrors.download("ES10B_ERROR_REASON_INSTALL_FAILED_DUE_TO_ICCID_ALREADY_EXISTS_ON_EUICC", null, null, null))
        assertEquals("ES10B_ERROR_REASON_SCP03T_SECURITY_ERROR",
            EsimErrors.download("ES10B_ERROR_REASON_SCP03T_SECURITY_ERROR", null, null, null).detail)
    }

    @Test fun connectionBeforeCard() {
        val e = EsimErrors.download("ES10B_ERROR_REASON_UNDEFINED", null, UnknownHostException("rsp.nowhere.example"), IOException("card"))
        assertEquals("The server could not be found. Check the address and the internet connection.", e.sentence)
        assertEquals("rsp.nowhere.example", e.detail)
    }

    @Test fun cardWentQuiet() {
        assertEquals("The card stopped answering.", EsimErrors.download("ES10B_ERROR_REASON_UNDEFINED", null, null, IOException("timeout: tx")).sentence)
    }
}
