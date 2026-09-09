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
  WebRtcPcmMediaTransport  ───── WebRTC peer connection
                                      ↓
                              Android CLIENT app
```

The HOST fake audio source produces deterministic mono PCM16 at 16 kHz in 20 ms frames. It never opens the HOST microphone or speaker. The CLIENT owns the only physical `AudioRecord` and `AudioTrack` in the prototype.

## Module boundaries

| Location | Responsibility |
|---|---|
| `prototype-core/` | Versioned signaling model, validation, fake call control, pairing-code generator, PCM frame codec, format adapter, transport interfaces |
| `prototype-media-android/` | WebRTC peer connection and binary media data channel |
| `app/` | Existing HOST app, unchanged privileged backend stack, fake audio backend, coordinator, HOST prototype UI |
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
→ data channel opens
→ fake RX and TX sessions start
```

Outgoing flow:

```text
CLIENT outgoing_call
→ HOST Dialing
→ Active
→ HOST creates WebRTC offer
→ data channel opens
→ fake RX and TX sessions start
```

Terminal fake call states pass through `Ended` and return to `Idle`, allowing a new session.

## Media V0

The current media payload is PCM16 carried in an unordered WebRTC data channel with zero retransmissions. Each binary message has a magic value, version, format metadata, sequence number, monotonic capture timestamp, and exactly one 20 ms mono frame.

This is real bidirectional WebRTC transport but not WebRTC RTP/Opus. The deliberate PCM V0 exposes the same raw frame boundary required by `FrameworkInterceptionBackend` and the CLIENT audio device without routing HOST audio through physical hardware. Opus packetization remains isolated to the media module and can replace the current binary payload without changing call control, signaling, pairing, coordinator, backend, or UI.

The V0 data rate is suitable only for local prototype testing. There is no TURN server, congestion controller for the application payload, jitter buffer, packet-loss concealment, or production TLS configuration.

## Format adaptation

`AudioFormatAdapter` is the single PCM rate adaptation boundary. The wire format starts at 16 kHz mono PCM16/20 ms. A future PSTN backend may expose 8, 16, or 48 kHz; the coordinator adapts its frames at this boundary rather than scattering rate assumptions.

## Observability

HOST and CLIENT emit concise `SimRelayPrototype` events for signaling state, pairing success, call transitions, media state, first RX, first TX, cleanup, and failure. Session IDs and counters are allowed. Pairing credentials, tokens, identities beyond fixed prototype labels, and PCM content are not logged.

## Prototype limitations

- in-memory backend state is lost on restart
- one HOST/CLIENT pair per pairing credential
- short-lived pairing code is sent over the signaling connection, so remote deployment requires `wss://`
- WebRTC data-channel PCM is not yet Opus/RTP
- public STUN is configured, with no TURN fallback
- Android activities do not yet use foreground services
- runtime media validation requires two active Android app processes or devices
