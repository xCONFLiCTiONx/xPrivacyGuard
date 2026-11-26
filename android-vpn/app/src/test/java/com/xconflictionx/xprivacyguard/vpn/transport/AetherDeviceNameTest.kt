package com.xconflictionx.xprivacyguard.vpn.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class AetherDeviceNameTest {

    @Test
    fun formatDeviceName_prefersUserConfiguredName() {
        assertEquals(
            "Michael's Pixel",
            AetherMasqueTransport.formatDeviceName(
                userDeviceName = "  Michael's Pixel  ",
                manufacturer = "Google",
                model = "Pixel 9 Pro"
            )
        )
    }

    @Test
    fun formatDeviceName_combinesManufacturerAndModelWithoutDuplication() {
        assertEquals(
            "Google Pixel 9 Pro",
            AetherMasqueTransport.formatDeviceName(
                userDeviceName = null,
                manufacturer = "Google",
                model = "Pixel 9 Pro"
            )
        )
        assertEquals(
            "Samsung SM-S928U",
            AetherMasqueTransport.formatDeviceName(
                userDeviceName = "",
                manufacturer = "Samsung",
                model = "Samsung SM-S928U"
            )
        )
    }

    @Test
    fun formatDeviceName_fallsBackToOsWhenDeviceInfoUnavailable() {
        assertEquals(
            "Android",
            AetherMasqueTransport.formatDeviceName(
                userDeviceName = null,
                manufacturer = "unknown",
                model = "unknown",
                device = ""
            )
        )
        assertEquals(
            "Android",
            AetherMasqueTransport.formatDeviceName(
                userDeviceName = "   ",
                manufacturer = null,
                model = null,
                device = null
            )
        )
    }
}
