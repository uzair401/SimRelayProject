# SimRelay living prototype architecture

## Scope

This prototype exercises the real orchestration, signaling, pairing, call state, and bidirectional WebRTC transport while the privileged PSTN boundary is unavailable. Production operation is local device-to-device and does not require a deployed SimRelay application backend or external Internet access.

```text
Carrier / PSTN
        ↓
HOST Android app with SIM and native hotspot
  FakeCallControlBackend
  FakeCallAudioBackend
           ↓
  PrototypeSessionCoordinator
           ↓
  DirectPeerSignalingTransport ─ local hotspot/LAN socket
  WebRtcAudioMediaTransport ─── WebRTC Opus/RTP audio
                                      ↓
                            hotspot/LAN
                                      ↓
                              Android CLIENT app
```

`DevelopmentBackendSignalingTransport` and the FastAPI WebSocket router remain available as an optional development/test harness. Selecting it does not change coordinator, media, audio, call-control, or CLIENT behavior.

The HOST fake audio source produces deterministic mono PCM16 at 16 kHz in 20 ms frames. The coordinator adapts this to the WebRTC boundary. The WebRTC HOST microphone input is disabled and its physical output is muted, so fake HOST media does not use audible or captured HOST hardware. The CLIENT owns functional microphone capture and speaker playback.

## Module boundaries

| Location | Responsibility |
|---|---|
| `prototype-core/` | Versioned signaling model, direct and development signaling transports, validation, fake call control, pairing, PCM adapter/buffering, transport interfaces |
| `prototype-media-android/` | Isolated WebRTC peer connection, external PCM bridge, Opus/RTP media, and transport statistics |
| `app/` | Existing HOST app, privileged backend stack, fake audio backend, coordinator, backend selection, PSTN control seam, HOST prototype UI |
| `client-android/` | Pairing/call UI and CLIENT microphone/speaker adapter |
| `backend/` | Optional in-memory FastAPI WebSocket development/test router |

## Call lifecycle

One `PrototypeSessionCoordinator` owns one call session at a time. It rejects duplicate calls, propagates state, starts media only when the fake call reaches `Active`, opens downlink and uplink from the same audio backend, and tears both directions down on reject, hangup, signaling loss, media failure, or close.

Incoming flow:

```text
HOST simulateIncomingCall
→ Incoming
→ Ringing
→ CLIENT answer
→ Answering
→ Active
→ HOST creates WebRTC offer
→ audio RTP connection opens
→ fake RX and TX sessions start
```

Outgoing flow:

```text
CLIENT outgoing_call
→ HOST Dialing
→ Active
→ HOST creates WebRTC offer
→ audio RTP connection opens
→ fake RX and TX sessions start
```

Terminal fake call states pass through `Ended` and return to `Idle`, allowing a new session.

## Media V1

`WebRtcAudioMediaTransport` exposes 48 kHz mono PCM16 to the coordinator and carries primary voice through a WebRTC `SEND_RECV` audio transceiver. Only Opus is offered. Runtime statistics verify `audio/opus` and increasing inbound/outbound RTP packet and byte counts. No PCM DataChannel is created.

`ExternalPcmAudioBridge` fills WebRTC capture buffers from HOST/backend PCM and emits decoded remote PCM through an audio-track sink. The bridge assembles WebRTC callback chunks into 20 ms application frames and uses bounded buffering. The HOST WebRTC audio device never reads the physical microphone. Its playback device remains muted while decoded PCM is delivered to the selected uplink sink.

PeerConnection details, SDP, ICE, Opus, and RTP statistics remain inside the media module. The coordinator, call control, audio backends, signaling transports, and signaling envelope continue to depend only on `MediaTransport`.

## Signaling selection

Direct peer mode is the default. The user enables Android's native Internet-sharing hotspot, joins the CLIENT to it, and starts the HOST listener. The HOST selects a private, non-point-to-point IPv4 address when one is available, permits manual correction, binds the listener on the local interfaces, and produces a short-lived, one-time `simrelay-direct://` pairing payload. The CLIENT receives that payload out of band, connects directly, proves possession of the token, and then routes the unchanged V1 signaling envelope over the peer socket.

Development backend mode explicitly selects `DevelopmentBackendSignalingTransport`, which connects to the optional FastAPI WebSocket harness. `PrototypeSessionCoordinator` sees only `SignalingTransport` in either mode.

The default WebRTC configuration has no STUN or TURN servers. Local host ICE candidates establish media across the hotspot/LAN even when external Internet is unavailable. Android's tethering stack may independently provide CLIENT Internet through HOST mobile data; SimRelay neither implements nor depends on that routing.

If the hotspot restarts or its address changes, the HOST disconnects, refreshes the displayed local address, generates a fresh one-time payload, and the CLIENT re-pairs. A lost peer connection tears down the active call and media before reconnection.

## Format adaptation

`AudioFormatAdapter`, `PcmFrameAssembler`, and `PcmSampleBuffer` form the PCM adaptation boundary. The media boundary is 48 kHz mono PCM16/20 ms. HOST backends and CLIENT hardware may expose 8, 16, or 48 kHz; conversion, frame assembly, validation, and bounded buffering remain centralized.

## Backend selection

`PrototypeHostBackendFactory` selects `FakeCallAudioBackend` or `FrameworkInterceptionBackend`, and independently selects `FakeCallControlBackend` or `AndroidPstnCallControlBackend`. The default development configuration remains fake/fake. Selection does not alter coordinator, signaling, media, protocol, or CLIENT code.

`AndroidPstnCallControlBackend` observes public telephony state and exposes typed capability results for answer, reject, hangup, and dial. It reports missing runtime permission, unavailable API level, role/privilege failure, invalid request, or runtime failure instead of faking success.

## Observability

HOST and CLIENT emit concise `SimRelayPrototype` events for signaling state, pairing success, call transitions, media state, first RX, first TX, cleanup, and failure. Session IDs and counters are allowed. Pairing credentials, tokens, identities beyond fixed prototype labels, and PCM content are not logged.

## Prototype limitations

- direct signaling is currently limited to reachable LAN endpoints and a single peer
- each direct pairing payload is short-lived and one-time; reconnecting requires a fresh payload
- direct prototype signaling uses a token-protected LAN socket and is not an Internet-facing signaling service
- optional development-backend state is lost on restart
- development backend remote deployment requires `wss://`
- hotspot enablement and CLIENT network joining remain user-controlled through Android system UI
- arbitrary Internet peer-to-peer connectivity, STUN, and TURN are outside the local product architecture
- Android activities do not yet use foreground services
- on-device loopback validates media transport but not physical two-device acoustic behavior
- real PSTN call control and audio remain unvalidated on a provisioned HOST
