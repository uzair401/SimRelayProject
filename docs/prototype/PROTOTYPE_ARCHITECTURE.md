# SimRelay living prototype architecture

## Scope

This prototype exercises the real orchestration, signaling, pairing, call state, and bidirectional WebRTC transport while the privileged PSTN boundary is unavailable.

```text
HOST Android app
  FakeCallControlBackend
  FakeCallAudioBackend
           ↓
  PrototypeSessionCoordinator
           ↓
  OkHttpSignalingTransport ───── FastAPI WebSocket backend
  WebRtcAudioMediaTransport ─── WebRTC Opus/RTP audio
                                      ↓
                              Android CLIENT app
```

The HOST fake audio source produces deterministic mono PCM16 at 16 kHz in 20 ms frames. The coordinator adapts this to the WebRTC boundary. The WebRTC HOST microphone input is disabled and its physical output is muted, so fake HOST media does not use audible or captured HOST hardware. The CLIENT owns functional microphone capture and speaker playback.

## Module boundaries

| Location | Responsibility |
|---|---|
| `prototype-core/` | Versioned signaling model, validation, fake call control, pairing-code generator, PCM adapter/buffering, transport interfaces |
| `prototype-media-android/` | Isolated WebRTC peer connection, external PCM bridge, Opus/RTP media, and transport statistics |
| `app/` | Existing HOST app, privileged backend stack, fake audio backend, coordinator, backend selection, PSTN control seam, HOST prototype UI |
| `client-android/` | Pairing/call UI and CLIENT microphone/speaker adapter |
| `backend/` | In-memory FastAPI WebSocket pairing and signaling router |

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

PeerConnection details, SDP, ICE, Opus, and RTP statistics remain inside the media module. The coordinator, call control, audio backends, backend service, and signaling envelope continue to depend only on `MediaTransport`.

## Format adaptation

`AudioFormatAdapter`, `PcmFrameAssembler`, and `PcmSampleBuffer` form the PCM adaptation boundary. The media boundary is 48 kHz mono PCM16/20 ms. HOST backends and CLIENT hardware may expose 8, 16, or 48 kHz; conversion, frame assembly, validation, and bounded buffering remain centralized.

## Backend selection

`PrototypeHostBackendFactory` selects `FakeCallAudioBackend` or `FrameworkInterceptionBackend`, and independently selects `FakeCallControlBackend` or `AndroidPstnCallControlBackend`. The default development configuration remains fake/fake. Selection does not alter coordinator, signaling, media, protocol, or CLIENT code.

`AndroidPstnCallControlBackend` observes public telephony state and exposes typed capability results for answer, reject, hangup, and dial. It reports missing runtime permission, unavailable API level, role/privilege failure, invalid request, or runtime failure instead of faking success.

## Observability

HOST and CLIENT emit concise `SimRelayPrototype` events for signaling state, pairing success, call transitions, media state, first RX, first TX, cleanup, and failure. Session IDs and counters are allowed. Pairing credentials, tokens, identities beyond fixed prototype labels, and PCM content are not logged.

## Prototype limitations

- in-memory backend state is lost on restart
- one HOST/CLIENT pair per pairing credential
- short-lived pairing code is sent over the signaling connection, so remote deployment requires `wss://`
- public STUN is configured with no TURN fallback, so restrictive NAT traversal is not covered
- Android activities do not yet use foreground services
- on-device loopback validates media transport but not physical two-device acoustic behavior
- real PSTN call control and audio remain unvalidated on a provisioned HOST
