# SIM Relay — Prior Art & Implementation Research

**Version:** 3.0 — source-verified
**Date:** 2026-09-03
**Method:** Cloned and read `pulpoff/gsm2sip` source directly; platform commits; vendor docs; developer forums. Market analysis excluded.
**Companion docs:** `sim-relay-rnd-brief.md`, `sim-relay-stack-decisions.md`

> **How to use this document.** Everything below is other people's work, read as a *reference*, not as authority. Their code is undated in places, version-scarred, and tuned for their far end (Asterisk), not ours. Treat every mixer control and property as **a hypothesis to verify on our own device**, not a fact. What it genuinely gives us is a map of which doors exist and which are dead ends — worth months of blind probing.

---

## 0. Headline findings

1. **The uplink question is answered: it is DIGITAL, not acoustic.** Verified from source comments. Qualcomm's `Incall_Music` mixer injects `AudioTrack` output straight into the modem uplink with no speaker→mic path. My earlier concern in v2 §1.2 is resolved — good news for product quality.
2. **No C or JNI needed.** They use `su` + the `tinymix` binary for mixer control and ordinary Java `AudioRecord`/`AudioTrack` for the audio itself. **This invalidates the stack doc's tinyalsa-via-JNI decision.**
3. **We now have a working per-chipset recipe** — exact mixer control names, HAL properties, volume calibrations, and ordering constraints for Qualcomm (MSM8930, generic) and Exynos 9820.
4. **A long list of hard-won gotchas** that would each have cost days: `PERFORMANCE_MODE_LOW_LATENCY` silently breaks injection; `PermissionController` re-revokes appops; cold-boot `AudioRecord` fails for 10–30s; speaker mode is mandatory; restore state or the *second* call fails.
5. **Echo is unsolved in their design** — they punt it to Asterisk. We have no server. **This is now our biggest open engineering problem, replacing uplink injection.**
6. **Three industry implementations triangulate the architecture.** Google, Microsoft, and IKOS all relay call audio at the OS or firmware layer, never through an app.
7. **New API findings:** `MANAGE_ONGOING_CALLS` avoids needing `ROLE_DIALER`; `setAudioRoute` is deprecated in favour of `requestCallEndpointChange`.

---

## 1. `pulpoff/gsm2sip` — verified mechanism

**https://github.com/pulpoff/gsm2sip** — 66 stars, 37 forks, 8 commits. Kotlin, Gradle KTS. Bridges GSM calls on a rooted Android to a SIP/Asterisk server for an AI voice agent.

**Architecturally this is our host.** GSM call ↔ IP audio, bidirectional, `InCallService` for control. Only the far end differs: SIP/RTP to Asterisk, where we'd use L2CAP to a client app.

Internal version comments run to **v2.8.50**, with a visible trail of failed attempts. That history is as valuable as the working code — it tells us what not to try.

### 1.1 Uplink injection — Qualcomm

Their source comment settles the question directly:

> `incall_music` injects AudioTrack output digitally into the modem uplink — there is no acoustic speaker→mic path, so deep-buffer headroom is unnecessary.

The mechanism has three parts:

| Part | Detail |
|---|---|
| **HAL property** | `AudioManager.setParameters("incall_music_enabled=true")` |
| **Mixer control** | `tinymix 'Incall_Music Audio Mixer MultiMedia1' 1` (and `MultiMedia2`) |
| **App-side** | Ordinary `AudioTrack`, `AudioAttributes.USAGE_MEDIA` → `STREAM_MUSIC`, which the HAL injects into voice TX |

**Injection level** is controlled by `STREAM_MUSIC` volume as a percentage of max — 14% on MSM8930, 20% generic, plus a software gain multiplier. Tuned, not obvious.

### 1.2 ⚠️ The `LOW_LATENCY` trap — would have cost days

Their comment, worth quoting in full because it is the least discoverable fact in the whole project:

> **IMPORTANT: Do NOT use `PERFORMANCE_MODE_LOW_LATENCY` here.** Low-latency forces the HAL to use "low-latency-playback" usecase which maps to MultiMedia5. On MSM8930 (Galaxy S4 Mini), only MultiMedia1 and MultiMedia2 have `Incall_Music` mixer controls. MultiMedia5 has no incall_music mixer, so audio plays on the earpiece but is **NEVER injected into the modem uplink.** Using default (deep-buffer-playback → MultiMedia1) ensures the `Incall_Music Audio Mixer MultiMedia1` routes audio to the caller.

The instinct on a real-time audio path is to reach for low-latency mode. Here it silently breaks the entire product — audio plays locally, far end hears nothing, no error anywhere.

**Consequence for our latency budget:** deep-buffer playback is mandatory, so the host's playback stage costs more than the 20 ms the stack doc assumed. Re-measure §4.4 of the stack doc against reality.

### 1.3 Uplink injection — Exynos 9820, a different route entirely

No `Incall_Music` mixer exists on Exynos. Their source notes that `USAGE_MEDIA` there only plays on the speaker without reaching the modem, and that v2.8.41 proved `USAGE_VOICE_COMMUNICATION` is ignored by the HAL for TX injection.

Their workaround is **ABOX NSRC bridge rerouting**: repoint the modem's TX source from the mic to the playback mixer.

```
tinymix 'ABOX NSRC0' 'SIFS0'
tinymix 'ABOX NSRC1' 'SIFS0'    # BOTH required
```

Their documented routing map for the S10e during a call:

```
NSRC0 = UAIF0  Bridge=On    mic/codec → modem TX
NSRC1 = UAIF0  Bridge=On    (redundant path)
NSRC2 = SIFS0  Bridge=Off   playback mixer
SIFS1 → UAIF1 → CS35L41     speaker amp
RDMA0 → VPCMOUT_DAI0        modem downlink
VPCMIN_DAI0 → WDMA1         modem uplink capture
UAIF0=Codec  UAIF1=SpkAmp  UAIF2=FM  UAIF3=Bluetooth
```

Their iteration trail:
- v2.8.42 — `NSRC0→SIFS0` + `NSRC1→SIFS1`: caller heard **loud noise** (SIFS1 carries modem downlink → feedback loop)
- v2.8.43–44 — `NSRC0` only: **silent** (the modem reads NSRC1, not NSRC0)
- v2.8.45 — **both → SIFS0**: clean injection, no feedback

They also report they **could not mute the speaker at all** on that firmware — no speaker-amp ALSA controls exposed, and disconnecting `ABOX UAIF1 SPK` killed the entire voice path.

**Read this as confirmation that Qualcomm is the right target.** Exynos works, but as a reverse-engineered bridge hack with an unmutable speaker.

### 1.4 Capture — a fallback ladder, not one source

They probe in order rather than assuming: `VOICE_CALL@16k` → `VOICE_CALL@8k` → `VOICE_RECOGNITION` → `MIC` → `VOICE_COMMUNICATION` → `VOICE_DOWNLINK@16k/8k`, and carry a per-device `voiceDownlinkWorks` flag (false on MSM8930, true on Exynos 9820).

**Copy this pattern.** It's how you survive device variation, and it doubles as an M0 diagnostic — their `MainActivity` has a source-probe screen testing every source and reporting AEC availability. Build that first; it *is* our M0 harness.

**A subtlety they hit and we must design around:** `VOICE_CALL` mixes uplink *and* downlink. If we capture `VOICE_CALL` while injecting, **we capture our own injected audio and send it back** — an echo loop. `VOICE_DOWNLINK` alone avoids this and is the correct source where the device supports it. Their `voiceDownlinkWorks` flag exists for exactly this reason.

### 1.5 Echo — unsolved in their design, and now our problem

Two source comments:

> No platform AEC — AudioTrack is on `USAGE_MEDIA` (different stream from AudioRecord), so platform AEC can't reference it anyway. For `VOICE_DOWNLINK`, AEC was over-cancelling (capRMS dropped from 252 to ~5). **Echo cancellation is handled by Asterisk on the server side.**

So: platform AEC is structurally unavailable here (the two streams are unrelated as far as the framework is concerned), and they offload the real work to Asterisk.

Their stopgap is software gating in the device profile: `noiseGateThreshold` (below this = modem DSP noise, send silence), `echoGateThreshold` (above this = playback active), and `doubleTalkRatio` (capture must exceed expected echo × 1.5 to count as barge-in). That's a half-duplex-ish gate, not cancellation — it works for an AI agent that mostly talks in turns.

**We have no Asterisk, and our users are humans who interrupt each other.** So AEC lands on us. Options, in order of preference:

1. **Capture `VOICE_DOWNLINK` only** — removes the loop at the source. First thing to try.
2. **AEC on the iOS/Android client** — `.voiceChat` / `AVAudioEngine` voice processing handles the *client's* local acoustic echo (its speaker into its mic) natively. This is needed regardless.
3. **Software AEC on the host** referencing our own injected buffer — we know exactly what we injected, so a WebRTC AEC3 module with our playback as the reference signal is tractable. More work, but this is the right answer if 1 and 2 aren't enough.

**This replaces uplink injection as the project's leading technical risk.** Add it to M0's success criteria: not just "can the far end hear us," but "can both parties talk without echo."

### 1.6 Magisk module — verified contents

**`system.prop`:**
```
# Qualcomm — allow concurrent audio during GSM calls
voice.record.conc.disabled=false
voice.voip.conc.disabled=false
voice.playback.conc.disabled=false

# Disable Qualcomm Fluence noise/echo processing
ro.qc.sdk.audio.fluencetype=none
persist.audio.fluence.voicerec=false
persist.audio.fluence.speaker=false
persist.audio.fluence.voicecall=false

# Samsung Exynos
persist.audio.call_record.enabled=true
```

The **Fluence** lines are new information — Qualcomm's voice DSP processing interferes with capture and injection and must be disabled. Not something you'd guess.

**Privileged permissions** (`privapp-permissions-gateway.xml`) — a longer list than the stack doc assumed:

```
CAPTURE_AUDIO_OUTPUT
READ_PRIVILEGED_PHONE_STATE
READ_PRECISE_PHONE_STATE
MODIFY_PHONE_STATE
CALL_PRIVILEGED
READ_LOGS
WRITE_SECURE_SETTINGS
INTERACT_ACROSS_USERS
REGISTER_CALL_PROVIDER
REGISTER_SIM_SUBSCRIPTION
BIND_INCALL_SERVICE
BIND_TELECOM_CONNECTION_SERVICE
```

### 1.7 ⚠️ The `PermissionController` problem — and why we should not copy their fix

`appops RECORD_AUDIO` keeps getting reset to `MODE_FOREGROUND`, which kills background capture. Their documented failures: `killall` (auto-restarts in ~3s), `appops set --uid` (overridden immediately), `pm disable-user` (Android still starts it for service binding), launching an activity (can't reach TOP with the screen locked).

Their solution is to **hide PermissionController's APK entirely** via a Magisk overlay (`system/priv-app/PermissionController/.replace`), so it never runs and appops stay as set. Then force `appops set --uid <pkg> RECORD_AUDIO allow`, with a background re-assertion loop if it reverts.

**This is a heavy hack with real collateral damage** — the host phone loses its permission-management UI entirely, for every app. Defensible on a headless gateway; hostile on a device a user still holds.

**Prefer the lighter path they also implement:** re-assert appops synchronously right before opening `AudioRecord`. Their `RtpSession` comment records why — asynchronous re-assertion "caused a race: AudioRecord started reading silence (denied) before" the grant landed. Try that alone before neutering PermissionController.

### 1.8 Other operational gotchas worth their weight

| Gotcha | Detail |
|---|---|
| **Cold-boot capture failure** | AudioFlinger refuses to create record tracks for **10–30+ seconds** after a voice call starts on cold boot ("could not create record track, status: -1"). The HAL needs time after the modem voice path starts. Needs a retry loop. |
| **Speaker mode mandatory** | `requireSpeakerMode = true` on every profile. On the S10e in earpiece mode, **all** capture sources returned RMS = 0. So the call must be in speaker mode — then the speaker must be muted at mixer level, which on Exynos was impossible. |
| **Restore or the next call breaks** | `'Voice Tx Device Mute'` must be restored to 0 on call end or, in their words, the "second call never answered". Also reset the `Incall_Music` mixers — the HAL doesn't. |
| **Ordering** | Enable `incall_music` *after* capture is established, not during bridge setup, so `AudioRecord` can lock its PCM device before the HAL re-routes. Plus a 500–700 ms delay after speaker route change. |
| **`tinymix` placement** | Deploy to `/data/local/tmp/` (permissive SELinux) — `/system/bin` via Magisk overlay hits SELinux denials. Their bundled binaries are ARM64 only. |
| **Dev loop** | `service.sh` copies the APK from `/data/app` into the priv-app overlay on boot, so `adb install -r` + reboot works during development. Worth stealing on day one. |

### 1.9 Their architecture, for reference

```
GsmCallService / CallConnectionService   InCallService, call control
GsmCallManager                           call lifecycle
CallOrchestrator                         bridges GSM ↔ SIP
RtpSession                               AudioRecord/AudioTrack + RTP
G722Codec                                codec
SipClient / SipCall / SipAuth / SipMessage
RootShell                                su + tinymix
DeviceProfile                            per-chipset audio config
GatewayService / BootReceiver            foreground service, boot
StunClient                               NAT traversal
```

Our shape is the same with `RtpSession` → an L2CAP/Opus transport and the SIP layer deleted.

**Device detection** is on `Build.HARDWARE` / `Build.BOARD` / `Build.MODEL`, falling back through generic Qualcomm → generic Exynos → generic (no mixer hacks, Android APIs only). Sensible; copy it.

---

## 2. `chenxiaolong/BCR` — capture reference

Uses `CAPTURE_AUDIO_OUTPUT` because `VOICE_CALL`, `VOICE_DOWNLINK` and `VOICE_UPLINK` cannot be accessed without that system permission, plus `CONTROL_INCALL_EXPERIENCE` so telephony binds its `InCallService` directly — letting the service foreground itself during a call and reach the audio stream without hitting Android 12+ background-mic limits. Pulls PCM s16le.

Note `gsm2sip` did **not** use `CONTROL_INCALL_EXPERIENCE` (they take the default-dialer role instead). BCR's approach is cleaner and preserves the user's dialer. Worth combining: BCR's privilege binding with gsm2sip's audio routing.

---

## 3. The three shipping architectures — and what they share

Structural finding. Four organisations relay cellular call audio or its equivalent to a second device. **None route audio through an application.**

### 3.1 Google — Wear OS: the watch is a Bluetooth headset

Google's docs: you can use the smartwatch like a Bluetooth headset, enabling *Play phone voice call on watch* under Connectivity → Bluetooth to route audio there.

AOSP confirms it at framework level — a Telecom commit filtering implicit routing notes that when Bluetooth is supported Telecom auto-routes to an available device, so a paired watch receives call audio unrequested, and that wearables are identifiable by `BluetoothClass.Device.WEARABLE_WRIST_WATCH` / `BluetoothDevice.DEVICE_TYPE_WATCH`. Corroborating: you can't use a Bluetooth headset paired to the watch during calls — single HFP chain.

Google controls both OSes and still used HFP.

### 3.2 Microsoft — Phone Link: control over IP, audio over HFP

With Phone Link, the PC handles the call audio while the phone relays the connection, and the PC must support **HFP** — the same profile a Bluetooth headset uses. Bluetooth carries live audio; the app connection carries controls, notifications and sync.

Their known pain points preview our support burden: HFP depends on adapter and driver support, and a headset can hold only one HFP connection at a time, so a headset already paired to the phone can't also serve the PC's call audio.

### 3.3 IKOS — own firmware

SIM in their hardware, PCM read off the baseband, custom BLE profile to an **unprivileged** iOS app.

### 3.4 Samsung CMC — not audio relay at all

Call & Message Continuity syncs calls and messages across Samsung devices, with **devices sharing data over the internet** regardless of distance — not a Bluetooth range-limited relay. It's also **carrier-dependent** (users report AT&T unsupported, carrier-specific failures elsewhere).

Internet-based operation on WiFi-only tablets plus carrier dependency points to **carrier IMS multi-device provisioning** — the operator registering a second endpoint on the same MSISDN (the T-Mobile DIGITS / Verizon NumberShare / Apple Watch cellular pattern). Samsung didn't relay audio; they got the carrier to deliver the call twice.

### 3.5 Conclusion

| Who | Control | Audio | Why they could |
|---|---|---|---|
| Google (Wear OS) | Companion protocol | Bluetooth HFP | Builds the client OS |
| Microsoft (Phone Link) | App over IP | Bluetooth HFP | Builds the client OS |
| IKOS | Custom BLE | Own firmware → baseband | Builds the firmware |
| Samsung (CMC) | Cloud/account | Carrier IMS — no relay | Carrier relationship |
| **gsm2sip** | `InCallService` | **Root + vendor mixer hacks** | Rooted the host |
| **Our app-only design** | App over BLE | Blocked | — |

Everyone who succeeds owns the client OS, the firmware, the carrier — **or roots the host.** `gsm2sip` is the existence proof that the fifth row works. That's why root is not a shortcut we chose; it's the only app-layer door.

---

## 4. Android as a Bluetooth headset — definitively dead

Multiple independent teams built custom ROMs with `profile_supported_hfpclient` enabled:

- One rebuilt and flashed, wrote a `BluetoothHeadsetClient` test app: could dial and accept calls, **but no voice — no speaker sound, no mic audio over SCO**
- Another backporting to Android 4.2.2: dial/accept/terminate worked, **no voice**
- A third on Nexus shamu (Nougat) with A2DP sink, HFP client and `AUDIO_FEATURE_ENABLED_HFP` in the device makefile: **no sound during calls**
- ODROID C2: profile discovered, needed `lbdroid/HFPClient` (reflection) for control

**Control works with a custom ROM. SCO audio does not**, across a decade and multiple SoCs. Rejected-approach R4 upgrades from "not compiled in" to "does not work when compiled in." Also relevant: a current XDA thread finds an Android head unit hardcoded HF-only inside `libbluetooth_qti.so`, not controllable via sysprops or `config.xml`. Bluetooth roles live in vendor stacks.

---

## 5. Revised M0

Because someone else published the discovery, M0 is now mostly **verification** rather than exploration.

| Step | Action | Cost |
|---|---|---|
| **0** | Clone `gsm2sip`; read `DeviceProfile.kt`, `RtpSession.kt`, `magisk/`. Build their source-probe screen as our harness. | 1 day |
| **1** | Apply Magisk pattern: concurrency + Fluence props, privapp-permissions, tinymix to `/data/local/tmp/`. Reboot. | Hours |
| **2** | Capture ladder: probe every source, log RMS. Confirm which work and at what rate. | Hours |
| **3** | Injection: `incall_music_enabled=true` + `Incall_Music Audio Mixer MultiMedia1/2` + `AudioTrack` on `USAGE_MEDIA`, **deep-buffer (never LOW_LATENCY)**. Verify far end hears it. | Hours–days |
| **4** | **Echo test.** Both parties talk simultaneously. Try `VOICE_DOWNLINK`-only capture first. | Days |
| **5** | Latency measurement. Deep-buffer playback will cost more than the stack doc assumed. | Hours |
| **6** | Speaker mute at mixer level, host silent with screen dark. | Hours–days |

**Revised M0: 30–70 hours → 2–4 weeks at 20h/week.** Success criteria expanded: bidirectional audio **plus** acceptable echo **plus** measured latency **plus** a silent host. "The far end can hear me" is necessary, not sufficient.

**Device choice:** a Qualcomm target remains right — Exynos needs the NSRC hack and has an unmutable speaker. Their `genericQualcomm()` profile is our starting point. A Snapdragon device with an active LineageOS port and published `mixer_paths.xml` is ideal.

---

## 6. New API findings

### 6.1 `MANAGE_ONGOING_CALLS` — `InCallService` without the dialer role

A third-party companion app can access `InCallService` by declaring `MANAGE_ONGOING_CALLS`, associating with a physical wearable device via `CompanionDeviceManager`, and implementing `InCallService` with `BIND_INCALL_SERVICE`. The permission comes from that association; `TelecomManager.hasManageOngoingCallsPermission()` checks it. CDM assigns a device profile role (for example, watch) and grants that profile's permissions after user consent.

**Why it matters:** both the stack doc and `gsm2sip` assume `ROLE_DIALER`, which hijacks the user's dialer. This is an officially sanctioned companion-device path to the same APIs — and our host app *is* a companion app. BCR's `CONTROL_INCALL_EXPERIENCE` is a third option (privileged, no dialer takeover).

**Spike during M1:** can the client phone be associated under the watch profile? Half a day, materially better product if it works.

### 6.2 `setAudioRoute` is deprecated

`InCallService.setAudioRoute(int)` and `Connection.setAudioRoute(int)` are deprecated in favour of `requestCallEndpointChange(CallEndpoint, Executor, OutcomeReceiver)`, with routes enumerated via `onAvailableCallEndpointsChanged(List)`. Jetpack **Core-Telecom** (`CallsManager`, `CallAttributesCompat`) is the modern wrapper. For BLE audio on Android 13+, `AudioManager.setCommunicationDevice()`.

### 6.3 `MEDIA_ROUTING_CONTROL` (Android 15)

Companion apps targeting Android 15 can request `MEDIA_ROUTING_CONTROL` to change where the phone outputs audio, including audio from other apps. Media routing, not call audio — doesn't solve our problem, but confirms the trend: Android opens *routing* control to companion apps while keeping *stream access* closed.

---

## 7. SMS relay — solved space

| Project | Notes |
|---|---|
| **`ndhunju/Relay`** | Closest in intent — relays calls and messages child→parent device, for travellers keeping a home-country number. Compose, Firestore, WorkManager. **Calls marked WIP.** |
| `nyaruka/android-sms-relay` | Phone as SMS modem over HTTP, DB-backed |
| `smskit/smskit` | Self-hosted gateway, PHP + Android |
| Somleng | Twilio-compatible open-source gateway |
| `koocyton/sms_forwarder_pro` | SMS → Telegram/Discord/webhook/email |
| `ihciah/AndroidSMSRelay` | Rooted, adb → Telegram |

`ndhunju/Relay` shipped messages and left calls WIP — the exact difficulty gradient this R&D mapped. Worth skimming for control-plane and pairing UX.

---

## 8. Corrections to the other documents

| Doc | Section | Change |
|---|---|---|
| Stack decisions | §1, §2.1 | **tinyalsa via JNI (C) → RootShell + `tinymix` + Java `AudioRecord`/`AudioTrack`.** No native audio library needed. |
| Stack decisions | §2.1 | Add: never `PERFORMANCE_MODE_LOW_LATENCY`; deep-buffer mandatory |
| Stack decisions | §2.2 | Add per-device profile pattern; capture-source fallback ladder |
| Stack decisions | §2.3 | Add Fluence props, full privileged permission list, appops re-assertion, tinymix placement |
| Stack decisions | §2.4 | Add `MANAGE_ONGOING_CALLS` and `CONTROL_INCALL_EXPERIENCE` as alternatives to `ROLE_DIALER` |
| Stack decisions | §4.4 | Latency budget understated — deep-buffer playback; re-measure |
| Stack decisions | §5, new | **AEC is now ours to solve.** Client-side voice processing plus possibly host-side AEC3 with our injected buffer as reference |
| Stack decisions | §9.1 | `BluetoothRouteStrategy` should use `requestCallEndpointChange`, not `setAudioRoute` |
| R&D brief | §6.3.2 | Uplink injection is solved-in-principle on Qualcomm via `Incall_Music`; no longer "the make-or-break unknown" |
| R&D brief | §8.2 | Demote; promote echo/duplex quality to lead risk |

---

## 9. Updated risk picture

**Reduced:**
- Uplink injection — demonstrated in shipping open-source code, digital not acoustic
- Implementation complexity — no C/JNI; shell + Java APIs
- M0 duration — 6–11 weeks → 2–4 weeks
- Magisk packaging — verified template
- Control-plane UX — `MANAGE_ONGOING_CALLS` may avoid dialer takeover

**New or elevated:**
- **Echo/duplex quality is now the lead technical risk.** Platform AEC is structurally unavailable on this path; their fix was a server we don't have. Human conversation needs real duplex, not turn-taking gates.
- **Speaker mode is mandatory**, so the host must be muted at mixer level — proven impossible on at least one Exynos firmware. Verify on our target.
- **Latency likely worse than budgeted** — deep-buffer playback is forced.
- **Per-device profiles are unavoidable.** Not "Android support," a device list.
- **Cold-boot and appops races** need defensive engineering from day one.

**Unchanged:**
- Emergency calls (brief §8.1) unverified
- App Store review risk for the iOS client
- Root narrows the market

---

## 10. Immediate next actions

1. Clone `gsm2sip`. Read `DeviceProfile.kt` (the whole per-chipset recipe), `RtpSession.kt` (audio config and comments), `magisk/` (props, permissions, boot scripts).
2. Port their `MainActivity` source-probe screen as our M0 harness before writing anything else.
3. Buy a Snapdragon target with an active LineageOS port and published `mixer_paths.xml`.
4. Read `BCR` for `CONTROL_INCALL_EXPERIENCE` — cleaner than dialer takeover.
5. Design the echo strategy before M2. Start with `VOICE_DOWNLINK`-only capture.
6. Apply §8 corrections to the other two documents.
7. Spike `MANAGE_ONGOING_CALLS` + `CompanionDeviceManager` in M1.

---

## 11. Source index

| Source | Relevance |
|---|---|
| https://github.com/pulpoff/gsm2sip | **Critical, source-read.** Working rooted-Android GSM↔IP audio bridge with per-chipset recipes |
| — `DeviceProfile.kt` | Mixer controls, HAL params, volume calibration, per-SoC profiles |
| — `rtp/RtpSession.kt` | AudioRecord/AudioTrack config, LOW_LATENCY warning, AEC notes |
| — `magisk/system.prop` | Concurrency + Fluence properties |
| — `magisk/service.sh` | appops forcing, PermissionController hiding, tinymix deployment |
| https://github.com/chenxiaolong/BCR | Privileged capture; `CONTROL_INCALL_EXPERIENCE` binding |
| https://github.com/ndhunju/Relay | Closest intent match; calls WIP |
| https://github.com/lbdroid/HFPClient | Reflection HFP-HF control (audio non-functional) |
| https://groups.google.com/g/android-platform/c/zQVDRlQt4DQ | HFP-HF SCO audio failures |
| android.googlesource.com — Telecomm `9e94241eb` | Watches as Bluetooth audio routes |
| android.googlesource.com — marlin/bramble/qcom-audio | `incall_music_uplink`, `VOC_REC_UL/DL` |
| developer.android.com — telecom/dialer-app | `MANAGE_ONGOING_CALLS` companion path |
| developer.android.com — telecom/voip-app | Jetpack Core-Telecom |
| source.android.com — companion-device-profile | CDM profiles and permission grants |
| support.google.com/wearos | Wear OS call audio = Bluetooth headset |
| Microsoft Learn / Q&A — Phone Link | Control over IP, audio over HFP |
| samsung.com — Call & Message Continuity | Internet-based, carrier-dependent → IMS multi-device |
