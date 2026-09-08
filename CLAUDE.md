# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Claude Branch Ownership

Claude's SimRelay work is restricted to:

`experiment/non-root-provisioning`

At the beginning of every session:

```bash
git branch --show-current
```

If the output is not `experiment/non-root-provisioning`, switch to it (`git checkout experiment/non-root-provisioning`) before making any changes. Do not commit or push to `main` or any other branch.

## What this project is

SIM Relay M0 is the feasibility harness for a larger (not-yet-built) project: making a rooted Android phone's cellular call audio (PSTN downlink/uplink) programmatically extractable and injectable, as a precursor to relaying calls to a remote iOS/Android client. M0 answers exactly one question — can a real device simultaneously capture clean downlink PCM and inject uplink PCM during a live call, with the host speaker/mic excluded — and nothing more. It is a diagnostic Android app (`com.simrelay.m0`), not a product.

Full context lives in root-level docs; read them before making architectural changes:
- `AGENTS.md` (identical to `SIM_RELAY_M0_CODEX_INSTRUCTIONS.md`) — the authoritative agent operating rules for this repo (scope boundaries, code-quality rules, git rules, phase definitions). Treat this as binding, not background reading.
- `sim-relay-architecture.md` — full system design (host/client roles, why iOS can't be HOST, transport/security).
- `sim-relay-stack-decisions.md` — stack decisions for the phases *after* M0 (KMP, Opus, Noise protocol, tinymix/Magisk). Not yet implemented here.
- `PHASE_0A_REVIEW.md` — independent review of the Phase 0A implementation; several findings (capability vs. session-readiness separation, write-all semantics, etc.) are already reflected in the current code.
- `docs/provisioning/PHASE_0B2_REPORT.md` and `PHASE_0B2_DEVICE1.md` — real-device provisioning findings for the current target handset (Samsung SM-A225F). Read-only ADB characterization only; no root/privileged provisioning has been applied yet.

## Build, test, lint

Standard Gradle Android project, single `:app` module.

```bash
./gradlew assembleDebug          # build
./gradlew testDebugUnitTest      # JVM unit tests (pure Kotlin logic — this is where nearly all tests live)
./gradlew lintDebug              # lint
```

Run a single test class or method:

```bash
./gradlew testDebugUnitTest --tests "com.simrelay.m0.audio.BackendLogicTest"
./gradlew testDebugUnitTest --tests "com.simrelay.m0.pcm.PcmMetricsTest.someMethodName"
```

Before considering any change done, run unit tests, lint, and assembleDebug, per `AGENTS.md` §14/§25 — then `git status` / `git diff --check`.

Environment: `minSdk 24`, `compileSdk 36`, `targetSdk 36`, Kotlin, JDK 17, Jetpack Compose (Material3). No native/NDK code.

## Architecture

### The root/non-root migration boundary is the central design constraint

This is not a normal Android app — it's built so that a future non-rooted or OEM-privileged host backend can replace the rooted one without touching anything else. Concretely: **UI, the session state machine, diagnostics, PCM utilities, and call-state observation must never know or care whether the device is rooted or what backend is selected.** Only code inside `audio/framework`, `audio/legacy`, `audio/vendor` may know about privilege level, reflection, or vendor mixer behavior. When adding a feature, ask which side of this boundary it belongs on before writing it.

### Backend abstraction (`app/src/main/java/com/simrelay/m0/audio/`)

`CallAudioBackend` is the core interface (`probe()`, `readiness()`, `openDownlink()`, `openUplink()`, `close()`). Three implementations exist:

- `framework/FrameworkInterceptionBackend` — the real, primary target. Wraps the AOSP system APIs `isPstnCallAudioInterceptable()`, `getCallDownlinkExtractionAudioRecord()`, `getCallUplinkInjectionAudioTrack()` (API 33+, gated behind `CALL_AUDIO_INTERCEPTION`). All reflection is isolated inside `framework/FrameworkAudioApiBridge` — nothing else in the app performs reflection on `AudioManager`.
- `legacy/LegacyPrivilegedBackend` and `vendor/VendorAudioBackend` — intentional placeholders. They must return explicit unavailable/`NotImplemented` capability states, never fake success, and must never run root/`su`/tinymix/Magisk commands. Do not implement these for real in M0 — see `AGENTS.md` §6.2/§6.3/§18 for what they'll eventually become and why they're deferred.
- `CallAudioBackendFactory` / `BackendSelectionPolicy` select a backend from device capability (currently: API 33+ and framework state `Supported`/`PermissionMissing` → `FrameworkInterceptionBackend`; otherwise `UnsupportedBackend`). Selection must never silently fall back to an unimplemented backend — always surface *why* a backend was rejected.

**Capability is never a boolean.** `CapabilityState` is a large enum (`Supported`, `ApiNotPresent`, `ApiUnavailableOrBlocked`, `PermissionMissing`, `CallNotActive`, `PstnInterceptionUnsupported`, `NotImplemented`, ...) because the UI and device-qualification records need to distinguish "API doesn't exist" from "API exists but reflection is blocked" from "permission missing" from "call not active." `CapabilityFailureMapper` maps caught exceptions to these states — keep exception mapping close to the operation that produced it rather than inferring meaning globally from exception class alone (this was a review finding, see `PHASE_0A_REVIEW.md` M7).

**Backend capability and session readiness are deliberately separate concepts** (`CallAudioCapability` vs `CallAudioReadiness`, evaluated by `diagnostics/CallSessionReadinessEvaluator`). Capability = is this backend supported on this OS/device at all. Readiness = is it legal to open a session *right now* (call state, audio mode). An idle-but-compatible device must still be selectable as the candidate backend — this was Phase 0A review finding H1 and is load-bearing; don't recouple these.

### Session lifecycle (`presentation/M0ViewModel.kt`)

Single-owner state machine (`SessionState`: `Idle → Probing → Ready → Capturing/Injecting/FullDuplex → Stopping/Error`), transitions validated by `SessionTransitionPolicy`, guarded by one `lock`. All audio actions (`runCapabilityProbe`, `startDownlinkCapture`, `startInjection`, `startFullDuplex`, `stopAll`, `exportDiagnostics`) go through this ViewModel and must remain idempotent (`stopAll` in particular). Background work runs on `SafeBackgroundExecutor` so a failure in one audio operation can't silently kill the app — failures always route through `fail()` / `containBackgroundFailure()` into a typed `CapabilityState`, never a raw crash.

`PcmConfig.Candidates` defines the sample-rate probe ladder (48k/16k/8k mono PCM16); `openDownlink`/`openUplink` walk it and record which one actually works rather than assuming a rate from network technology — do not hardcode a sample rate.

### Diagnostics (`diagnostics/`)

Every capability probe produces a `DiagnosticReport` (device/permission/audio-device snapshots, capability + readiness state, monotonic-timestamped `DiagnosticEvent`s) exported per-run by `DiagnosticExporter` into app-owned storage, plus a `DeviceQualificationProfile` (`DeviceQualificationProfileFactory`) — a structured, machine-readable per-device qualification record (`Observed`/`Unknown`/`NotTested` fields) that is the actual deliverable of M0's device-qualification effort. `SensitiveDiagnosticRedactor` scrubs event text — do not log phone numbers, SMS, or contacts (see `AGENTS.md` §22).

### PCM utilities (`pcm/`)

Pure Kotlin, deliberately independent of Android framework classes so they're trivially unit-testable (`PcmToneGenerator`, `PcmMetrics`/`StreamingPcmMetrics`, `WavWriter`). Keep it that way — don't let `AudioRecord`/`AudioTrack` types leak into this package.

## Working within this repo

- **No code comments, no TODOs.** Use clear naming and small functions instead (`AGENTS.md` §24 — this is an explicit, repeatedly-enforced project rule, not a general style preference).
- **Never implement root/Magisk/tinymix/vendor mixer behavior, SMS, BLE, WebRTC, networking, or telephony call-control in this app.** Those belong to later phases described in `sim-relay-stack-decisions.md` and are explicitly out of scope for M0 (`AGENTS.md` §16–§21, §23). Do not add dependencies from that document's rejection list without a concrete current requirement.
- **Do not change `minSdk` from 24.** Gate any version-dependent API at the call site, close to its platform implementation.
- Prefer sealed types / explicit enums for result and capability domains (`BackendResult<T>`, `CapabilityState`) over booleans or exceptions crossing module boundaries.
- Release `AudioRecord`/`AudioTrack` deterministically; sessions are `AutoCloseable` and close paths must be idempotent.
- Git: never push, force-push, rewrite history, or change remotes/config unless explicitly asked. Don't commit unless explicitly asked.
- Device-modifying actions (ADB provisioning, bootloader/root operations, flashing) require explicit user approval per call — see `tools/provisioning/` and the Phase 0B reports for what has and hasn't been done on the current target device (Samsung SM-A225F: read-only characterization only, no privileged provisioning yet).

## Provisioning tooling (`tools/`, non-destructive only)

- `tools/phase0b_device_probe.sh` — read-only device characterization over ADB (`--serial`, `--package`).
- `tools/provisioning/adb_target.sh`, `verify_installation.sh`, `verify_privilege.sh` — verify actual `PackageManager` install/permission state on a connected device rather than inferring success.
- `tools/provisioning/build_privapp_module.sh` — builds (does not install) a systemless priv-app Magisk module layout for the least-privilege `CALL_AUDIO_INTERCEPTION` allowlist design described in `docs/provisioning/PHASE_0B2_REPORT.md`.

These scripts only read device/package state or produce local artifacts; none of them flash, root, or grant privileged permissions. Installing/applying privileged provisioning is a separate, explicitly-approved step — do not chain these tools into a destructive sequence on your own initiative.
