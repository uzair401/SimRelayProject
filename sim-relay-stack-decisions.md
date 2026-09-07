# SIM Relay — Stack Decisions (Phases 1 & 2)

**Version:** 3.0 — revised after independent review (`Sim_Relay_Feasibility_Architecture_Review.docx`, Sept 2026). See §13 for review disposition.
**Date:** 2026-09-03
**Scope:** Phase 1 (Android rooted host → Android client), Phase 2 (same host → iOS client)
**Out of scope:** Phase 3 (no-root). One cheap hedge is retained for it — see §9.1.
**Companion docs:** `sim-relay-rnd-brief.md` (feasibility), `sim-relay-architecture.md` (platform detail)

---

## 1. Decision summary

| Layer | Decision |
|---|---|
| Host app | Native Kotlin, no framework |
| Host audio I/O | **RootShell (`su` + `tinymix`) + Java `AudioRecord`/`AudioTrack`** — no C/JNI |
| Host privilege | Magisk module → priv-app + `privapp-permissions` + sepolicy |
| Host telephony | `InCallService` (via `CONTROL_INCALL_EXPERIENCE`, or `MANAGE_ONGOING_CALLS`; `ROLE_DIALER` as fallback), `ROLE_SMS` |
| Android client | Native Kotlin + Compose, self-managed `ConnectionService` |
| Android client audio | **Oboe (AAudio)** |
| iOS client | Native Swift + SwiftUI, `CallKit` |
| iOS client audio | `AVAudioEngine` with voice-processing I/O |
| Shared logic | **Kotlin Multiplatform** (`commonMain`) |
| Control transport | **BLE GATT** (discovery, presence, wake, pairing, call control) |
| Media transport | **Wi-Fi UDP + Opus** (primary) — revised in v3 |
| Media fallback | BLE L2CAP CoC — benchmark candidate, not default |
| Codec | **libopus** (C), one build, both platforms |
| Echo cancellation | Client-side platform voice processing; host-side AEC3 if needed (§5.1) |
| Control serialization | **Protobuf** |
| Media framing | Raw 4-byte header + Opus payload (no protobuf) |
| Crypto | **Noise Protocol via a reviewed implementation** (XX pairing, KK reconnect) — do not hand-roll the handshake |
| Min versions | Android API 29, iOS 15 |

---

## 2. Host application

**Native Kotlin. Not negotiable.** `InCallService`, `ROLE_DIALER`, `SmsManager`, JNI to tinyalsa, Magisk packaging — nothing here is abstractable, and no cross-platform framework has anything to offer a rooted single-platform daemon.

### 2.1 Audio: root shell + `tinymix` + Java audio APIs — NOT JNI/tinyalsa

**Revised in v2.** The original decision was to write a C library over tinyalsa. Source-reading `gsm2sip` shows this is unnecessary: they drive the mixer by shelling out to the `tinymix` binary via `su`, and move the actual audio with ordinary Java `AudioRecord`/`AudioTrack`. Far less code, far less to debug.

| Concern | Approach |
|---|---|
| **Mixer control** | `RootShell` — batch `tinymix` commands in a single `su` invocation |
| **Downlink capture** | `AudioRecord`, source from a probe ladder (§2.2) |
| **Uplink injection** | `AudioTrack`, `AudioAttributes.USAGE_MEDIA` (→ `STREAM_MUSIC`) |
| **HAL enable** | `AudioManager.setParameters("incall_music_enabled=true")` |
| **Binary deployment** | `tinymix`/`tinycap` to `/data/local/tmp/` (permissive SELinux; `/system/bin` overlay hits denials). ARM64 only unless we build 32-bit too. |

#### Uplink injection — the Qualcomm mechanism

Three parts, all required:

1. HAL property: `incall_music_enabled=true` via `AudioManager.setParameters`
2. Mixer: `tinymix 'Incall_Music Audio Mixer MultiMedia1' 1` and `MultiMedia2`
3. App: `AudioTrack` on `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC`

Injection level is set by `STREAM_MUSIC` volume as a percentage of max (14% on MSM8930, 20% generic in their profiles) plus a software gain. **Needs calibration per device.**

Verified from their source: this is **digital injection into the modem uplink — there is no acoustic speaker→mic path.** Good for quality.

#### 🚨 Never use `PERFORMANCE_MODE_LOW_LATENCY`

Low-latency mode forces the HAL into the "low-latency-playback" usecase → **MultiMedia5**, which has **no `Incall_Music` mixer control**. Audio plays locally and is never injected into the uplink. No error is raised anywhere — the far end simply hears nothing.

Use default (deep-buffer-playback → MultiMedia1). **Consequence: the host playback stage costs more latency than §4.4 budgets. Re-measure.**

#### Speaker mode is mandatory

Every `gsm2sip` device profile sets `requireSpeakerMode = true`. On the S10e in earpiece mode, *all* capture sources returned RMS 0. So the call must be routed to speaker — and the speaker must then be muted at mixer level (Qualcomm: `Voice Rx Device Mute`, `RX3 Digital Volume`, `SPK DRV Volume`, `Speaker Boost Volume` to 0).

⚠️ **On at least one Exynos firmware speaker muting was impossible** — no speaker-amp ALSA controls exposed, and cutting the amp's I2S bus killed the voice path. **Verify muting works on our target before committing to it**, or the host audibly leaks every call.

#### Ordering and lifecycle constraints

| Constraint | Reason |
|---|---|
| Enable `incall_music` **after** capture is established | Lets `AudioRecord` lock its PCM device before the HAL re-routes |
| 500–700 ms delay after speaker route change before mixer setup | Route settling |
| Retry `AudioRecord` for up to ~30 s on cold boot | AudioFlinger refuses record tracks for 10–30 s after the modem voice path starts |
| Re-assert `appops RECORD_AUDIO` **synchronously** before opening `AudioRecord` | Async re-assertion races: `AudioRecord` reads silence before the grant lands |
| Restore `Voice Tx Device Mute` → 0 and reset `Incall_Music` mixers on call end | Otherwise the **second** call fails to answer; the HAL doesn't reset them |

#### Exynos fallback (do not lead with this)

No `Incall_Music` mixer exists. `gsm2sip` instead reroutes the ABOX modem-TX source from mic to playback mixer:

```
tinymix 'ABOX NSRC0' 'SIFS0'
tinymix 'ABOX NSRC1' 'SIFS0'     # BOTH — modem reads NSRC1
```

Routing `NSRC1→SIFS1` produces a feedback loop (SIFS1 carries modem downlink). Restore both to `UAIF0` on call end. Treat Exynos as a secondary target.

### 2.2 Capture source: probe, don't assume

Do not hardcode `VOICE_CALL`. Probe in order and record what works per device:

`VOICE_CALL@16k` → `VOICE_CALL@8k` → `VOICE_DOWNLINK@16k/8k` → `VOICE_RECOGNITION` → `MIC` → `VOICE_COMMUNICATION`

**Prefer `VOICE_DOWNLINK` where it works.** `VOICE_CALL` mixes uplink *and* downlink, so capturing it while injecting means **capturing our own injected audio and sending it back** — an echo loop. `VOICE_DOWNLINK` avoids this structurally. Device support varies, so carry a `voiceDownlinkWorks` flag per profile.

### 2.3 Per-device profiles are unavoidable

Adopt `gsm2sip`'s pattern: a `DeviceProfile` data class holding mixer setup/restore/incall-music commands, volume calibrations, gate thresholds, `requireSpeakerMode`, `voiceDownlinkWorks`, `playbackUsage`, and timing delays. Detect on `Build.HARDWARE` / `Build.BOARD` / `Build.MODEL`, falling back generic-Qualcomm → generic-Exynos → generic (Android APIs only, no mixer hacks).

**We ship a device compatibility list, not "Android support."** Design for that from the first commit.

### 2.4 Sample rate

Match the source, don't resample. VoLTE (AMR-WB) surfaces at 16 kHz; 2G/3G narrowband at 8 kHz. Detect the PCM device's rate at open and configure Opus accordingly. **Resampling on the host is wasted CPU and added latency** — send what the modem gives you and let the client's output stage handle rate conversion if needed.

### 2.5 Privilege packaging

Magisk module carrying:
- APK bind-mounted into `/system/priv-app/SimRelayHost/`
- `privapp-permissions-simrelay.xml` in `/system/etc/permissions/`
- `system.prop` (below)
- `tinymix` / `tinycap` binaries, deployed to `/data/local/tmp/` by `service.sh`
- `sepolicy.rule` for PCM device access
- `service.sh` to force appops and (during development) sync the APK from `/data/app` into the priv-app overlay so `adb install -r` + reboot works

**`system.prop` — verified set from `gsm2sip`:**
```
# Qualcomm — allow concurrent audio during GSM voice calls
voice.record.conc.disabled=false
voice.voip.conc.disabled=false
voice.playback.conc.disabled=false

# Disable Qualcomm Fluence voice DSP — interferes with capture/injection
ro.qc.sdk.audio.fluencetype=none
persist.audio.fluence.voicerec=false
persist.audio.fluence.speaker=false
persist.audio.fluence.voicecall=false

# Samsung Exynos
persist.audio.call_record.enabled=true
```

**Privileged permissions — fuller list than v1 assumed:**
`CAPTURE_AUDIO_OUTPUT`, `READ_PRIVILEGED_PHONE_STATE`, `READ_PRECISE_PHONE_STATE`, `MODIFY_PHONE_STATE`, `CALL_PRIVILEGED`, `READ_LOGS`, `WRITE_SECURE_SETTINGS`, `INTERACT_ACROSS_USERS`, `REGISTER_CALL_PROVIDER`, `REGISTER_SIM_SUBSCRIPTION`, `BIND_INCALL_SERVICE`, `BIND_TELECOM_CONNECTION_SERVICE`

**appops:** `RECORD_AUDIO` gets reset to `MODE_FOREGROUND` by `PermissionController`, which kills background capture. `gsm2sip` solves this by hiding PermissionController's APK entirely via a Magisk overlay — effective but it destroys the host's permission-management UI for every app. **Prefer synchronous re-assertion of appops immediately before opening `AudioRecord`** (which they also do) and only escalate if that proves insufficient.

Keep the module **per-device**, keyed on `ro.product.device`. Do not ship one module claiming universal support.

### 2.6 Telephony

`ROLE_DIALER` for `InCallService`; render an empty in-call UI so the host stays dark. `ROLE_SMS` for messaging. Foreground service with `foregroundServiceType="phoneCall"`.

**Three ways to get `InCallService`, in preference order:**

1. **`CONTROL_INCALL_EXPERIENCE`** (privileged) — telephony binds our service directly, no dialer takeover, and the service can foreground itself during a call, sidestepping Android 12+ background-mic limits. We already hold privileged permissions, so this is free. See BCR.
2. **`MANAGE_ONGOING_CALLS`** — granted by associating a device via `CompanionDeviceManager` under a device profile (e.g. watch). Officially sanctioned companion-app path, no root needed for this part, no dialer takeover. **Spike in M1: can the client phone be associated under the watch profile?** Half a day; materially better product if it works.
3. **`ROLE_DIALER`** — works, but hijacks the user's dialer on their daily-driver phone. Fallback only.

Note `gsm2sip` took route 3. BCR takes route 1. Route 1 or 2 is the better product.

---

## 3. Shared logic: Kotlin Multiplatform

### 3.1 Why KMP over Flutter or React Native

The deciding factor is **where the complexity sits**.

Your hard code is `InCallService`, tinyalsa, `CallKit`, `PushKit`, `AVAudioEngine`, `CBL2CAPChannel`, `ConnectionService`. Your shared code is protocol, state machine, crypto, and models — pure logic with almost no UI.

A UI-first framework abstracts the part that was never hard and forces everything difficult through a platform channel. For this project that's backwards. Concretely, Flutter would mean: a `flutter_callkit_incoming`-class wrapper whose quirks compound with CallKit's own, a bridge on the audio path, and no help at all on the host.

KMP inverts it: share the logic, write each platform's hard parts natively with full API access and no bridge.

**Decisive secondary reason:** the host is Kotlin. With KMP the wire protocol is written **once and consumed by all three binaries** — host, Android client, iOS client. Under Flutter the host would need a second, hand-maintained implementation of the same protocol in Kotlin. Two implementations of one protocol is the single most reliable way to ship desync bugs.

### 3.2 Module layout

```
:shared          (KMP: androidMain, iosMain, commonMain)
:host-android    (Kotlin, depends on :shared)
:client-android  (Kotlin, depends on :shared)
ios/             (Swift, consumes :shared as XCFramework)
native/          (C: libopus, libsodium — no audio wrapper needed)
```

### 3.3 What goes where

| In `commonMain` | Stays native |
|---|---|
| Protobuf message definitions + codec | BLE (GATT + L2CAP) |
| Noise handshake + session crypto | Audio capture/playback |
| Call state machine | Telecom / CallKit |
| SMS + call-log models | UI |
| Pairing logic | Notifications, background lifecycle |
| Capability negotiation | |
| Jitter buffer | |

**Note the jitter buffer is shared.** It's pure logic over timestamps and sequence numbers, needs no platform API, and is the last thing you want two divergent implementations of.

### 3.4 iOS integration

Expose `:shared` as an XCFramework. Keep the public surface **narrow and Swift-friendly**: suspend functions and `Flow` map awkwardly into Swift, so wrap them in callback- or `AsyncStream`-shaped facades at the boundary. Decide this early — retrofitting the KMP public API after the Swift client is written is genuinely painful.

---

## 4. Transport

**Revised in v3.** v2 made BLE L2CAP CoC the primary media path and accepted its head-of-line blocking as a known trade-off. The independent review challenged this, correctly. Reliable ordered delivery is the wrong semantics for interactive voice: a delayed packet stalls everything behind it, where a real-time codec would rather drop it and conceal the gap.

### 4.1 Two channels, different technologies

| Channel | Mechanism | Carries |
|---|---|---|
| **Control** | BLE GATT | Advertising, discovery, presence, pairing, capability exchange, call events, SMS, wake |
| **Media** | **Wi-Fi UDP + Opus** | Voice, both directions |

BLE GATT stays connected always — cheap, and it is what wakes a suspended iOS client (a UDP socket on a suspended app receives nothing, which is precisely why the control channel must be BLE). Wi-Fi comes up on call start.

**The host is already running a hotspot** to provide the client's data, so the client is on that network anyway. v2's justification for avoiding Wi-Fi — hotspot lifecycle management — was weak, since we pay that cost regardless.

### 4.2 Why UDP for media

| Property | Why it matters here |
|---|---|
| No head-of-line blocking | A late packet is discarded, not queued ahead of fresh audio |
| Loss concealment | Opus PLC + in-band FEC handle loss better than retransmission handles latency |
| Natural jitter-buffer fit | Sequence numbers and timestamps, standard real-time shape |
| Bandwidth headroom | Wi-Fi has orders of magnitude more than we need |

This matters more than v2 assumed because **deep-buffer playback is mandatory on the host** (§2.1 — low-latency mode breaks injection entirely). The host playback stage already costs us latency we can't recover, so the transport must not add avoidable delay on top.

### 4.3 BLE L2CAP CoC — fallback and benchmark, not default

L2CAP CoC remains genuinely useful and worth implementing behind the same interface: it gives a bidirectional byte stream to the peer that runs alongside but independently of GATT, with reads and writes bypassing the GATT operation queue. Bandwidth is sufficient (Opus at 16 kHz / 24 kbps ≈ 3 kB/s per direction against tens of kB/s available).

Keep it as:
- **Fallback** when Wi-Fi is unavailable or the hotspot is down
- **Benchmark** to measure against UDP on latency, loss behaviour, sustained-call stability, and locked-iPhone background behaviour

Do not promote it to primary without those measurements.

**PSM gotcha if used:** both platforms allocate the PSM dynamically, so the listener's PSM must be passed out of band — publish it as a GATT characteristic. Use the **insecure** variant (`createInsecureL2capChannel` / `listenUsingInsecureL2capChannel`); the secure variants require an authenticated BLE link key, dragging in OS pairing dialogs and inconsistent cross-platform behaviour, for weaker guarantees than the Noise layer we run anyway (§6).

### 4.4 `MediaTransport` interface

Both paths sit behind one interface from the first commit:

```kotlin
interface MediaTransport {
    suspend fun open(): Result<Unit>
    fun send(frame: ByteArray, seq: Int)
    val inbound: Flow<TimestampedFrame>
    suspend fun close()
}
```

`UdpMediaTransport` and `L2capMediaTransport`. The audio pipeline must not know which is in use. This is what makes the benchmark in §4.3 a configuration change rather than a rewrite.

### 4.5 Latency budget — measure, don't plan

| Stage | v2 estimate |
|---|---|
| Capture buffer | 20 ms |
| Encode | 20 ms |
| Transport (LAN) | 5–30 ms |
| Jitter buffer | 40–60 ms |
| Decode + playout | 20 ms |
| **Added** | **~105–150 ms** |

⚠️ **Treat these as placeholders, not a budget.** Deep-buffer host playback is mandatory and costs more than the 20 ms assumed. A cellular call already runs ~150–250 ms mouth-to-ear. Target under 400 ms total; beyond that parties talk over each other. **Measure on real hardware in M0 and replace this table with observed figures.**

Keep the jitter buffer adaptive and share it in `commonMain` — it is pure logic over sequence numbers and timestamps, and the last thing you want two divergent implementations of.

### 4.6 Security — non-negotiable

**This link carries live voice and banking OTPs.** An unauthenticated relay is an account-takeover primitive.

- Out-of-band pairing via QR code, carrying the host's static public key so the client verifies rather than trusts
- E2E encryption on **both** channels
- Client re-authenticates the host on every reconnect — never auto-trust anything advertising the right service UUID

## 5. Codec: libopus

One C build, both platforms — via CMake/NDK on Android, cinterop on iOS. **Same libopus version on both sides**, pinned. Codec version drift between platforms produces intermittent artefacts that are miserable to diagnose.

| Setting | Value | Why |
|---|---|---|
| Application | `OPUS_APPLICATION_VOIP` | Tuned for speech |
| Frame size | 20 ms | Standard; balances overhead against latency |
| Bitrate | CBR ~24 kbps | **CBR not VBR** — predictable pacing matters more than bandwidth on a link with 5x headroom |
| FEC | On (`OPUS_SET_INBAND_FEC`) | Cheap insurance against loss |
| DTX | Off | Continuous flow keeps jitter estimation clean; bandwidth isn't scarce |
| Complexity | 5–7 | Leaves CPU headroom on cheap hosts |

**Do not use libwebrtc for the full stack.** See §8. (One exception: its AEC3 module — see §5.1.)

### 5.1 🚨 Echo cancellation — now the lead technical risk

**New in v2.** `gsm2sip` does not solve this: their source notes platform AEC is structurally unavailable on this path, because `AudioTrack` is on `USAGE_MEDIA` while `AudioRecord` is on a telephony source — different streams, so platform AEC has no reference signal. They offload echo cancellation to Asterisk.

**We have no Asterisk, and our users are humans who talk over each other.** Their fallback — software noise/echo gates with a double-talk ratio — is turn-taking behaviour adequate for an AI agent, not for a phone call.

Strategy, in order:

1. **Capture `VOICE_DOWNLINK` only** (§2.2). Removes the loop at source: we never capture our own injection. Try this first; it may be sufficient.
2. **Platform voice processing on the client.** iOS `.voiceChat` / `AVAudioEngine` voice-processing I/O, Android `AcousticEchoCanceler`. Handles the *client's* local acoustic echo (its speaker into its mic). **Required regardless of 1.**
3. **Host-side AEC with our own injected buffer as reference.** We know exactly what we injected, so WebRTC's AEC3 with that as the reference signal is tractable. More work, but this is the correct answer if 1 and 2 fall short.

**Add to M0's success criteria:** not just "the far end can hear us" but "both parties can talk simultaneously without echo." Their `noiseGateThreshold` / `echoGateThreshold` / `doubleTalkRatio` values are a useful starting calibration, not a solution.

---

## 6. Crypto: Noise Protocol

**Noise Protocol via a reviewed implementation**, in `commonMain`.

⚠️ **Revised in v3.** v2 said "Noise over libsodium," which reads as building the handshake state machine from primitives. Don't. Use an existing, reviewed Noise implementation (e.g. `noise-c` or `noise-java` via cinterop/JNI so both platforms share one build), or an established authenticated session protocol. Hand-rolled handshakes fail in ways that don't show up in testing.

- **Pairing:** `Noise_XX` — mutual authentication with no prior knowledge, static keys exchanged and confirmed. Bind it to a QR code shown on the host and scanned by the client; the QR carries the host's static public key so the client can verify rather than trust.
- **Reconnect:** `Noise_KK` — both static keys already known. One round trip, fast enough to run on every reconnect.
- **Transport:** ChaCha20-Poly1305 with a counter nonce per direction. Encrypt after serialization, per message.

**Why not DTLS/TLS:** those want a PKI. You have exactly two endpoints and a QR code. Noise is built for raw-key mutual auth and is a far better fit — smaller, no cert handling, no CA.

**Why this matters more than usual:** the link carries banking OTPs. An unauthenticated relay is an account-takeover primitive, not a privacy nicety. Build this in phase 1, not phase 4 — retrofitting crypto into a working protocol is where security bugs live.

---

## 7. Serialization

**Two different answers, deliberately.**

**Control plane — Protobuf.** Schema evolution, generated code for Kotlin and Swift from one `.proto`, explicit field numbering. Length-prefix each message with a varint.

**Media plane — raw framing.** A 4-byte header (sequence `uint16`, flags `uint8`, length `uint8`) plus the Opus payload. Protobuf per 20 ms frame is 50 frames/second of pointless encode/decode and per-message overhead. Audio frames have a fixed shape that will never evolve; give them a fixed header.

### 7.1 Version negotiation from message one

First control message after the handshake is a capability exchange: protocol version, supported codecs, feature flags. **Both ends will be updated independently forever** — a user's host might be three versions behind their client. Design for that now; it costs one message and saves a migration.

---

## 8. Rejected stack choices

Recorded so they don't get re-litigated.

| Rejected | Why |
|---|---|
| **Flutter / React Native** | Abstracts the trivial layer (UI), bridges the hard layer (CallKit, audio, BLE, telecom). Also forces a second hand-written protocol implementation for the Kotlin host. §3.1 |
| **libwebrtc** | Enormous dependency. Brings ICE, STUN/TURN, signalling, DTLS-SRTP — all of it solving problems you don't have on a point-to-point BLE link with QR pairing. You'd use ~5% of it and inherit 100% of the build complexity. Take libopus and write ~200 lines of jitter buffer instead. |
| **MQTT** | TCP pub/sub with a broker, built for telemetry. Broker round-trip, head-of-line blocking, retained-message semantics you don't want. Wrong tool for real-time media, and needs infrastructure you otherwise don't. |
| **WebSocket for media** | TCP; you'd reimplement sequencing and jitter handling on top. Fine for control if you ever add a server; unnecessary now. |
| **tinyalsa via JNI/C for audio I/O** | **Rejected in v2.** `gsm2sip` proves mixer control via `su` + the `tinymix` binary plus Java `AudioRecord`/`AudioTrack` is sufficient. Writing a C library buys nothing and costs a build system, an ABI surface, and a debugging layer. |
| **GATT notifications for audio** | Chunking, manual reassembly, and throughput contention with control traffic. L2CAP CoC exists precisely to avoid this. §4.2 |
| **Secure L2CAP channels** | Requires authenticated BLE link keys → OS pairing dialogs and inconsistent cross-platform behaviour, for weaker guarantees than the Noise layer you're already running. §4.4 |
| **DTLS-SRTP** | PKI overhead for a two-endpoint system with out-of-band key exchange. §6 |
| **BLE L2CAP CoC as primary media transport** | **Rejected in v3.** Reliable ordered delivery is wrong for interactive voice — head-of-line blocking stalls fresh audio behind a retransmit. Retained as fallback and benchmark (§4.3), not default. |
| **Compose Multiplatform for shared UI (now)** | Not wrong, just premature. The clients need a dialer, an SMS list, and an in-call screen. Native UI on each side is cheap and keeps CallKit/`ConnectionService` integration clean. Revisit once the protocol is stable. |

---

## 9. Ceiling risks and the hedges against them

These are the decisions that are expensive to reverse. Each is cheap to get right now.

### 9.1 Audio path behind an interface

Even with phase 3 out of scope, define:

```kotlin
interface HostAudioStrategy {
    fun onCallActive(call: Call)
    fun onCallEnded(call: Call)
}
```

`RelayAudioStrategy` (mixer + `AudioRecord`/`AudioTrack` + transport) is what you build. `BluetoothRouteStrategy` is a few days' work later, if ever — and it should use **`requestCallEndpointChange(CallEndpoint, …)`**, since `setAudioRoute` is deprecated in favour of it, with routes enumerated via `onAvailableCallEndpointsChanged`. Cost of the interface today: near zero. Cost of retrofitting it after root assumptions have spread through the host: a fork.

### 9.2 Don't let the Android client define the contract

The Android client can write relayed SMS into the system SMS provider and enjoys far more background freedom than iOS. Both are fine to build — but keep them **strictly optional capabilities behind feature flags**. If the protocol or UX assumes them, the iOS client will feel broken rather than merely different, and you'll discover it in phase 2 when it's expensive.

### 9.3 Protocol versioning from message one

§7.1. The most common cause of "we have to rewrite the transport" is a v1 protocol with no version field.

### 9.4 KMP public API shape

§3.4. Decide the Swift-facing surface before writing the iOS client, not during.

### 9.5 Pin the native libraries

libopus and libsodium: same pinned version, same build flags, both platforms. Version drift across platforms produces intermittent, non-reproducible audio and crypto failures.

---

## 10. Minimum versions

| Platform | Minimum | Reason |
|---|---|---|
| Android host | API 29 (Android 10) | `createL2capChannel`; also where call-audio restrictions tightened, so the privileged path is designed against it |
| Android client | API 29 | L2CAP CoC |
| iOS client | iOS 15 | `CBL2CAPChannel` needs 11+, but CallKit maturity, SwiftUI, and `AsyncStream` all argue for 15 |

**Revised in v3:** with Wi-Fi UDP as primary media, L2CAP no longer sets the floor — but keep API 29 / iOS 15 anyway, since L2CAP is retained as a fallback and the other reasons (CallKit maturity, SwiftUI, `AsyncStream`, Android 10 call-audio restrictions) stand on their own. Given the host must be rooted and the client is typically a recent iPhone, neither floor costs meaningful market.

---

## 11. Build and tooling

| Concern | Choice |
|---|---|
| Build system | Gradle (KMP + both Android apps), Xcode for iOS, CMake for native C |
| Native deps | Git submodules for libopus/libsodium, built per-platform from source. **No native audio library** — host audio is Java APIs + `tinymix` shell (§2.1). `tinymix`/`tinycap` are prebuilt binaries shipped in the Magisk module. |
| CI | GitHub Actions — Android + KMP on Linux runners, iOS on macOS runners |
| Magisk module | Shell script in-repo; per-device, keyed on `ro.product.device` |
| Protobuf codegen | `.proto` in `:shared`; Wire or protobuf-kotlin for Kotlin, SwiftProtobuf for iOS |
| Logging | Structured, in-memory ring buffer with an export path — you cannot debug relay timing from `logcat` on two devices at once |

**Build a protocol conformance test suite in `commonMain` early.** Golden-vector tests for encode/decode and handshake, running in CI. It's what catches host/client desync before a user does, and it's nearly free once the codec is shared.

---

## 12. Phase sequencing against this stack

**Revised in v3** per the review's milestone structure.

| # | Deliverable | Exit condition |
|---|---|---|
| **M0** | **Audio feasibility** on one exact rooted Snapdragon host + firmware | Capture, injection, host silence, full duplex, clean teardown, second-call correctness. Shell/harness only — no product code. **Gates everything.** |
| **M1** | Host control + messaging | Telephony integration, SMS/OTP, dual-SIM, lifecycle, BLE pairing and control plane |
| **M2** | Media transport | Opus framing, UDP media, adaptive jitter buffer, loss handling, reconnection — validated against a **headless test client** (§12.1) |
| **M3** | Client integration | iOS CallKit / `AVAudioEngine` first; Android client second |
| **M4** | Reliability + security | Authenticated pairing, session keys, background behaviour, reconnects, call races, failure recovery |
| **M5** | Device qualification | Add host profiles one device + firmware at a time, with automated regression tests |

Steps M0–M2 produce everything reusable. `:shared` (protocol, Noise, protobuf, jitter buffer, conformance tests) is built during M1 and has no I/O of its own.

### 12.1 On client ordering — a real tension

The review puts **iOS first** at M3. The project owner's stated plan is **Android host → Android client first**, for velocity: one language, one IDE, `adb logcat` on both ends, no App Store, no PushKit, no iOS background guesswork.

Both are defensible. iOS is where the product actually lives and where the client-side unknowns are; Android-first is far faster to debug, which matters a lot for a solo developer.

**Suggested reconciliation:** validate M2's media transport against a **headless test client** — a CLI/desktop endpoint that speaks the protocol, plays received audio and sends a known signal. That gives Android-level debuggability without building a full Android client you may not ship, and it makes the protocol the contract rather than either client. Then go to iOS at M3, and treat the Android client as an optional later SKU.

**Regardless of ordering:** get a DIRBS-blocked (or SIM-less) client device into the loop by M1. A stock Android client with its own working SIM will never show you the product's real failure modes, and you'll optimise for a scenario no user has.

---

## 13. Independent review disposition

Review: `Sim_Relay_Feasibility_Architecture_Review.docx`, September 2026. Verdict: *doable, with a hard M0 gate.* Points accepted and rejected, recorded so the other agent knows what moved and why.

### Accepted — changed the document

| Review point | Change |
|---|---|
| Wi-Fi UDP should be primary media, not BLE L2CAP | §4 rewritten. UDP primary, L2CAP demoted to fallback/benchmark behind a `MediaTransport` interface. |
| Don't hand-roll crypto from primitives | §6 now specifies a reviewed Noise implementation. |
| M0 must include silence, full duplex, and teardown/second-call correctness | §12 M0 exit condition expanded; qualification suite added to the brief. |
| Don't assume sample rate from network technology | §2.4 already probed at open; wording tightened to remove the VoLTE/2G assumption. |
| Per-device profiles are product architecture, qualified against model + firmware | §2.3 already had profiles; now explicitly model **+ firmware**. |
| Manage as a hardware compatibility program, not a generic Android app | Adopted as the product frame. Ship compatibility, not hope. |
| Generic Qualcomm support is not proven | Accepted. `genericQualcomm()` is a starting hypothesis, not a claim. |

### Accepted with a nuance

- **Review rates uplink injection Amber–Red and "the harder of the two."** After source-reading `gsm2sip`, on Qualcomm with `Incall_Music` the injection has a known recipe. The harder problems are now, in order: **echo/duplex (Red)**, **host speaker muting (Amber–Red — proven impossible on at least one Exynos firmware)**, then injection and capture (Amber). The review's own §8 no-go list reflects this ordering even though §2 doesn't.
- **Review puts iOS client first.** See §12.1 — reconciled via a headless test client rather than choosing.

### Gaps in the review worth carrying forward

1. **Emergency calls** (brief §8.1) are not mentioned. Still unverified, and still the item with the worst downside if wrong.
2. **`MANAGE_ONGOING_CALLS` / `CONTROL_INCALL_EXPERIENCE`** as alternatives to `ROLE_DIALER` (§2.6) — avoids hijacking the user's dialer.
3. **The `PERFORMANCE_MODE_LOW_LATENCY` trap** (§2.1). Notably this *reinforces* the review's transport argument: mandatory deep-buffer playback on the host means the transport must not add avoidable latency.
4. **App Store review risk** for the iOS client — a distribution blocker independent of engineering.
5. **appops / `PermissionController` interference** (§2.5) — a real reliability hazard during long-running background operation.
