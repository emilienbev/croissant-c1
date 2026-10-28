package com.bleural.croissant.esim

import net.typeblog.lpac_jni.LocalProfileAssistant
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Why a write to the card failed, as the page says it: a sentence, and the raw words under it. */
data class EsimError(val sentence: String, val detail: String? = null)

/**
 * Turns what lpac-jni hands back after a failed download into an [EsimError]. The most
 * specific source wins: the SM-DP+'s own failure status, then the card's reason for
 * refusing the profile, then the connection, then the card going quiet.
 */
object EsimErrors {

    /** The failure status in an ES9+ response body; null if the body is not one or reports success. */
    data class ServerStatus(val subject: String?, val reason: String?, val message: String?)

    fun serverStatus(body: ByteArray?): ServerStatus? {
        if (body == null || body.isEmpty()) return null
        return try {
            val status = JSONObject(String(body)).getJSONObject("header").getJSONObject("functionExecutionStatus")
            if (status.optString("status") !in setOf("Failed", "Expired")) return null
            val data = status.optJSONObject("statusCodeData")
            ServerStatus(
                data?.optString("subjectCode")?.ifBlank { null },
                data?.optString("reasonCode")?.ifBlank { null },
                data?.optString("message")?.ifBlank { null },
            )
        } catch (e: Exception) {
            null
        }
    }

    fun download(lpaReason: String, httpBody: ByteArray?, httpError: Throwable?, apduError: Throwable?): EsimError {
        serverStatus(httpBody)?.let { s ->
            val codes = if (s.subject != null && s.reason != null) "(${s.subject} ${s.reason})" else null
            return EsimError(serverSentence(s.subject, s.reason), listOfNotNull(s.message, codes).joinToString(" ").ifEmpty { null })
        }
        when (lpaReason) {
            "ES10B_ERROR_REASON_INSTALL_FAILED_DUE_TO_ICCID_ALREADY_EXISTS_ON_EUICC" ->
                return EsimError("This profile is already on the card.")
            "ES10B_ERROR_REASON_INSTALL_FAILED_DUE_TO_INSUFFICIENT_MEMORY_FOR_PROFILE" ->
                return EsimError("The card is full. Delete a profile to make room.")
            "ES10B_ERROR_REASON_UNDEFINED" -> Unit
            else -> return EsimError("The card could not install the profile.", lpaReason)
        }
        if (httpError != null) return EsimError(connectionSentence(httpError), httpError.message ?: httpError.javaClass.simpleName)
        if (apduError != null) return EsimError("The card stopped answering.", apduError.message)
        return EsimError("The download failed.", lpaReason)
    }

    /** Why the card would not take a new name for a profile. */
    fun rename(e: Throwable): EsimError = when (e) {
        is LocalProfileAssistant.ProfileNameTooLongException -> EsimError("That name is too long. The card takes 64 characters.")
        is LocalProfileAssistant.ProfileNameIsInvalidUTF8Exception -> EsimError("The card could not take those characters.")
        else -> EsimError("The card refused the new name.", e.message)
    }

    /** The subject and reason codes of SGP.22 annex H, as lpac's es9p_errors.c lists them. */
    fun serverSentence(subject: String?, reason: String?): String = when (subject to reason) {
        "8.2.6" to "3.8" -> "The server refused the code. It may be mistyped, used already or cancelled."
        "8.2.6" to "3.1", "8.2.6" to "3.3" -> "The code does not match this profile."
        "8.2.7" to "2.2" -> "This profile needs a confirmation code."
        "8.2.7" to "3.8" -> "The confirmation code was refused."
        "8.2.7" to "6.4" -> "Too many wrong confirmation codes. The operator must issue a new one."
        "8.8.5" to "4.1" -> "The download order has expired. Ask the operator for a new code."
        "8.8.5" to "6.4" -> "This code has been tried too many times. Ask the operator for a new one."
        "8.1" to "4.8" -> "The card does not have room for this profile."
        "8.1.1" to "3.1", "8.1.1" to "3.8", "8.2.1" to "3.1" -> "This code belongs to a different eSIM."
        "8.2.5" to "4.3" -> "The server has no profile this card can take."
        "8.2" to "3.7", "8.2.5" to "3.7" -> "The server has no profile left for this code."
        "8.8.4" to "3.7" -> "The server has no certificate this card trusts."
        "8.8.1" to "3.8" -> "The server address does not match this code."
        else -> "The server refused the download."
    }

    fun connectionSentence(e: Throwable): String = when (e) {
        is UnknownHostException -> "The server could not be found. Check the address and the internet connection."
        is SSLException -> "The server's certificate could not be trusted."
        is SocketTimeoutException -> "The server did not answer in time."
        is ConnectException -> "The server could not be reached."
        else -> "The connection to the server failed."
    }
}
