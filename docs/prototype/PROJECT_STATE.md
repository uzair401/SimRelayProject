# Prototype project state

## Working

- versioned signaling envelope and validation
- default backendless HOST-hotspot/local-LAN signaling with an out-of-band, expiring one-time pairing payload
- optional FastAPI/WebSocket development signaling harness
- private LAN address selection with manual refresh/correction after hotspot or Wi-Fi changes
- secure random, expiring pairing-code generation
- one-time in-memory backend pairing and authorization
- fake incoming, answer, reject, hangup, outgoing, and repeated-call state transitions
- deterministic 16 kHz mono PCM16 fake RX in 20 ms frames
- fake TX frame/byte/RMS/peak metrics
- centralized call/audio/signaling/media coordinator
- bidirectional WebRTC audio RTP with negotiated Opus
- external PCM16 bridge at 48 kHz mono with approximately 20 ms application frames
- centralized 8/16/48 kHz PCM adaptation and bounded frame buffering
- separate Android CLIENT microphone and speaker adapter
- backend live smoke for pairing, incoming/answer, reject, outgoing, hangup, second session, and disconnect cleanup
- separate HOST/CLIENT emulator validation for incoming, answer, reject, outgoing, RTP/Opus media, abrupt disconnect, re-pairing, and second-call cleanup
- backendless direct-signaling instrumentation for incoming, outgoing, reject, hangup, disconnect, reconnect, second call, and Opus/RTP
- Internet-independent WebRTC validation using local ICE candidates with no STUN or TURN servers
- on-device two-call WebRTC RX/TX, teardown, and repeated-session validation on Device #1
- HOST, shared core, media, backend, and CLIENT tests, lint, and debug builds
- corrected CLIENT network permission packaging and native WebRTC teardown lock ordering
- repeated-session on-device verification of Opus codec and increasing RTP packets/bytes
- explicit fake/framework audio and fake/Android PSTN call-control selection boundary
- typed public-API PSTN call-control capability seam
- read-only rooted-HOST readiness diagnostic script
- `Cannot create AudioTrack` classified as audio-route unavailable without retrying the sample-rate ladder

## In progress

- physical two-endpoint RTP/Opus microphone and speaker validation
- provisioned rooted-HOST PSTN execution

## Blocked

- real framework PSTN audio remains blocked by missing `CALL_AUDIO_INTERCEPTION` and unavailable rooted HOST
- representative physical bidirectional CLIENT audio needs a second Android endpoint

## Next

- repeat HOST/CLIENT RTP/Opus validation on two physical endpoints with real microphone and speaker hardware
- validate the same flow with the CLIENT joined to the provisioned HOST's native hotspot and external Internet disabled
- run the rooted-HOST readiness script after legitimate provisioning
- replace `FakeCallAudioBackend` with `FrameworkInterceptionBackend` when the provisioned rooted HOST arrives
