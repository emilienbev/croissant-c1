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
