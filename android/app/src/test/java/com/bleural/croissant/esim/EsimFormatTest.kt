package com.bleural.croissant.esim

import org.junit.Assert.assertEquals
import org.junit.Test

class EsimFormatTest {
    private fun profile(nickname: String? = null, name: String? = null, provider: String? = null) =
        EsimProfile("8944000000000000019", nickname, name, provider, enabled = false, kind = EsimProfile.Kind.Operational)

    @Test fun freeSpaceInWholeKilobytes() {
        // The 9eSIM card empty, then with BetterRoaming on it.
        assertEquals("421 KB", EsimFormat.bytes(431434))
        assertEquals("376 KB", EsimFormat.bytes(385400))
    }

    @Test fun eidInFours() {
        assertEquals("8904 9032 0000 0000 0000 0000 0001 2345", EsimFormat.grouped("89049032000000000000000000012345"))
    }

    @Test fun iccidKeepsItsOddTail() {
        assertEquals("8944 0000 0000 0000 019", EsimFormat.grouped("8944000000000000019"))
    }

    @Test fun nameFallsBackFromNicknameToIccid() {
        assertEquals("Travel", profile(nickname = "Travel", name = "BetterRoaming", provider = "Truphone").name)
        assertEquals("BetterRoaming", profile(name = "BetterRoaming", provider = "Truphone").name)
        assertEquals("Truphone", profile(provider = "Truphone").name)
        assertEquals("8944000000000000019", profile().name)
    }
}
