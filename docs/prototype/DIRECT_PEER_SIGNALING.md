# Direct peer signaling

## Purpose

Production SimRelay does not depend on a deployed SimRelay application backend or external Internet. `DirectPeerSignalingTransport` connects one HOST and CLIENT directly across the HOST's native hotspot or another shared local LAN while preserving the existing V1 signaling envelope and `SignalingTransport` contract.

The FastAPI/WebSocket implementation remains available only as `DevelopmentBackendSignalingTransport` for automated development and diagnostics.

## Prototype flow

1. The user enables the HOST's native Internet-sharing hotspot and joins the CLIENT to it.
2. HOST selects hotspot/LAN mode, refreshes or confirms the advertised private address and port, and starts listening.
3. HOST displays a short-lived `simrelay-direct://` payload containing address, port, expiry, and a random one-time token.
4. The payload is copied to the CLIENT out of band.
5. CLIENT connects directly and proves possession of the token.
6. HOST and CLIENT exchange the unchanged call-control, SDP, and ICE messages over the direct socket.
7. Voice travels through the existing WebRTC audio transceiver as Opus over RTP/RTCP.

The token is not logged. It expires after five minutes and cannot pair a second connection after successful use. A reconnect uses a newly generated payload.

## Security and network scope

WebRTC DTLS-SRTP provides media confidentiality and integrity. The direct signaling socket is a token-protected hotspot/LAN prototype and must not be exposed as a public Internet service.

The default WebRTC path uses local host ICE candidates and configures no STUN or TURN servers. SimRelay call control and media continue working when the HOST has no mobile data or external Internet. Android may separately share HOST mobile data with the CLIENT through normal tethering when it is available.

The app does not attempt to enable or configure tethering. If the hotspot restarts or its address changes, the HOST refreshes the local address, creates a fresh payload, and the CLIENT re-pairs. Remote Internet peer-to-peer connectivity is outside this architecture.

No custom encryption, PKI, account service, discovery service, or deployed signaling service is introduced by this checkpoint.
