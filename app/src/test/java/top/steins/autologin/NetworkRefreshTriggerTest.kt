package top.steins.autologin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkRefreshTriggerTest {

    private val previous = NetworkRefreshSnapshot(
        ipAddress = "10.21.1.10",
        ssid = "bjut_wifi"
    )

    @Test
    fun detectDefaultNetworkChange_returnsInitialForFirstSnapshot() {
        assertEquals(
            DefaultNetworkChange.INITIAL,
            detectDefaultNetworkChange(previous = null, current = previous)
        )
    }

    @Test
    fun detectDefaultNetworkChange_ignoresUnchangedSnapshot() {
        assertNull(detectDefaultNetworkChange(previous, previous))
    }

    @Test
    fun detectDefaultNetworkChange_ignoresSsidWhenItWasNotReadable() {
        assertNull(
            detectDefaultNetworkChange(
                previous.copy(ssid = null, isSsidReadable = false),
                previous
            )
        )
    }

    @Test
    fun detectDefaultNetworkChange_reportsOnlyIpOrSsidChanges() {
        assertEquals(
            DefaultNetworkChange.IP_ADDRESS_CHANGED,
            detectDefaultNetworkChange(
                previous,
                previous.copy(ipAddress = "10.21.1.11")
            )
        )
        assertEquals(
            DefaultNetworkChange.SSID_CHANGED,
            detectDefaultNetworkChange(
                previous,
                previous.copy(ssid = "bjut_wifi_2")
            )
        )
        assertEquals(
            DefaultNetworkChange.IP_ADDRESS_AND_SSID_CHANGED,
            detectDefaultNetworkChange(
                previous,
                NetworkRefreshSnapshot(ipAddress = "10.21.1.11", ssid = "bjut_wifi_2")
            )
        )
    }
}
