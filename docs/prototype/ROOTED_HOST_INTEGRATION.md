# Rooted HOST integration contract

## Replacement boundary

The living prototype uses:

```text
PrototypeSessionCoordinator
        ↓
CallAudioBackend
        ↓
FakeCallAudioBackend
```

When the provisioned PSTN HOST is available, construction changes to:

```text
PrototypeSessionCoordinator
        ↓
CallAudioBackend
        ↓
FrameworkInterceptionBackend
```

No signaling, protocol, pairing, media transport, Android CLIENT, or coordinator API changes are intended.

## Required audio contract

The coordinator consumes only the existing `CallAudioBackend` contract:

- capability and readiness
- PCM format metadata
- downlink session open/start/read/stop/close
- uplink session open/start/write/stop/close
- typed `BackendResult` failures

HOST downlink is always a PCM source. HOST uplink is always a PCM sink. Full duplex uses one selected backend for both directions.

The coordinator uses 20 ms frames and handles partial uplink writes. It stops sessions before closing them and waits for the downlink loop before release.

## Call-control replacement

The prototype separately depends on `CallControlBackend`:

```text
FakeCallControlBackend
        ↓ replace
AndroidPstnCallControlBackend
```

The real implementation must preserve session identity and report incoming, ringing, answering, active, dialing, rejected, ended, and failure transitions. It must not expose Telecom implementation details to signaling or media.

## Integration sequence

1. Provision `CALL_AUDIO_INTERCEPTION` externally and verify the actual PackageManager grant.
2. Run `tools/prototype/rooted_host_readiness.sh` and preserve its timestamped artifact directory.
3. Confirm the idle framework capability probe progresses beyond `PermissionMissing` without treating idle readiness as unsupported capability.
4. Select `FrameworkInterceptionBackend` through `PrototypeHostConfiguration`; do not change coordinator or transport code.
5. Start with observed real PSTN call state and verify `isPstnCallAudioInterceptable()` during the controlled call.
6. Run downlink, uplink, and full-duplex tests independently before using the end-to-end CLIENT path.
7. Enable the HOST's native Internet-sharing hotspot, join the CLIENT, and connect the default local direct signaling and WebRTC Opus/RTP path after framework RX/TX succeeds. The FastAPI signaling harness and external Internet are optional and not required for production operation.
8. Select `AndroidPstnCallControlBackend` only when its reported permissions/role capabilities are genuinely available.
9. Preserve the existing fallback order: framework, legacy privileged, vendor, unsupported.

Root, Magisk, shell, manufacturer, chipset, and provisioning behavior remain external to generic orchestration.

SimRelay does not control tethering or implement Internet routing. Android's native hotspot stack may share HOST mobile data with the CLIENT independently of local SimRelay signaling and media.

## Real-device acceptance

The replacement is successful only when a real carrier call demonstrates downlink capture, uplink injection, full duplex, microphone exclusion, speaker isolation, feedback classification, clean teardown, and a second call without stale routing.

## Deterministic rooted-HOST plan

### Test 1 — PSTN downlink

Establish a carrier call, have the remote caller speak an agreed deterministic phrase and numbers, start framework downlink capture, and verify non-silent PCM with the negotiated sample rate, frame size, RMS, peak, first-RX latency, duration, and artifact path.

### Test 2 — Uplink injection

Inject the deterministic 1 kHz tone through framework uplink injection. Verify at the far end and record first-TX latency, sample rate, frame size, written frames/bytes, partial writes, and failures.

### Test 3 — Full duplex

Run downlink extraction and deterministic uplink injection simultaneously. Verify both RTP directions continue, Opus remains negotiated, and PCM/RTP counters increase without crash, deadlock, or session corruption.

### Test 4 — HOST microphone isolation

Inject digital silence, then tone, while producing loud speech/noise near the HOST microphone. Classify physical microphone exclusion as `Pass`, `Fail`, or `Inconclusive`.

### Test 5 — HOST speaker isolation

Capture downlink while checking the physical HOST earpiece and speaker. Classify the result as `Silent`, `Audible`, `PartiallyAudible`, or `Unknown` without applying vendor routing changes.

### Test 6 — Feedback

Use deterministic uplink tone evidence to classify its presence in downlink capture as `NoRecapture`, `MinorLeakage`, `StrongRecapture`, or `Inconclusive`.

### Test 7 — Double talk

Have both remote endpoints speak simultaneously while RX and TX run. Record intelligibility, clipping, discontinuities, overruns, underruns, and dropped frames.

### Test 8 — Hangup

End the call during active media. Verify audio sessions stop before release, media disconnects, teardown time is recorded, and call/audio state returns to idle.

### Test 9 — Second call

Place a second carrier call without restarting either application. Repeat short RX/TX or full duplex and verify no stale callback, route, session, or permission state.

### Test 10 — Screen off and background

After the initial smoke proof, repeat a short active session with the HOST screen off and application backgrounded. Record process/lifecycle behavior without adding a foreground service until evidence requires it.

### Test 11 — Long call

After all smoke tests pass, run a 30+ minute call and record first-RX, first-TX, media-start latency, RTP packets/bytes, underruns, overruns, dropped frames, session duration, teardown time, temperature, and failures.

## Current physical blockers

- `CALL_AUDIO_INTERCEPTION` must be genuinely granted to the installed HOST package.
- Framework PSTN interceptability and session readiness require the appropriately provisioned physical HOST and a real carrier call.
- Public call-control actions require the runtime permissions and, where Android enforces it, the appropriate dialer/telecom role or privilege.
- Physical microphone exclusion, speaker isolation, feedback, double talk, screen-off behavior, and long-call stability cannot be inferred from fake audio or one-device RTP loopback.
