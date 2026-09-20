package com.kiwinokoto.powerwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkSnapshotTest {
    @Test
    fun emptyTransportHasNoLabel() {
        assertNull(NetworkSnapshot(InternetReachability.UNAVAILABLE).transportLabel())
    }

    @Test
    fun cellularIsAFirstClassInternetTransport() {
        val snapshot = NetworkSnapshot(
            InternetReachability.VALIDATED,
            setOf(NetworkTransport.CELLULAR)
        )

        assertEquals("données mobiles", snapshot.transportLabel())
    }

    @Test
    fun transportLabelHasStableOrder() {
        val snapshot = NetworkSnapshot(
            InternetReachability.VALIDATED,
            setOf(NetworkTransport.VPN, NetworkTransport.WIFI, NetworkTransport.CELLULAR)
        )

        assertEquals("Wi-Fi + données mobiles + VPN", snapshot.transportLabel())
    }
}
