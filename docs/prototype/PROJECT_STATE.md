# Prototype project state

## Working

- versioned signaling envelope and validation
- secure random, expiring pairing-code generation
- one-time in-memory backend pairing and authorization
- fake incoming, answer, reject, hangup, outgoing, and repeated-call state transitions
- deterministic 16 kHz mono PCM16 fake RX in 20 ms frames
- fake TX frame/byte/RMS/peak metrics
- centralized call/audio/signaling/media coordinator
- PCM16 over a WebRTC DataChannel with approximately 20 ms frames
- RTP/Opus audio is not implemented yet
- separate Android CLIENT microphone and speaker adapter
- backend live smoke for pairing, incoming/answer, reject, outgoing, hangup, second session, and disconnect cleanup
- on-device two-call WebRTC RX/TX, teardown, and repeated-session validation on Device #1
- HOST, shared core, media, backend, and CLIENT tests, lint, and debug builds
- corrected CLIENT network permission packaging and native WebRTC teardown lock ordering

## In progress

- two-device physical microphone and speaker smoke run
- Opus/RTP media replacement after PCM transport validation

## Blocked

- real framework PSTN audio remains blocked by missing `CALL_AUDIO_INTERCEPTION` and unavailable rooted HOST
- representative physical bidirectional CLIENT audio needs a second Android endpoint

## Next

- run HOST/CLIENT on two endpoints and verify incoming, reject, outgoing, disconnect, and second-call flows
- replace data-channel PCM with isolated Opus media framing without changing coordinator or call control
- replace `FakeCallAudioBackend` with `FrameworkInterceptionBackend` when the provisioned rooted HOST arrives
