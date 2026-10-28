package com.bleural.croissant.esim

/**
 * An eSIM activation code (GSMA SGP.22 section 4.1), the text an operator's QR code
 * holds: `LPA:1$<SM-DP+ address>$<matching ID>[$<SM-DP+ OID>[$<confirmation code required>]]`.
 * The confirmation code itself is never in it; the operator gives it separately.
 */
data class ActivationCode(
    val address: String,
    /** The AC token; may be absent, then the SM-DP+ looks the card up by its EID. */
    val matchingId: String?,
    val confirmationRequired: Boolean,
) {
    companion object {
        /**
         * The code in [text], or null if [text] is not one. Case of the `LPA:` prefix and
         * surrounding blanks are forgiven, and so is a missing prefix: SGP.22 makes it
         * optional in a QR code, and some operators leave it out.
         */
        fun parse(text: String): ActivationCode? {
            val whole = text.trim()
            val t = if (whole.startsWith("LPA:", ignoreCase = true)) whole.substring(4) else whole
            val parts = t.split('$')
            if (parts.size < 3 || parts[0] != "1" || !validAddress(parts[1])) return null
            return ActivationCode(parts[1], parts[2].ifEmpty { null }, parts.getOrNull(4) == "1")
        }

        /** A host name with at least one dot, and a port if any: what an SM-DP+ address is. */
        fun validAddress(address: String): Boolean =
            Regex("""[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+(:\d{1,5})?""").matches(address)
    }
}

/** What the add page holds, kept by [EsimRepo] so it survives leaving the page and can be filled from a link. */
data class EsimDraft(val address: String = "", val matchingId: String = "", val confirmation: String = "") {
    val ready: Boolean get() = ActivationCode.validAddress(address.trim())

    enum class Field { Address, MatchingId, Confirmation }

    /**
     * [text] typed or pasted into [field]. A whole activation code pasted into any field
     * fills the address and the matching ID from it; anything else is that field's value.
     */
    fun with(field: Field, text: String): EsimDraft {
        ActivationCode.parse(text)?.let { return copy(address = it.address, matchingId = it.matchingId.orEmpty()) }
        return when (field) {
            Field.Address -> copy(address = text.trim())
            Field.MatchingId -> copy(matchingId = text.trim())
            Field.Confirmation -> copy(confirmation = text.trim())
        }
    }
}
