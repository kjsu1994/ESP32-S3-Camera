package io.github.kjsu1994.workbenchcam

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardHostTest {
    @Test fun allowsPrivateLanAndMdnsHostsOnly() {
        assertTrue(isAllowedLocalHost("192.168.4.1"))
        assertTrue(isAllowedLocalHost("192.168.0.42"))
        assertTrue(isAllowedLocalHost("10.0.0.15"))
        assertTrue(isAllowedLocalHost("172.16.5.20"))
        assertTrue(isAllowedLocalHost("dockycam.local"))
        assertTrue(isAllowedLocalHost("workbenchcam.local"))

        assertFalse(isAllowedLocalHost("8.8.8.8"))
        assertFalse(isAllowedLocalHost("172.32.0.1"))
        assertFalse(isAllowedLocalHost("example.com"))
        assertFalse(isAllowedLocalHost(""))
    }
}
