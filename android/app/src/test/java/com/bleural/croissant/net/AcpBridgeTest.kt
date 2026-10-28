package com.bleural.croissant.net

import com.bleural.croissant.Agents
import com.bleural.croissant.Prefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AcpBridgeTest {

    @Test fun readsThePairingLink() {
        val b = parseAcpLink("acp-bridge://192.168.1.20:8775?token=abc-DEF_1&also=100.100.100.7,192.168.1.20")!!
        assertEquals(listOf("192.168.1.20", "100.100.100.7"), b.hosts)
        assertEquals(8775, b.port)
        assertEquals("abc-DEF_1", b.token)
        assertEquals(listOf("10.0.0.2"), parseAcpLink("acp-bridge://10.0.0.2:9000?token=t")!!.hosts)
    }

    @Test fun refusesOtherCodes() {
        assertNull(parseAcpLink("WIFI:S:home;T:WPA;P:secret;;"))
        assertNull(parseAcpLink("https://github.com/owner/repo"))
        assertNull(parseAcpLink("acp-bridge://10.0.0.2:8775"))
        assertNull(parseAcpLink("acp-bridge://10.0.0.2:8775?token="))
    }

    @Test fun groupsSessionsByFolderNewestFirst() {
        val s = listOf(
            AgentSession("a", "/work/rabbit", "old", 1_000, false),
            AgentSession("b", "/work/curator/", "newest", 5_000, false),
            AgentSession("c", "/work/rabbit", "newer", 3_000, false),
            AgentSession("d", "/work/curator/", null, null, false),
        )
        val f = Agents.folders(s)
        assertEquals(listOf("curator", "rabbit"), f.map { it.name })
        assertEquals(listOf("b", "d"), f[0].sessions.map { it.id })
        assertEquals(listOf("c", "a"), f[1].sessions.map { it.id })
    }

    @Test fun aLinkIsItsWords() {
        assertEquals("Created haiku.txt, see the logo.", plain("Created [haiku.txt](/tmp/x/haiku.txt), see the ![logo](a.png)."))
    }

    @Test fun saysHowLongAgo() {
        val now = 1_000_000_000_000L
        assertEquals("now", Agents.age(now - 30_000, now))
        assertEquals("5m", Agents.age(now - 5 * 60_000, now))
        assertEquals("3h", Agents.age(now - 3 * 3_600_000, now))
        assertEquals("2d", Agents.age(now - 2 * 86_400_000L, now))
        assertEquals("6w", Agents.age(now - 6 * 7 * 86_400_000L, now))
        assertNull(Agents.age(null, now))
    }

    /** Against a running bridge: ACPBRIDGE=<its pairing link> (acp-bridge pair prints it as a QR). */
    @Test fun talksToARealBridge() = runBlocking {
        val link = System.getenv("ACPBRIDGE")
        assumeTrue("ACPBRIDGE unset", !link.isNullOrBlank())
        val b = parseAcpLink(link!!)!!
        val client = AcpBridgeClient { b }
        try {
            assertEquals(1, client.hello().version)
            val agents = client.agents()
            assertTrue(agents.any { it.id == "codex" && it.ready })
            val first = client.sessions("codex")
            assertTrue(first.sessions.isNotEmpty())
            assertTrue(first.sessions.all { it.cwd.startsWith("/") })
            val next = first.next?.let { client.sessions("codex", it) }
            if (next != null) assertTrue(next.sessions.none { s -> first.sessions.any { it.id == s.id } })
        } finally {
            client.close()
        }
        val wrong = AcpBridgeClient { Prefs.AcpBridge(b.hosts, b.port, "not-the-token") }
        assertThrows(AcpBridgeClient.Refused::class.java) { runBlocking { wrong.hello() } }
        Unit
    }
}
