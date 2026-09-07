# SIM Relay M0 — Phase 0A Independent Review

**Reviewer:** ChatGPT / GPT-5.6 Sol  
**Date:** 2026-09-07  
**Scope:** `SimRelayM0-phase0a.zip`  
**Decision:** **Needs a small correction pass before Phase 0A is frozen. No Phase 0B yet.**

## Executive Result

The Phase 0A implementation is structurally good. The root/non-root boundary is preserved, framework call interception is isolated, the project remains API 24+, no vendor/root/transport behavior leaked into the application, and PCM/WAV utilities are clean.

There is no architectural showstopper in the submitted code.

However, Phase 0A should not yet be considered frozen because several behaviors would produce misleading device-qualification results or weak audio behavior on the real M0 handset.

## HIGH

### H1 — Backend capability is incorrectly coupled to an active call

Files:

- `app/src/main/java/com/simrelay/m0/audio/framework/FrameworkInterceptionBackend.kt`
- `app/src/main/java/com/simrelay/m0/audio/CallAudioBackendFactory.kt`

Current behavior:

`FrameworkInterceptionBackend.probe()` returns `CallNotActive` when `AudioManager.mode != MODE_IN_CALL` before calling `isPstnCallAudioInterceptable()`.

`CallAudioBackendFactory` then selects `UnsupportedBackend` unless the framework probe returns exactly `Supported`.

This means an idle but fully compatible device is classified as unsupported.

That is wrong for our scale-first device qualification architecture.

Required change:

Separate two concepts:

- backend/device capability
- current session readiness

Framework capability should determine:

- OS/API support
- framework method accessibility
- `CALL_AUDIO_INTERCEPTION` permission state
- `isPstnCallAudioInterceptable()`

Session readiness should determine:

- current call/audio mode
- whether opening RX/TX is currently legal

The framework backend should remain the selected/candidate backend when it is supported but no call is active.

Opening a downlink/uplink session should enforce the active-call precondition.

### H2 — The hard-coded `MODE_IN_CALL` requirement is narrower than AOSP

File:

- `app/src/main/java/com/simrelay/m0/audio/framework/FrameworkInterceptionBackend.kt`

Current code only accepts:

`AudioManager.MODE_IN_CALL`

Current AOSP call-redirection code supports multiple valid call modes and determines the redirect mode internally.

Do not pre-reject a framework request solely because the mode is not `MODE_IN_CALL`.

For the PSTN backend, either:

- model supported PSTN modes correctly, including system call-redirection modes, or
- preferably let the framework call determine legality and map its `IllegalStateException`.

Record the current mode diagnostically instead of duplicating framework policy incorrectly.

### H3 — Uplink partial writes are not handled correctly

Files:

- `app/src/main/java/com/simrelay/m0/audio/framework/FrameworkInterceptionBackend.kt`
- `app/src/main/java/com/simrelay/m0/presentation/M0ViewModel.kt`

`AudioTrack.write()` can return a positive count smaller than the requested sample count.

The current code treats every positive result as if the whole buffer was consumed. The next loop starts again at the beginning of the tone buffer.

This can drop samples, create discontinuities, corrupt timing, and make latency/audio-quality measurements unreliable.

Required change:

Implement write-all semantics.

Either change the session API to support offset/count or have the framework uplink session internally continue writing until all requested samples are consumed, stopped, or an error occurs.

### H4 — Hidden/System API access failure is reported as API absence

File:

- `app/src/main/java/com/simrelay/m0/audio/framework/FrameworkAudioApiBridge.kt`

The APIs are `@SystemApi`/`@TestApi`, not ordinary public SDK APIs.

On Android 9+, non-SDK access enforcement can cause reflective lookup/access to fail even when the method physically exists in the OS.

Current `detectApiPresence()` converts reflection lookup failure into `false`, and the rest of the application reports `ApiNotPresent`.

For M0 this distinction is critical:

- method absent from vendor build
- method present but hidden-API access blocked
- method accessible but permission missing

are three different engineering outcomes.

Required change:

Do not claim `ApiNotPresent` when reflection alone cannot distinguish absence from access enforcement.

Introduce a state such as:

`ApiUnavailableOrBlocked`

or improve the diagnostic model to preserve this ambiguity.

Phase 0B provisioning must explicitly verify hidden/System API accessibility.

## MEDIUM

### M1 — UI hard-codes `RECORD_AUDIO` as a requirement for framework downlink

File:

- `app/src/main/java/com/simrelay/m0/MainActivity.kt`

The UI requires `RECORD_AUDIO` before starting downlink capture or full duplex.

The framework call-redirection path is primarily authorized by `CALL_AUDIO_INTERCEPTION`; current AOSP treats `VOICE_DOWNLINK` specially.

Do not hard-code per-backend permission requirements in the UI.

Move action requirements behind the selected backend/capability model so future framework, role-based, legacy, and vendor backends can declare different requirements.

### M2 — Audio frame sizing is unsuitable for latency measurements

File:

- `app/src/main/java/com/simrelay/m0/presentation/M0ViewModel.kt`

Downlink uses a fixed `ShortArray(1024)`.

That represents approximately:

- 21.3 ms at 48 kHz
- 64 ms at 16 kHz
- 128 ms at 8 kHz

Use a duration-based frame size, preferably 20 ms initially:

`sampleRateHz / 50`

The same principle should apply to uplink tone chunks.

### M3 — Uplink uses one-second write chunks

File:

- `app/src/main/java/com/simrelay/m0/presentation/M0ViewModel.kt`

The tone generator creates a 1000 ms buffer and repeatedly performs blocking writes.

This makes stop responsiveness and latency behavior unnecessarily coarse.

Use small deterministic frames, preferably around 20 ms.

### M4 — Silence detection is exact-zero only

File:

- `app/src/main/java/com/simrelay/m0/pcm/PcmMetrics.kt`

`isSilent = peak == 0` is mathematically valid but not useful for real telephony paths, which can contain low-level DSP/quantization noise.

Keep exact-zero information if useful, but also add a configurable RMS/peak silence threshold for device qualification.

Do not tune the final threshold until real handset measurements exist.

### M5 — Diagnostic logs need timestamps and live state changes

Files:

- `M0ViewModel.kt`
- `DiagnosticReport.kt`

Current logs are plain strings without monotonic timestamps.

M0 needs to diagnose:

- call state transition
- audio mode transition
- probe time
- session creation
- start time
- first RX sample
- first TX write
- stop/release
- failures

Add monotonic elapsed-time timestamps to diagnostic events.

Do not use wall-clock time for latency calculations.

### M6 — Repeated test actions overwrite artifacts

File:

- `M0ViewModel.kt`

`rx.wav` is reused inside the probe's existing run directory.

Multiple capture attempts under one probe overwrite the prior capture.

Each capture/full-duplex attempt should receive a unique attempt/run ID or numbered artifact.

This will matter immediately when comparing sample-rate attempts and repeated calls.

### M7 — Error classification is too broad

File:

- `CapabilityFailureMapper.kt`

Examples:

- every `IllegalStateException` becomes `CallNotActive`
- non-PSTN `UnsupportedOperationException` becomes `AudioRouteUnavailable`

Those exceptions can also represent released resources, invalid track state, unsupported format, or other runtime failures.

Map exceptions in the layer that knows the operation being attempted instead of globally inferring meaning only from exception class.

### M8 — Fallback placeholders use misleading states

Files:

- `LegacyPrivilegedBackend.kt`
- `VendorAudioBackend.kt`

The backends are explicitly not implemented in Phase 0A but report `PrivilegeMissing` or `AudioRouteUnavailable`.

Add a clear `NotImplemented` / `UnavailableInPhase` state, or otherwise make the machine-readable state match the message.

### M9 — Phase 0A launch smoke test was not demonstrated

The implementation report proves:

- unit tests
- lint
- build

but does not prove the AGENTS.md requirement that the APK launches on a normal Android device/emulator without privileged permissions.

This is not a code defect, but Phase 0A Definition of Done is technically incomplete until a launch/capability-probe smoke test is performed.

An ordinary phone or API 36 emulator is sufficient for this test; PSTN functionality is not required.

### M10 — Unit tests do not cover the most important new failure paths

Add tests around pure/testable logic for:

- capability vs session-readiness separation
- API < 33
- API access unavailable/blocked state
- partial uplink write handling
- transition behavior after start failure
- repeated stop/idempotent stop logic where it can be isolated
- error mapping by operation context

Do not add a large mocking framework solely for these tests.

## LOW

### L1 — Framework report is selected by list position

`M0ViewModel.kt` uses `backendReports.first()`.

Look up by `AudioBackendId.FrameworkInterception` instead.

### L2 — File I/O occurs while holding the ViewModel lock

Some exporter calls execute inside `synchronized(lock)`.

Move filesystem work outside the lock. Keep lock ownership limited to state/reference mutations.

### L3 — Template comments/TODOs remain

The handwritten implementation is clean, but Android Studio template comments remain in theme/build/XML files.

This is not functionally important, but project style says no comments/TODO comments.

Clean them when convenient.

### L4 — Git repository has no commit yet

The submitted repository has no commits.

After the correction pass and verification, create the first accepted baseline commit before Phase 0B.

Do not start device experimentation with an uncommitted baseline.

## GOOD

### G1 — Root boundary is clean

No `su`, Magisk, `tinymix`, SELinux mutation, vendor mixer, BLE, WebRTC, SMS, backend networking, or hardware dependency exists in the Phase 0A application code.

This matches the root-now/non-root-later architecture.

### G2 — Framework API isolation is good

Reflection is confined to:

`FrameworkAudioApiBridge.kt`

That is exactly the kind of narrow replaceable boundary we want.

### G3 — API 24 application floor is preserved

Platform-dependent behavior is reasonably isolated and the project compiles with:

- `minSdk 24`
- `compileSdk 36`
- `targetSdk 36`
- Java 17

### G4 — Session cleanup design is directionally good

Audio resources are encapsulated by `DownlinkSession` / `UplinkSession`, close operations are idempotent, and the ViewModel owns the session lifecycle.

### G5 — PCM/WAV foundations are clean

Tone generation, metrics, and WAV serialization are separated from Android framework classes and unit tested.

### G6 — No fake fallback success

Legacy/vendor placeholders fail explicitly rather than pretending functionality exists.

## Current Decision

**Do not start Phase 0B yet.**

Perform one Phase 0A correction pass addressing H1-H4 and the practical medium items above.

Then run:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
git diff --check
```

Then perform one ordinary-device/emulator launch smoke test.

After that:

1. review again
2. create the accepted Phase 0A Git baseline
3. update shared project state for Codex, Claude, and ChatGPT
4. begin Phase 0B when the target handset is available

## Important Platform Validation

Current AOSP confirms:

- `isPstnCallAudioInterceptable()` is a dedicated system API protected by `CALL_AUDIO_INTERCEPTION`.
- `getCallDownlinkExtractionAudioRecord(AudioFormat)` and `getCallUplinkInjectionAudioTrack(AudioFormat)` are dedicated system APIs for call downlink extraction and uplink injection.
- Current AOSP's call-redirection logic supports more call modes than only `MODE_IN_CALL`.
- Current Android 16 QPR2 defines `CALL_AUDIO_INTERCEPTION` with a role protection path in addition to privileged/signature access.
- Android's hidden/non-SDK enforcement can restrict reflection; system-image/platform exemptions are separate from privileged-permission grants.

These validate the framework-first direction, while also making capability/access diagnostics important.
