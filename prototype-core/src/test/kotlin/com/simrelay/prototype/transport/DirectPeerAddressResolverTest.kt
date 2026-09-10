package com.simrelay.prototype.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectPeerAddressResolverTest {
    @Test
    fun prefersReachablePrivateLanCandidateOverPointToPointMobileCandidate() {
        val selected = DirectPeerAddressResolver.selectLanIpv4(
            listOf(
                LocalAddressCandidate("10.20.30.40", true, true, false, false),
                LocalAddressCandidate("192.168.43.1", true, false, true, false)
            )
        )

        assertEquals("192.168.43.1", selected)
    }

    @Test
    fun returnsNewAddressWhenNetworkCandidatesChange() {
        val first = DirectPeerAddressResolver.selectLanIpv4(
            listOf(LocalAddressCandidate("192.168.43.1", true, false, true, false))
        )
        val second = DirectPeerAddressResolver.selectLanIpv4(
            listOf(LocalAddressCandidate("192.168.50.1", true, false, true, false))
        )

        assertEquals("192.168.43.1", first)
        assertEquals("192.168.50.1", second)
    }

    @Test
    fun returnsNullWhenNoReachableAddressExists() {
        assertNull(
            DirectPeerAddressResolver.selectLanIpv4(
                listOf(
                    LocalAddressCandidate("10.20.30.40", true, true, false, false),
                    LocalAddressCandidate("203.0.113.10", false, false, true, false)
                )
            )
        )
    }
}
