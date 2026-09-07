# SIM Relay System — Technical Understanding Document

**Audience:** Senior iOS engineer, Senior Android engineer, Senior Embedded Linux engineer, Backend
**Status:** Pre-implementation feasibility. Nothing here is built yet.
**Purpose:** Establish shared understanding of what the system is, what is provably possible, what is blocked, and where the real risk sits.

---

## 1. What we are building

Two phones, one app, two roles.

- **HOST** — an Android phone holding a live, PTA-registered SIM. Does all cellular work.
- **CLIENT** — a non-PTA device (usually iPhone, sometimes Android) whose IMEI is blocked from the cellular network.

The client should behave, to its user, as though the SIM were inside it: calls ring there, are answered and spoken there, SMS and OTPs arrive there, dialing originates there.

The host should be silent and idle-looking. It sits in a bag or pocket.

### Non-goals (explicitly out of scope)

- RCS, MMS, SIM Toolkit menus, interactive multi-step USSD. Accepted losses; these stay on the host.
- Cellular data delivery is handled by ordinary tethering (hotspot / BT PAN), not by our relay.
- iOS as HOST. Not a scoping choice — it is impossible (see §3.2).

---

## 2. The constraint that determines the entire architecture

**DIRBS blocks by IMEI, not by SIM.**

The client's radio can never register on the network. Not with a physical SIM, not with an eSIM, not via VoWiFi (IMS registration reports IMEI).

Consequences — these three "obvious" designs are dead and should not be re-litigated:

| Design | Why it's dead |
|---|---|
| Put an eSIM on the client | Client IMEI blocked; SIM identity is irrelevant |
| Bluetooth SAP (host shares SIM credentials, client's modem registers) | Client would register with its own blocked IMEI |
| Client registers on carrier IMS / WiFi Calling | Requires carrier ISIM credentials + reports IMEI |

**Therefore:** the host performs 100% of radio work. The client is a pure remote endpoint. Every byte reaching the client crosses a link we build ourselves.

---

## 3. Platform capability map

### 3.1 The three planes

Think of the system as three independent planes. They have completely different difficulty profiles and should be built and de-risked separately.

| Plane | What it carries | Difficulty |
|---|---|---|
| **Control** | Call state, caller ID, answer/reject/hangup, dial, DTMF | Solved. Public APIs. |
| **Messaging** | SMS in/out, OTPs, call log, contacts | Solved. Public APIs. |
| **Media** | The actual voice audio, both directions | **The entire project's risk** |

### 3.2 Why the host must be Android

| Capability | Android host | iOS host |
|---|---|---|
| Read incoming SMS | `ROLE_SMS` / `RECEIVE_SMS` | No API exists |
| Send SMS programmatically | `SmsManager` | No API exists |
| Observe & control calls | `ROLE_DIALER` → `InCallService` | No API exists |
| Place calls | `TelecomManager.placeCall` | `tel:` URL only (leaves app, user confirms) |
| Access call audio | Blocked, but reachable with privilege | No path at all |

iOS host is not "hard." The APIs do not exist in any form, public or private-with-entitlement. **iPhone→Android and iPhone→iPhone are permanently off the table.**

Supported matrix:

- Android host → iOS client ✅ (primary product)
- Android host → Android client ✅ (secondary)

---

## 4. HOST (Android) — engineering detail

Owner: Android engineer, with the embedded Linux engineer on §4.3.

### 4.1 Control plane — no root required

Acquire `RoleManager.ROLE_DIALER` (user-grantable, one dialog). This binds our `InCallService`, which provides:

- Full call lifecycle callbacks: ringing, active, held, disconnected
- Caller number/ID, and for dual-SIM, the `subscriptionId` the call arrived on
- `Call.answer()`, `Call.reject()`, `Call.disconnect()`, `Call.hold()`, `Call.playDtmfTone()`
- Outbound via `TelecomManager.placeCall()` — the callee sees the real MSISDN, no CLI problem

Holding `ROLE_DIALER` also means **we own the in-call UI**. Rendering nothing keeps the host screen dark and silent during a relayed call. This directly satisfies the "don't show on host" requirement.

Alternative worth evaluating: `CONTROL_INCALL_EXPERIENCE` (privileged) lets telephony bind our `InCallService` *without* being the default dialer, and lets the service move itself to foreground during a call, sidestepping Android 12+ background-microphone restrictions. Since we are already taking privileged permissions for audio, this is likely the better route. See the BCR project for a working reference: https://github.com/chenxiaolong/BCR

### 4.2 Messaging plane — no root required

- Incoming: `ROLE_SMS` or a `RECEIVE_SMS` broadcast receiver. Relay payload + `subscriptionId`.
- Outgoing: `SmsManager.sendTextMessage()` on the chosen subscription. Recipient sees the real number.
- Call log and contacts via their content providers.

**Distribution caveat:** Google Play restricts SMS/Call-Log permissions to an approved list of core use cases. "Cross-device relay" is not obviously on that list. If Play distribution matters, verify against current policy early — this is a business blocker, not a technical one. (If the host app is being sideloaded onto a rooted device anyway, this may be moot.)

### 4.3 Media plane — THE hard problem

This is the only genuinely unsolved part of the system. It has **two halves of very unequal difficulty**, and they are frequently conflated. Do not conflate them.

#### 4.3.1 Downlink capture (remote party's voice → client) — hard but solved territory

`AudioRecord` / `MediaRecorder` with `AudioSource.VOICE_CALL` (or `VOICE_DOWNLINK` alone) requires `android.permission.CAPTURE_AUDIO_OUTPUT`, which is `signature|privileged`. Third-party apps cannot hold it. This has been enforced since Android 6 and was a deliberate privacy decision.

Ways in, on a rooted host:

1. **Magisk module** placing a system-signed or priv-app APK into `/system/priv-app` plus a `privapp-permissions` XML whitelist entry, with SELinux policy adjustments.
2. **Platform-signed build** — only viable with an OEM/assembler relationship (vendor key required).
3. **Direct HAL / tinyalsa capture** of the voice-call PCM device on the SoC, bypassing the Android framework entirely. Embedded Linux territory.

**Critical portability warning:** capture from `VOICE_CALL` is *not mandated by the Android CDD* and is inconsistent across chipsets and OEMs. It commonly works on Qualcomm, is unreliable on some MediaTek and Samsung builds. **We must target specific device models, not "Android."**

#### 4.3.2 Uplink injection (client's mic → into the live call) — the real risk

There is no framework API for this at all. Not blocked-by-permission — nonexistent. `AudioTrack` on `STREAM_VOICE_CALL` routes to the local speaker path, not to the modem's uplink.

The modem reads uplink audio from whatever the audio HAL presents as the active input device. To inject, we must make the HAL present *our* buffer instead of the physical microphone. Candidate approaches, all requiring root or a custom build:

- **Custom / patched audio HAL** — replace or shim `audio.primary.<platform>.so` so that during a voice call the input path reads from a virtual source we feed. Cleanest, most work, least portable.
- **HAL shim via Magisk bind-mount** — mount a wrapper library over the vendor HAL, intercepting the input stream open. Less invasive, still device-specific.
- **ALSA-level injection** — write directly to the SoC's voice uplink PCM device via tinyalsa, bypassing the HAL. Highly device-specific; depends on how the platform wires modem↔codec (some SoCs route voice entirely in the DSP and never surface it to the AP — **on those devices this approach is impossible**).
- **Vendor `AudioManager.setParameters()` hooks** — some OEMs expose undocumented loopback/test parameters. Worth a reconnaissance pass; unlikely to be a foundation.

**This single item decides the project.** If uplink injection cannot be made to work reliably on a chosen device, the product is a one-way listening device and not a phone.

**Mandatory first action:** a two-week spike on one specific rooted handset answering only: *can we simultaneously capture downlink and inject uplink on a live cellular call?* Nothing else should be built until this returns yes.

### 4.4 Background execution

- Foreground service with `foregroundServiceType="phoneCall"` + `FOREGROUND_SERVICE_PHONE_CALL`.
- Battery-optimisation exemption; on a rooted host we can whitelist aggressively via system settings.
- The host is a dedicated device — we can assume OEM battery-killer settings are configured once at setup.

### 4.5 Dual SIM

Track `subscriptionId` on every call and message, both directions. The client UI must show which SIM, and outbound must specify it.

---

## 5. CLIENT (iOS) — engineering detail

Owner: iOS engineer.

**Key realisation: nothing on the client is privileged.** This is why IKOS works with an ordinary App Store app. We are building a normal VoIP-shaped app, not fighting iOS.

### 5.1 Audio

- Playback and capture through `AVAudioSession`, category `.playAndRecord`, mode `.voiceChat`.
- `.voiceChat` gives us the VoiceProcessingIO unit: hardware AEC, noise suppression, AGC. **Use it. Do not write our own echo cancellation** — the client's speaker output will otherwise leak into its mic and be sent back up the call.
- Standard microphone permission. Nothing exotic.

### 5.2 Incoming call presentation

`CallKit` gives the native full-screen incoming-call UI, lock-screen answer, system call log integration, and correct audio-session priority.

Two wake paths, and we should probably implement both:

1. **PushKit VoIP push** — server-driven. Reliable, Apple-blessed. Requires the client to have internet (it will, via the host's hotspot) and requires backend infrastructure. Note Apple's hard rule: a PushKit VoIP push **must** result in `CXProvider.reportNewIncomingCall` immediately or the app is terminated.
2. **BLE background wake** — `bluetooth-central` background mode lets a suspended app receive GATT notifications and call `reportNewIncomingCall` from that callback. Serverless. Wake latency needs empirical testing; iOS may deprioritise under memory pressure.

Recommend: BLE as primary (no backend dependency, works with no internet), PushKit as the reliability fallback.

### 5.3 What iOS cannot do — set expectations now

- **Relayed SMS lands in our app, not in Messages.** No autofill in the keyboard, no Safari OTP autofill. Users copy-paste. IKOS has this identical limitation and survives it, but it is friction on the single highest-frequency use case.
- No HFP Hands-Free role. Classic Bluetooth audio profiles are OS-owned; `CoreBluetooth` is BLE/GATT only. This is why we build our own audio transport rather than pretending to be a headset.
- No telephony integration beyond CallKit's presentation layer.

### 5.4 App Store risk — treat as a real business risk

Apple review may reject an app whose function is relaying carrier services to an unregistered device (Guideline 5.0 legal / 4.2 minimum functionality). Positioning and marketing copy materially affect the outcome. Have a distribution answer before engineering is complete; there is no good sideload story on iOS at scale.

---

## 6. CLIENT (Android) — engineering detail

Straightforward compared to iOS.

- Self-managed `ConnectionService` for native call UI and audio-focus behaviour.
- `AcousticEchoCanceler` / `NoiseSuppressor` on the capture path.
- Relayed SMS can be written into the system SMS provider if our app is the default SMS handler on the client — closer to native than iOS allows.
- Foreground service for the link.

---

## 7. Transport layer

Owner: Backend + both mobile engineers.

**Transport was never the bottleneck.** Voice is cheap. The design question is reliability and power, not bandwidth.

### 7.1 Bandwidth reality

Opus at 16 kHz, 20 ms frames, ~24 kbps per direction. ~48 kbps full duplex plus overhead. Trivially within reach of any option below.

### 7.2 Options

| Transport | Verdict |
|---|---|
| **BLE GATT** | Practical throughput 100–300 kbps with DLE on BLE 4.2+. Enough for Opus, but tight and sensitive to iOS's connection-interval caps. Excellent for control/wake. |
| **Bluetooth Classic SPP/L2CAP** | ❌ iOS third-party apps cannot use SPP without MFi hardware certification. |
| **WiFi (host hotspot, client joins)** | Best latency and bandwidth. Host is providing tethering anyway, so the client is already on this network. Plain UDP or WebRTC over LAN. |
| **Cloud relay (WebRTC + TURN)** | Works anywhere, adds 50–150 ms and per-GB cost. Fallback only. |

### 7.3 Recommended design

**Dual transport.**

- **BLE = always-on control channel.** Cheap to keep alive, wakes the iOS app in background, carries call state, SMS, and signalling.
- **WiFi (host hotspot) = media channel.** Brought up on demand when a call starts; carries the audio.

This gives low idle power with good in-call quality, and degrades to BLE-only audio if WiFi is unavailable.

### 7.4 Latency budget

A cellular call already costs ~150–250 ms mouth-to-ear. We add:

| Stage | Budget |
|---|---|
| Capture buffer | 20 ms |
| Encode | 20 ms |
| Transport (LAN) | 5–30 ms |
| Jitter buffer | 40–60 ms |
| Decode + playout | 20 ms |
| **Added total** | **~105–150 ms** |

Target end-to-end under 400 ms. Above that it feels like a satellite call and users start talking over each other. Keep the jitter buffer adaptive and aggressive.

### 7.5 Security — non-negotiable

This link carries live voice **and banking OTPs**. An unauthenticated BLE link would be an account-takeover vector.

- Pairing via QR code out-of-band, establishing a shared long-term key.
- E2E encryption on both channels (Noise protocol or DTLS-SRTP). Do not rely on BLE's own pairing.
- Client must authenticate the host on every reconnect. No auto-trust of a device advertising the right service UUID.

---

## 8. Open questions to resolve before/during the spike

1. **Uplink injection viability** on the target SoC. Everything depends on this. (§4.3.2)
2. **Emergency calls.** A blocked IMEI cannot attach normally; whether Pakistani operators permit emergency-only attach from a DIRBS-blocked IMEI is an operator-config question we have not verified. **Test on a real blocked handset.** Design an explicit, loud fallback either way — this is the item with the worst downside if we get it wrong.
3. **Device target list.** Which one or two rooted Android models do we officially support? Voice-call audio access varies by chipset; "all Android" is not a supportable claim.
4. **Play Store SMS policy** — does our use case qualify, and does it matter if the host is sideloaded?
5. **App Store positioning** for the iOS client.
6. **Relay-down UX.** Host out of range, dead, or Doze-killed means the client has *no service at all* — no degraded mode. A dongle has the same failure mode with a far simpler surface. What does the client show, and how fast does it notice?

---

## 9. Suggested milestones

| # | Milestone | Exit criterion |
|---|---|---|
| **M0** | **Audio spike** (2 weeks) | On one rooted device: simultaneous downlink capture + uplink injection on a live call, verified by both parties hearing each other. **Go/no-go for the whole project.** |
| M1 | Control + messaging relay | iPhone shows incoming caller ID, answers/rejects/dials; SMS and OTPs arrive. Audio still on host. |
| M2 | One-way audio | Remote party's voice heard on the iPhone. |
| M3 | Full duplex | Complete call conducted entirely on the iPhone. |
| M4 | Transport hardening | BLE/WiFi failover, reconnect, encryption, background reliability on both OSes. |
| M5 | Productisation | Pairing UX, battery tuning, device compatibility matrix, distribution. |

M1 is worth building regardless of M0's outcome — it validates pairing, transport, background execution, and the entire non-audio product surface, all of which M2–M4 depend on.

---

## 10. How we compare to IKOS

**Why IKOS can do what we can't, easily:** the SIM is in *their* hardware. They own the firmware and read PCM straight off the baseband. No permission model, no HAL to patch, no OS to fight. Their iPhone app is unprivileged and ordinary — same as ours will be.

**Where we are structurally better:** our host is a real phone with a real IP stack. We can relay notifications, call logs, contacts, network status, and richer data than a dongle ever could.

**Where we are structurally worse:** form factor. A dongle in a bag beats a second phone that must be charged, paired, and in range. Our advantage has to be "no hardware to buy" — which means the root requirement on the host is in direct tension with our only real edge. Worth confronting early.
