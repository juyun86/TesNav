package com.garan.tesnav.export

import org.junit.Assert.*
import org.junit.Test

class ArNavigationHostTest {
    @Test fun hotspotChangeReplacesHintAndMissingAckClearsIt() {
        try {
            AuthenticatedC3HostDiscovery.observeNavigationHost("192.168.0.12")
            assertEquals("192.168.0.12", AuthenticatedC3HostDiscovery.navigationHost())
            AuthenticatedC3HostDiscovery.observeNavigationHost("192.168.43.209")
            assertEquals("192.168.43.209", AuthenticatedC3HostDiscovery.navigationHost())
            assertNull(AuthenticatedC3HostDiscovery.navigationHost(System.nanoTime() + 4_000_000_000L))
            AuthenticatedC3HostDiscovery.observeNavigationHost(null)
            assertNull(AuthenticatedC3HostDiscovery.navigationHost())
        } finally { AuthenticatedC3HostDiscovery.observeNavigationHost(null) }
    }
}
