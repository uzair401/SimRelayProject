package com.simrelay.prototype.transport

import java.net.Inet4Address
import java.net.NetworkInterface

data class LocalAddressCandidate(
    val address: String,
    val siteLocal: Boolean,
    val pointToPoint: Boolean,
    val multicast: Boolean,
    val virtual: Boolean
)

object DirectPeerAddressResolver {
    fun findLanIpv4(): String? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { network -> runCatching { network.isUp && !network.isLoopback }.getOrDefault(false) }
            .flatMap { network ->
                network.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { address -> !address.isLoopbackAddress && !address.isLinkLocalAddress }
                    .map { address ->
                        LocalAddressCandidate(
                            address = address.hostAddress,
                            siteLocal = address.isSiteLocalAddress,
                            pointToPoint = runCatching { network.isPointToPoint }.getOrDefault(false),
                            multicast = runCatching { network.supportsMulticast() }.getOrDefault(false),
                            virtual = network.isVirtual
                        )
                    }
            }
        selectLanIpv4(candidates)
    }.getOrNull()

    fun selectLanIpv4(candidates: List<LocalAddressCandidate>): String? = candidates
        .filter { candidate -> candidate.siteLocal && !candidate.pointToPoint }
        .maxByOrNull { candidate ->
            (if (candidate.multicast) 2 else 0) +
                (if (!candidate.virtual) 1 else 0)
        }
        ?.address
}
