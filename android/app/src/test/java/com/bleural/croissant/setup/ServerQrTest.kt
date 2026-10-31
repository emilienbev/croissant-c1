package com.bleural.croissant.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerQrTest {
    @Test fun whatTheServerPrints() {
        // urlencode's own: the space a +, the curly apostrophe its UTF-8 escapes.
        assertEquals(
            ServerQr("100.100.100.103", 8765, "Kim’s MacBook Pro"),
            ServerQr.parse("croissant://server?host=100.100.100.103&port=8765&name=Kim%E2%80%99s+MacBook+Pro"),
        )
    }

    @Test fun fieldsInAnyOrderAndBlanksForgiven() {
        assertEquals(
            ServerQr("100.100.100.103", 9000, "mac"),
            ServerQr.parse("  CROISSANT://server?name=mac&port=9000&host=100.100.100.103\n"),
        )
    }

    @Test fun noNameIsAnEmptyOne() {
        assertEquals("", ServerQr.parse("croissant://server?host=100.100.100.103&port=8765")?.name)
    }

    @Test fun aLanCodeCarriesItsToken() {
        assertEquals(
            ServerQr("192.168.1.5", 8765, "Mac", "AbC-123_xyzAbC-123_xyzAbC-123_xy"),
            ServerQr.parse("croissant://server?host=192.168.1.5&port=8765&name=Mac&token=AbC-123_xyzAbC-123_xyzAbC-123_xy"),
        )
        assertEquals("", ServerQr.parse("croissant://server?host=100.100.100.103&port=8765")?.token)
    }

    @Test fun aTokenTheServerDoesNotMakeRefusesTheCode() {
        assertNull(ServerQr.parse("croissant://server?host=192.168.1.5&port=8765&token="))
        assertNull(ServerQr.parse("croissant://server?host=192.168.1.5&port=8765&token=short"))
        assertNull(ServerQr.parse("croissant://server?host=192.168.1.5&port=8765&token=has+a+space+in+it+somewhere"))
        assertNull(ServerQr.parse("croissant://server?host=192.168.1.5&port=8765&token=" + "a".repeat(129)))
    }

    @Test fun otherQrCodesAreNotServers() {
        // What the scanner meets when it is pointed at something else.
        assertNull(ServerQr.parse("WIFI:S:home;T:WPA;P:secret;;"))
        assertNull(ServerQr.parse("http://100.100.100.103:8765/"))
        assertNull(ServerQr.parse("https://server?host=100.100.100.103&port=8765"))
        assertNull(ServerQr.parse("croissant://box?host=100.100.100.103&port=8765"))
    }

    @Test fun notServers() {
        assertNull(ServerQr.parse("croissant://server?port=8765&name=mac"))
        assertNull(ServerQr.parse("croissant://server?host=&port=8765"))
        assertNull(ServerQr.parse("croissant://server?host=+&port=8765"))
        assertNull(ServerQr.parse("croissant://server?host=100.100.100.103"))
        assertNull(ServerQr.parse("croissant://server?host=100.100.100.103&port="))
        assertNull(ServerQr.parse("croissant://server?host=100.100.100.103&port=http"))
        assertNull(ServerQr.parse("croissant://server?host=100.100.100.103&port=0"))
        assertNull(ServerQr.parse("croissant://server?host=100.100.100.103&port=65536"))
        assertNull(ServerQr.parse("croissant://server?host=100.100.100.103&port=8765&name=%E"))
    }
}
