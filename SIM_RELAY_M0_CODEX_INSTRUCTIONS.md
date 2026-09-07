# SIM Relay M0 — Codex Agent Instructions

## 1. Role

You are the implementation agent for the `SimRelayM0` Android project.

Work as a senior Android systems engineer. Implement the M0 feasibility harness cleanly, incrementally, and with a migration path from rooted/system-privileged Android hosts to non-root deployment.

Root is an initial development/provisioning mechanism, not the product architecture. Hardware accessories are out of scope.

Do not add code comments. Use clear naming, small functions, types, tests, and documentation instead.

Do not push changes, rewrite Git history, modify Git configuration, flash a device, unlock a bootloader, root a phone, modify SELinux, change system partitions, or run destructive ADB/Fastboot commands unless the user explicitly asks. Normal repository edits, Gradle builds, unit tests, lint, and non-destructive local commands are allowed.

---

## 2. Product Context

SIM Relay has two logical devices:

- `HOST`: Android phone containing the active cellular SIM. It performs all PSTN/SMS/radio work.
- `CLIENT`: iPhone or Android device used by the user as the remote endpoint.

Long-term experience:

- incoming cellular calls on HOST ring on CLIENT
- CLIENT can answer/reject/hang up
- CLIENT microphone audio reaches the cellular caller
- cellular caller audio reaches CLIENT
- outgoing calls can originate from CLIENT through HOST
- SMS/OTP can be relayed
- HOST should remain effectively silent and idle-looking

The current project is only M0. Do not implement the full relay product yet.

---

## 3. Product Principles

Every implementation decision must satisfy these constraints:

1. Scale matters.
2. Hardware accessories are out of scope.
3. Initial development may use root/system privilege.
4. Preserve a migration path to non-root hosts.
5. Root-specific logic must remain isolated behind replaceable interfaces.
6. Protocol, media transport, call state, pairing, backend services, and client applications must never depend directly on root.
7. Do not claim generic Android compatibility.
8. Device/firmware compatibility must be measurable and explicit.
9. Framework-supported Android paths are preferred over vendor mixer hacks.
10. Vendor/HAL/mixer paths are fallback mechanisms, not the primary architecture.
11. M0 is a feasibility harness, not a polished application.

---

## 4. Current Local Development Environment

Expected project:

```text
Project: SimRelayM0
Package: com.simrelay.m0
Language: Kotlin
Gradle: Kotlin DSL
minSdk: 24
compileSdk: 36
targetSdk: 36
```

Expected Android SDK:

```text
ANDROID_HOME=/home/uzair401/Android/Sdk
ANDROID_SDK_ROOT=/home/uzair401/Android/Sdk
Platform Tools: 37.0.1
Build Tools: 36.0.0
Android Platform: API 36
JDK: 17
```

Do not change `minSdk` from 24.

API 24 means the application shell must install and run on Android 7.0+, even though individual call-audio backends may require newer Android versions or privileged access.

All version-dependent APIs must be runtime-gated.

---

## 5. M0 Objective

M0 answers one question:

> Can a real Android host simultaneously extract clean cellular-call downlink PCM and digitally inject PCM into the cellular-call uplink, while excluding the host physical speaker/microphone path and maintaining usable full-duplex behavior?

M0 is successful only when all of these can eventually be demonstrated on a real device:

1. Real PSTN cellular call is active.
2. Remote-party downlink can be captured as PCM.
3. Generated PCM can be injected into the uplink.
4. Capture and injection work simultaneously.
5. Host physical microphone can be excluded from transmitted audio.
6. Host speaker can remain effectively silent.
7. Injected uplink is not destructively recaptured into downlink.
8. Double-talk is usable.
9. Audio latency is measured.
10. Resources and routes restore correctly after call termination.
11. Second and subsequent calls still work.
12. Screen-off and longer calls remain stable.

The first coding deliverable does not need to prove these without a device. It must create the correct harness so these can be proven when the device arrives.

---

## 6. Revised Audio Strategy

Use this backend priority:

```text
FrameworkInterceptionBackend
        ↓
LegacyPrivilegedBackend
        ↓
VendorAudioBackend
        ↓
UnsupportedBackend
```

### 6.1 FrameworkInterceptionBackend

This is the primary M0 target on Android versions that expose the framework capability.

Modern AOSP contains system APIs on `AudioManager` for PSTN call audio:

```text
isPstnCallAudioInterceptable()
getCallDownlinkExtractionAudioRecord(AudioFormat)
getCallUplinkInjectionAudioTrack(AudioFormat)
```

They are system/test APIs, not ordinary third-party SDK APIs.

They require:

```text
android.permission.CALL_AUDIO_INTERCEPTION
```

The permission is privileged/signature/role protected depending on Android branch and is not available to an ordinary third-party APK by default.

For M0, do not directly compile against unavailable hidden/system SDK symbols if that breaks the normal SDK build. Create a narrow reflection/system-API bridge isolated inside the framework backend.

No reflection should leak outside that backend.

Catch and classify:

```text
NoSuchMethodException
SecurityException
IllegalStateException
UnsupportedOperationException
InvocationTargetException
LinkageError
```

Never crash the app because a system API is missing or inaccessible.

### 6.2 LegacyPrivilegedBackend

This is the future fallback for privileged Android versions/devices where framework interception is not available or does not work.

It may eventually use:

```text
AudioRecord
VOICE_DOWNLINK
VOICE_CALL
CAPTURE_AUDIO_OUTPUT
privileged telephony permissions
```

Do not fully implement this backend in Phase 0A. Create the interface/availability model and a safe placeholder only.

### 6.3 VendorAudioBackend

This is the last fallback.

Potential future mechanisms include:

```text
incall_music_enabled=true
tinymix
Qualcomm Incall_Music mixer routes
device-specific mixer profiles
vendor audio parameters
```

Do not implement mixer commands, `su`, Magisk, SELinux policy, or vendor mutations in Phase 0A.

The design must make adding this backend later possible without changing the UI, state machine, diagnostics, or future transport.

---

## 7. Why Framework Interception Is First

Do not begin by copying Qualcomm mixer commands.

The framework path is superior for scale because it gives a stable abstraction when the OEM exposes the required telephony devices and permits the interception role/permission.

AOSP's PSTN capability check looks for internal telephony TX/RX audio devices.

The first real-device question is:

```text
Does this device expose and permit the framework PSTN interception path?
```

Only if that path fails do we descend into legacy/vendor mechanisms.

---

## 8. Non-Root Migration Boundary

The following components must not know whether the host is rooted:

```text
UI
M0 state machine
diagnostics
audio metrics
PCM generators
WAV writer
call-state representation
future codec layer
future transport layer
future protocol
future client applications
```

Only backend/provisioning implementations may know about privilege.

Use capability-driven selection rather than root checks spread through the app.

Preferred shape:

```kotlin
interface CallAudioBackend {
    val id: String
    suspend fun probe(): CallAudioCapability
    suspend fun openDownlink(config: PcmConfig): DownlinkSession
    suspend fun openUplink(config: PcmConfig): UplinkSession
    suspend fun close()
}
```

The exact signatures may be improved if necessary, but preserve the abstraction.

Do not add `isRooted` parameters to general application layers.

A future non-root/OEM/system-role backend must be able to replace the rooted backend without changing the rest of the application.

---

## 9. Required Initial Architecture

Use package boundaries similar to:

```text
com.simrelay.m0
├── audio
│   ├── CallAudioBackend
│   ├── CallAudioBackendFactory
│   ├── AudioBackendId
│   ├── PcmConfig
│   ├── CallAudioCapability
│   ├── SessionState
│   ├── framework
│   │   ├── FrameworkInterceptionBackend
│   │   └── FrameworkAudioApiBridge
│   ├── legacy
│   │   └── LegacyPrivilegedBackend
│   └── vendor
│       └── VendorAudioBackend
├── call
│   ├── CallState
│   ├── CallStateMonitor
│   └── AudioModeSnapshot
├── diagnostics
│   ├── CapabilityProbe
│   ├── DiagnosticReport
│   ├── DeviceSnapshot
│   ├── PermissionSnapshot
│   ├── AudioDeviceSnapshot
│   └── DiagnosticExporter
├── pcm
│   ├── PcmToneGenerator
│   ├── PcmMetrics
│   └── WavWriter
├── presentation
│   ├── M0UiState
│   ├── M0ViewModel
│   └── existing UI files
└── util
```

Adapt this to the template already present in the repository.

Do not recreate the whole project. Do not migrate the UI toolkit. Use whichever UI system the template already uses. Do not create unnecessary modules during M0. A single `app` module is sufficient.

---

## 10. Capability Model

Do not model capability as a Boolean.

Use explicit states so diagnostics are useful:

```text
Supported
UnsupportedByOs
ApiNotPresent
PermissionMissing
PrivilegeMissing
CallNotActive
AudioRouteUnavailable
DeviceNotInterceptable
InitializationFailed
RuntimeFailure
Unknown
```

The capability report should preserve exception class and a sanitized message when useful.

The UI must distinguish:

```text
API does not exist
API exists but permission is missing
permission exists but PSTN audio route is unavailable
call is not currently active
backend initialized successfully
```

This distinction is critical for device qualification.

---

## 11. Phase 0A — Implement Now

This is the task Codex should execute immediately. No rooted phone is required for Phase 0A.

### 11.1 Inspect the existing repository

Before editing:

```text
read settings.gradle.kts
read root build.gradle.kts
read app/build.gradle.kts
read AndroidManifest.xml
inspect existing Activity/UI
run git status
run ./gradlew assembleDebug
```

Do not assume the project matches this document if the actual files disagree. Preserve the existing Android Studio template unless there is a technical reason to change it.

### 11.2 Verify build configuration

Ensure:

```text
minSdk = 24
compileSdk = 36
targetSdk = 36
Java/Kotlin JVM target compatible with JDK 17
```

Do not upgrade unrelated dependencies just because newer versions exist.

### 11.3 Add M0 manifest declarations

Expected candidates:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.CALL_AUDIO_INTERCEPTION" />
<uses-permission android:name="android.permission.CAPTURE_AUDIO_OUTPUT" />
<uses-permission android:name="android.permission.MODIFY_PHONE_STATE" />
```

Do not assume privileged permissions will be granted on a normal install. The app must remain launchable when they are denied.

Do not add SMS, contacts, networking, Bluetooth, notification, location, or broad storage permissions yet unless the current implementation genuinely needs them.

### 11.4 Implement DeviceSnapshot

Capture at least:

```text
Build.MANUFACTURER
Build.BRAND
Build.MODEL
Build.DEVICE
Build.PRODUCT
Build.HARDWARE
Build.BOARD
Build.FINGERPRINT
Build.VERSION.SDK_INT
Build.VERSION.RELEASE
Build.VERSION.SECURITY_PATCH
supported ABIs
```

Keep it serializable to readable text/JSON without adding a heavy dependency.

### 11.5 Implement PermissionSnapshot

Probe:

```text
RECORD_AUDIO
READ_PHONE_STATE
CALL_AUDIO_INTERCEPTION
CAPTURE_AUDIO_OUTPUT
MODIFY_PHONE_STATE
```

For privileged permissions, report actual grant state from `PackageManager`.

Do not runtime-request privileged permissions. Runtime-request ordinary dangerous permissions only when the action genuinely needs them.

### 11.6 Implement audio-system diagnostics

Capture:

```text
AudioManager.mode
AudioManager.isMicrophoneMute
speakerphone state where API permits
available public AudioDeviceInfo devices
device type
product name
source/sink direction
sample rates
channel counts
encodings
```

Do not use hidden internal audio-device constants outside `FrameworkAudioApiBridge`.

### 11.7 Implement FrameworkAudioApiBridge

Responsibilities:

```text
detect whether the three system methods exist
invoke isPstnCallAudioInterceptable
create downlink AudioRecord
create uplink AudioTrack
unwrap InvocationTargetException
map errors to diagnostic states
```

The rest of the application must not perform reflection on `AudioManager`.

Runtime gate the feature to the Android generation where the APIs may exist. On older Android versions, return `UnsupportedByOs` without reflection.

The bridge must not mutate global hidden-API policy. The application must never run `settings put global hidden_api_policy` by itself.

### 11.8 Implement FrameworkInterceptionBackend

It should:

```text
probe API presence
probe CALL_AUDIO_INTERCEPTION grant
probe current audio mode
probe PSTN interceptability
return structured capability result
```

Do not automatically start recording during a capability probe. Opening audio sessions is an explicit user action.

### 11.9 Implement placeholders for fallback backends

Create:

```text
LegacyPrivilegedBackend
VendorAudioBackend
```

They must return explicit unavailable/not-implemented capability states. Do not add root commands. Do not create fake success paths.

### 11.10 Implement backend factory

Selection must be capability based.

Initial behavior:

```text
API 33+ and framework API usable → FrameworkInterceptionBackend
otherwise → report available fallbacks but do not silently use unimplemented ones
```

Do not hide why a backend was rejected.

### 11.11 Implement CallStateMonitor

The application supports API 24+, so use version-specific public APIs safely.

Expose simple states:

```text
Idle
Ringing
OffHook
Unknown
```

Use modern telephony callback APIs where available and legacy `PhoneStateListener` on older supported versions.

Handle permission denial gracefully.

M0 does not need a full `InCallService` yet unless the probe genuinely requires it. Do not make the app default dialer in Phase 0A.

### 11.12 Implement PCM utilities

Implement a deterministic mono PCM16 tone generator.

Initial tone:

```text
frequency: 1000 Hz
sample rate: configurable
amplitude: conservative and non-clipping
duration: configurable
```

Implement:

```text
RMS
peak absolute sample
zero/silence detection
sample count
duration calculation
```

Keep these utilities pure Kotlin and unit-testable.

### 11.13 Implement WAV writer

Support PCM16 mono first.

The writer must produce a valid WAV file from captured PCM. Write only into app-owned storage. Do not request broad filesystem permissions.

### 11.14 Implement explicit audio actions

UI actions:

```text
Run Capability Probe
Start Downlink Capture
Stop Downlink Capture
Inject 1 kHz Tone
Stop Injection
Start Full Duplex
Stop All
Export Diagnostics
```

Until a compatible real device/call/permission exists, unsupported actions must fail cleanly and show the reason. No action should crash the application.

### 11.15 Downlink capture behavior

When explicitly started:

```text
validate backend capability
validate active-call/audio mode
build requested AudioFormat
open framework downlink AudioRecord
start recording
write PCM to WAV
calculate metrics
release cleanly
```

Initial format candidates should be defined as data, not hardcoded throughout the code:

```text
48000 Hz mono PCM16
16000 Hz mono PCM16
8000 Hz mono PCM16
```

Record which format succeeds. Do not assume VoLTE automatically means the app receives 16 kHz PCM.

### 11.16 Uplink injection behavior

When explicitly started:

```text
validate backend capability
validate active-call/audio mode
create framework uplink AudioTrack
generate deterministic 1 kHz PCM
write continuously
stop cleanly
release
```

Use conservative amplitude.

Do not route to ordinary `STREAM_MUSIC` in the framework backend. The framework backend must use the dedicated call-uplink injection API.

The vendor fallback may later use `USAGE_MEDIA`/`incall_music`, but that must remain isolated in `VendorAudioBackend`.

### 11.17 Full-duplex behavior

Full duplex must use the same selected backend for both directions unless the backend explicitly declares hybrid support.

Start RX and TX with deterministic lifecycle ordering.

On any failure:

```text
stop the other direction
release both resources
preserve the error
return to a safe state
```

Do not leave AudioRecord or AudioTrack running after failure.

### 11.18 Diagnostics export

Create one diagnostic run directory per run with a timestamp or run ID.

At minimum generate:

```text
device.json
permissions.json
audio-devices.json
probe.json
metrics.json
app.log
rx.wav when capture exists
```

Do not include private SMS, phone numbers, contacts, or unrelated personal data.

### 11.19 UI

Keep UI diagnostic, not polished.

Display:

```text
device model
Android version/API
build fingerprint
selected backend
CALL_AUDIO_INTERCEPTION grant
framework API presence
PSTN interceptability result
current call state
AudioManager mode
capture state
injection state
last error
latest RMS/peak
output file path
```

Actions should be disabled when the state machine knows they cannot run. Do not spend time on visual design.

---

## 12. State and Concurrency Rules

Audio lifecycle must have one owner.

Required conceptual states:

```text
Idle
Probing
Ready
Capturing
Injecting
FullDuplex
Stopping
Error
```

Protect transitions.

A second Start action while already running must not create another audio session. `Stop All` must be idempotent. Activity recreation must not accidentally duplicate audio sessions.

Do not introduce a foreground service until needed for real call/background testing.

---

## 13. Error Handling

Every low-level failure must become a typed result.

Examples:

```text
permission denied
method unavailable
reflection blocked
no active PSTN mode
PSTN devices unavailable
AudioRecord uninitialized
AudioTrack uninitialized
read returned error
write returned error
resource already open
unsupported AudioFormat
```

Never swallow exceptions silently. Do not expose a raw stack trace as the only UI error. Log enough context to diagnose the device later.

---

## 14. Testing Requirements for Phase 0A

Add JVM unit tests for pure Kotlin logic.

At minimum:

```text
PcmToneGenerator produces expected sample count
tone peak remains below clipping
RMS calculation is correct
silence has zero RMS
WAV header/data sizes are valid
backend selection on API/capability inputs
diagnostic result mapping
state transition validation
```

If Android framework classes make a unit difficult, isolate logic rather than adding a large mocking framework immediately.

Run:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

Fix failures introduced by the implementation. If lint reports existing template issues unrelated to the work, report them instead of making broad unrelated changes.

---

## 15. Definition of Done for Phase 0A

Phase 0A is complete when:

1. Project builds successfully.
2. Unit tests pass.
3. App launches on a normal Android device/emulator without privileged permissions.
4. Capability probe produces structured diagnostics instead of crashing.
5. API < 33 reports framework interception unsupported cleanly.
6. API 33+ can detect API presence and permission state.
7. Framework system API calls are isolated in one bridge/backend.
8. Downlink/injection code paths exist and fail safely when privileges/call state are missing.
9. PCM tone generation works.
10. WAV writing works.
11. Diagnostic export works.
12. No root/vendor command is executed.
13. No architecture outside backend/provisioning layers depends on root.
14. `./gradlew assembleDebug` succeeds.

After completing Phase 0A, stop and report:

```text
files changed
architecture implemented
tests added
commands run
build/test results
known blockers
what requires the physical phone
```

Do not proceed into Magisk/root provisioning automatically.

---

## 16. Phase 0B — Only When the Physical Host Arrives

Do not execute this phase until the user confirms the target phone is available.

Tasks will include:

```text
ADB authorization
device fingerprint capture
bootloader/root status
factory-image backup/recovery plan
privileged APK provisioning
CALL_AUDIO_INTERCEPTION grant verification
hidden/system API access verification
real PSTN call
framework interceptability probe
downlink capture
uplink tone injection
simultaneous RX/TX
speaker/mic isolation
repeatability
latency measurements
```

No destructive device operation without explicit user approval.

---

## 17. Phase 0C — Fallback Research Only If Framework Path Fails

If framework interception fails on the target, collect evidence before changing architecture:

```text
exact device
build fingerprint
Android version
AudioManager mode
permission grants
framework method presence
isPstnCallAudioInterceptable result
available audio devices
exception/error
AudioFlinger dump
audio policy dump
telecom dump
```

Then evaluate:

```text
VOICE_DOWNLINK / VOICE_CALL privileged capture
BCR-style privileged call capture
Qualcomm incall_music path
tinymix device profile
speaker mute controls
vendor timing/restoration
```

All fallback code remains behind `LegacyPrivilegedBackend` or `VendorAudioBackend`.

---

## 18. Vendor Fallback Knowledge

Do not implement this in Phase 0A, but preserve it as research context.

Prior art from `pulpoff/gsm2sip` demonstrates device-specific Qualcomm digital uplink injection using:

```text
AudioManager.setParameters("incall_music_enabled=true")
Incall_Music Audio Mixer MultiMedia1
Incall_Music Audio Mixer MultiMedia2
AudioTrack with media usage
```

Important prior-art findings:

```text
do not assume mixer control names
do not assume generic Qualcomm works
do not use low-latency playback on that vendor path without verifying route
capture sources vary per device
VOICE_CALL can contain both directions
VOICE_DOWNLINK is preferable when it truly works
mixer state must be restored after a call
speaker routing/muting can be device specific
cold-start timing can matter
```

Treat these as hypotheses/reference knowledge for a fallback backend, not framework requirements.

---

## 19. Echo Strategy

Do not implement WebRTC AEC in Phase 0A.

M0 must first determine whether the dedicated framework downlink extraction path already prevents injected uplink from returning in the captured stream.

Later order of preference:

```text
1. Dedicated downlink-only extraction
2. Client platform voice processing
3. Host-side AEC using injected PCM as reference
```

Do not add crude half-duplex gating as the product solution. Human conversation must support double-talk.

---

## 20. Future Transport Boundary

Do not implement media networking in M0.

Future media transport must be behind `MediaTransport`.

Likely candidates later include:

```text
WebRTC/Opus for scalable local/remote operation
direct LAN transport where appropriate
BLE for discovery/control/wake
```

M0 deals in PCM only.

Do not introduce BLE, L2CAP, WebRTC, Opus, TURN, signaling servers, or backend infrastructure yet.

---

## 21. Future Telephony Boundary

M0 call-state observation may use public telephony state APIs. Full call control comes later.

Future host control should remain behind a separate `CallControlBackend`.

Potential implementation paths include:

```text
CONTROL_INCALL_EXPERIENCE
MANAGE_ONGOING_CALLS / companion-device path
ROLE_DIALER fallback
```

Do not make the M0 app the default dialer just to get started.

---

## 22. Security and Privacy Rules

This project will eventually carry live calls and OTPs.

Even though M0 is local:

```text
do not log phone numbers unless explicitly needed
do not log SMS
do not log contacts
do not upload diagnostics
do not add analytics
do not add crash-reporting SDKs
do not add cloud dependencies
```

Audio captures are test artifacts and must remain in app-owned local storage unless the user explicitly exports them.

---

## 23. Dependency Policy

Keep M0 lean.

Prefer Android/Kotlin standard APIs.

Do not add these unless a concrete current M0 requirement justifies them:

```text
WebRTC
Opus
protobuf
libsodium
Noise
Firebase
Retrofit
OkHttp
Room
Hilt
Koin
RxJava
native C/C++
NDK
CMake
```

Coroutines may be used if already available through the project template/dependencies and they simplify lifecycle-safe background work.

Do not add architectural frameworks just to create abstractions.

---

## 24. Code Quality Rules

- No code comments.
- No TODO comments.
- No dead code.
- No placeholder fake success values.
- No giant Activity.
- No reflection outside the framework bridge.
- No root checks outside backend/provisioning code.
- No device model branching outside device-profile/backend logic.
- No hardcoded absolute developer paths in application code.
- No silent exception swallowing.
- No unnecessary dependency upgrades.
- Prefer immutable models.
- Prefer sealed types for meaningful result/state domains.
- Keep Android version checks close to platform implementations.
- Keep pure PCM/metrics code independent of Android framework classes.
- Release AudioRecord and AudioTrack deterministically.

---

## 25. Git Rules

Before changes:

```bash
git status
```

After implementation:

```bash
git diff --check
git status
```

Do not:

```text
push
force push
reset --hard
clean -fd
rebase published history
change remotes
change git credentials
```

Do not commit automatically unless the user asks.

---

## 26. Primary Technical References

Use primary sources when validating framework behavior.

```text
https://android.googlesource.com/platform/frameworks/base/+/master/media/java/android/media/AudioManager.java
https://android.googlesource.com/platform/frameworks/base/+/master/core/res/AndroidManifest.xml
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/services/core/java/com/android/server/audio/AudioService.java
https://github.com/pulpoff/gsm2sip
https://github.com/chenxiaolong/BCR
```

Do not blindly copy prior-art code. Verify behavior against the actual target device and current AOSP behavior.

---

## 27. Important Superseded Assumptions

Older project research treated vendor `VOICE_CALL` capture + Qualcomm `incall_music` as the primary M0 route.

That is now fallback research.

Current priority:

```text
Android framework PSTN interception first
privileged legacy path second
vendor/mixer path third
```

Current application requirement:

```text
minSdk = 24
```

The app shell supports Android 7+, while capabilities are selected per API level/device.

Hardware solutions are out of scope.

Root is an initial development/provisioning mechanism, not the intended permanent architectural dependency.

---

## 28. First Codex Execution Instruction

After reading this file, execute **Phase 0A only**.

Sequence:

```text
1. Inspect repository and current Gradle/UI setup.
2. Run baseline build.
3. Present a short implementation plan in the Codex session.
4. Implement the architecture and capability harness described above.
5. Add tests.
6. Run unit tests, lint, and debug build.
7. Fix implementation-caused failures.
8. Stop.
9. Report exactly what changed and what remains blocked on a real device.
```

Do not ask for permission between ordinary repository edits.

Ask before any action that modifies the operating system, Android SDK installation, Git history/remotes, or a connected physical device in a destructive/privileged way.

The immediate goal is a clean, buildable M0 diagnostic harness ready for the physical host.
