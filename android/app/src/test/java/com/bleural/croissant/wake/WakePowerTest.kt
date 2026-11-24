package com.bleural.croissant.wake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePowerTest {
    @Test fun listensAtTheLineOrOver() {
        assertTrue(WakePower.allowed(20, charging = false))
        assertTrue(WakePower.allowed(100, charging = false))
        assertFalse(WakePower.allowed(19, charging = false))
    }

    @Test fun listensWhileChargingAtAnyLevel() {
        assertTrue(WakePower.allowed(3, charging = true))
    }
}
