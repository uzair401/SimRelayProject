# Prototype project state

## Working

- versioned signaling envelope and validation
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
- on-device two-call WebRTC RX/TX, teardown, and repeated-session validation on Device #1
- HOST, shared core, media, backend, and CLIENT tests, lint, and debug builds
- corrected CLIENT network permission packaging and native WebRTC teardown lock ordering
- repeated-session on-device verification of Opus codec and increasing RTP packets/bytes
- explicit fake/framework audio and fake/Android PSTN call-control selection boundary
- typed public-API PSTN call-control capability seam
- read-only rooted-HOST readiness diagnostic script

## In progress

- physical two-endpoint RTP/Opus microphone and speaker validation
- provisioned rooted-HOST PSTN execution

## Blocked

- real framework PSTN audio remains blocked by missing `CALL_AUDIO_INTERCEPTION` and unavailable rooted HOST
- representative physical bidirectional CLIENT audio needs a second Android endpoint

## Next

- repeat HOST/CLIENT RTP/Opus validation on two physical endpoints with real microphone and speaker hardware
- run the rooted-HOST readiness script after legitimate provisioning
- replace `FakeCallAudioBackend` with `FrameworkInterceptionBackend` when the provisioned rooted HOST arrives
