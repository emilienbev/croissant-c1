package com.bleural.croissant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The same cases as the installer's (installer/tests/test_devicename.py): one rule on both sides. */
class DeviceNameTest {

    @Test fun takesAPlainName() {
        for (n in listOf("Kitchen", "Emi's R1", "C1 2", "a", "x".repeat(DeviceName.MAX), "Café"))
            assertNull(n, DeviceName.problem(n))
    }

    @Test fun refusesWhatTheInstallerRefuses() {
        for (n in listOf("", "x".repeat(DeviceName.MAX + 1), "a/b", "a\"b", "éé", ". -"))
            assertNotNull(n, DeviceName.problem(n))
    }

    @Test fun hostnameIsADnsLabel() {
        assertEquals("croissant-c1", DeviceName.hostname("Croissant C1"))
        assertEquals("emis-r1", DeviceName.hostname("Émi's R1"))
        assertEquals("desk-two", DeviceName.hostname("  Desk & Two. "))
        assertEquals("cafe", DeviceName.hostname("Café"))
    }
}
