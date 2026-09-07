# SIM Relay Project — R&D Handoff Brief

**Version:** 3.0 — revised after source-reading `pulpoff/gsm2sip` and independent review (Sept 2026)
**Date:** 2026-09-03
**Owner:** Uzair Ullah
**Supersedes:** `sim-relay-architecture.md` (that doc covers §6 of this one in more depth; this brief is the complete picture)

---

## 0. How to use this document

This is a **handoff brief for an agent or engineer picking up cold.** It records the full R&D pass: what the product is, what was investigated, what is settled, what is dead, and what is still unknown.

Three conventions used throughout:

- **[VERIFIED]** — confirmed against a primary source or platform documentation
- **[ESTIMATE]** — engineering judgement, ±30%, needs a real quote or test
- **[UNVERIFIED]** — believed true, explicitly not yet confirmed, must be tested

**Read §5 (Rejected Approaches) before proposing anything.** Several intuitive designs are provably dead and were already worked through. Re-proposing them wastes cycles.

**Nothing has been built. No decision is final.** The single recommended next action is §9 (M0 spike).

---

## 1. Problem context

### 1.1 Pakistan's DIRBS regime [VERIFIED]

PTA operates the Device Identification, Registration and Blocking System. A handset's IMEI must be registered (and duty paid) or its cellular service is blocked, typically ~60 days after first SIM use. Blocked handsets retain WiFi but lose all cellular connectivity.

Registration tax scales with device value and is substantial for flagship phones — which is why a large secondary market of "non-PTA" devices exists, mostly imported iPhones.

**Critical property: DIRBS blocks by IMEI, not by SIM.** This single fact determines the entire solution space.

### 1.2 The incumbent: IKOS

UAE-based vendor (ikosuae.com) selling BLE SIM-adapter dongles into Pakistan. Models K1S (single SIM), K6 (dual SIM, 3G), K7 / K7s (dual SIM, 4G).

**Retail pricing in Pakistan [VERIFIED]** — PKR 24,899–34,999 (~$89–125 USD) across multiple retailers (dynamiteaccessories.pk, dynsol.pk, mega.pk). Sold via a reseller network.

**How it works [VERIFIED via multiple sources]:**

1. SIM(s) go into the IKOS dongle, not the phone. Dongle has its own modem and its own IMEI.
2. Dongle pairs to the iPhone over Bluetooth; a companion app (Simplus, or rebrands) runs on the phone.
3. **Calls:** user dials in the app → command goes to the dongle over BLE → dongle places the call on the cellular network → voice audio is relayed back over BLE. The network sees the dongle's IMEI. The iPhone's blocked IMEI never touches the radio.
4. **SMS:** received on the dongle, displayed in the app's own inbox. Not integrated with iOS Messages — **no OTP autofill**.
5. **Data:** dongle's 4G shared to the phone via Bluetooth tethering (and per some listings, WiFi hotspot).
6. Doubles as a power bank; some models offer call recording (which iOS blocks natively).

**Why IKOS can do this easily, and we can't:** they own the firmware. The SIM is in their hardware, so they read PCM directly off the baseband. No OS permission model, no HAL to patch. **Their iPhone app is completely unprivileged** — ordinary mic permission and `AVAudioSession`. This is the key insight for our client-side design.

**One IKOS feature not to copy [UNVERIFIED, but advertised]:** at least one retailer states the K7 offers customisable IMEI via in-app settings. If accurate that is IMEI spoofing — legally far worse exposure than a device using legitimately TAC-allocated IMEIs. Any product we build must use real vendor-allocated IMEIs.

### 1.3 Regulatory posture [UNVERIFIED — needs legal review]

Multiple sources describe BLE bypass devices as illegal in Pakistan and report PTA signalling intent to crack down. Treat as a live business risk, not settled fact. It has different implications per path:

- **Hardware path:** a ban strands physical inventory. Unrecoverable capital.
- **App path:** a ban means pulling or pivoting an app. Recoverable.

This asymmetry is a major input to the path decision.

---

## 2. Product concept

Two phones, one app, two roles.

- **HOST** — Android phone with a live PTA-registered SIM. Performs all cellular work. Expected to sit in a bag or pocket, screen dark.
- **CLIENT** — the non-PTA device (usually iPhone) whose IMEI is blocked.

**Goal:** the client behaves, to its user, as though the SIM were physically inside it. Calls ring there, are answered and *spoken* there. SMS and OTPs arrive there. Dialling originates there.

**Stated assumption from the product owner:** target users always already own both devices. The "you need a second phone" objection is accepted and out of scope for debate.

**Product frame (per independent review):** manage this as a **hardware/firmware compatibility program**, not a generic Android application. The shippable promise is *"supported on these qualified host models and firmware versions"* — not *"works on Android."* Ship compatibility, not hope.

### 2.1 Scope

**In scope:** voice (both directions), SMS/OTP, call control, call log, contacts, dual-SIM handling.

**Explicitly descoped by owner:** RCS, MMS, SIM Toolkit menus, interactive multi-step USSD. These stay on the host and are accepted losses.

**Handled outside the relay:** cellular data — ordinary hotspot / BT PAN tethering, needs no app support.

---

## 3. Capability matrix — what a SIM does, and what we can relay

| Capability | Verdict | Mechanism |
|---|---|---|
| Network identity/auth (IMSI, Ki, attach) | ✅ Host only | Never needs to leave host |
| Incoming voice | ⚠️ Blocked → §6.3 | Requires privileged audio access |
| Outgoing voice | ⚠️ Blocked → §6.3 | Control is fine; audio is the issue |
| Call control (answer, reject, hold, mute, DTMF) | ✅ No root | `InCallService` |
| Caller ID | ✅ No root | `InCallService` |
| Outbound dial with correct CLI | ✅ No root | `TelecomManager.placeCall` |
| SMS receive incl. OTP | ✅ No root | `ROLE_SMS` / `RECEIVE_SMS` |
| SMS send with correct sender | ✅ No root | `SmsManager` |
| Voicemail | ✅ | It's just a call to a number |
| Call waiting / forwarding / barring | ✅ | USSD or `TelephonyManager` |
| Single-shot USSD (`*123#` balance) | ⚠️ Partial | `sendUssdRequest` (API 26+); nested menus unreliable |
| SIM Toolkit menus | ❌ Descoped | Rendered by system `com.android.stk` |
| MMS | ❌ Descoped | |
| RCS | ❌ Descoped | No third-party API |
| SIM phonebook (ADN) | ✅ | ICC content provider |
| Signal strength / operator display | ✅ | `TelephonyManager` (cosmetic but expected) |
| Dual SIM | ✅ | Track `subscriptionId` per action |
| **Emergency calls** | ❓ **See §8.1** | Unresolved safety item |
| Cellular data | — | Out of relay scope; tethering |

**Summary: ~90% of SIM function is relayable. The blocker is voice audio, and only on the host side.**

---

## 4. Platform roles — why the host must be Android

| Capability | Android host | iOS host |
|---|---|---|
| Read incoming SMS | `ROLE_SMS` / `RECEIVE_SMS` | **No API exists** |
| Send SMS programmatically | `SmsManager` | **No API exists** |
| Observe & control calls | `ROLE_DIALER` → `InCallService` | **No API exists** |
| Place calls | `TelecomManager.placeCall` | `tel:` URL only (leaves app) |
| Access call audio | Blocked, reachable with privilege | **No path at all** |

iOS-as-host is not difficult — the APIs do not exist in public or entitled form.

**Supported matrix:**

| Host → Client | Verdict |
|---|---|
| Android → iOS | ✅ Primary product |
| Android → Android | ✅ Secondary |
| iOS → Android | ❌ Permanently impossible |
| iOS → iOS | ❌ Permanently impossible |

The original four-way "universal app" goal is **not achievable**. Host is always Android.

---

## 5. Rejected approaches — do not re-propose

Each of these was worked through and is dead for the stated reason.

| # | Approach | Why dead |
|---|---|---|
| R1 | eSIM provisioned on the client | DIRBS blocks by IMEI. SIM identity irrelevant. |
| R2 | **Bluetooth SAP** (host shares SIM credentials, client's modem registers) | The client would register using its own blocked IMEI. This is the most commonly proposed "obvious" answer and it fails on the core constraint. |
| R3 | Client registers on carrier IMS / VoWiFi | Requires carrier ISIM credentials; IMS registration reports IMEI. |
| R4 | **Client acts as a Bluetooth headset (HFP Hands-Free role)** so the host's own stack routes SCO audio to it | iOS third-party apps cannot implement HFP HF — `CoreBluetooth` is BLE/GATT only; classic audio profiles are OS-owned with no entitlement. On Android, HFP-client is not compiled into stock ROMs (`profile_supported_hfpclient`) and `BluetoothHeadsetClient` is a system API. **Note: this is the mechanically correct idea — it's blocked by platform policy, not by physics.** |
| R5 | Accessibility service to capture call audio | Google explicitly prohibited this and purged such apps in 2022. Also a Play policy violation. |
| R6 | Acoustic loopback (host on speakerphone, client mic picks up) | Requires devices adjacent, echo, half-duplex. Defeats the purpose. |
| R7 | `MediaProjection` / `AudioPlaybackCapture` | `USAGE_VOICE_COMMUNICATION` is excluded from capture by design. |
| R8 | iOS as host | §4. No APIs. |
| R9 | Sell hardware at cost + monthly subscription | §7.4. Worsens first-run cash position, and nothing enforces the subscription since the dongle works standalone. |

**Generalised rule:** any design where the non-PTA device performs its own cellular radio registration is dead. Any design requiring an unprivileged Android app to access voice-call PCM is dead. Everything else is negotiable.

---

## 6. PATH A — App-only architecture

Owner's preferred direction. Requires a **rooted host**; client stays stock.

### 6.1 Three planes

Build and de-risk these separately — the difficulty profiles are completely different.

| Plane | Carries | Difficulty |
|---|---|---|
| **Control** | Call state, caller ID, answer/reject/dial, DTMF | Solved. Public APIs. |
| **Messaging** | SMS in/out, OTPs, call log, contacts | Solved. Public APIs. |
| **Media** | Voice audio, both directions | **The entire project's risk** |

### 6.2 Host control + messaging plane — no root needed

Acquire `RoleManager.ROLE_DIALER` (single user dialog). Binds our `InCallService`:

- Call lifecycle callbacks; caller number/ID; `subscriptionId` per call
- `Call.answer()`, `.reject()`, `.disconnect()`, `.hold()`, `.playDtmfTone()`
- Outbound via `TelecomManager.placeCall()` — **callee sees the real MSISDN**, no CLI problem
- **We own the in-call UI** — rendering nothing keeps the host screen dark and silent during a relayed call, satisfying the "don't show on host" requirement

**Better alternative given we're already taking privileged permissions:** `CONTROL_INCALL_EXPERIENCE` (privileged) lets telephony bind our `InCallService` *without* us being the default dialer, and lets the service foreground itself during a call, sidestepping Android 12+ background-mic restrictions. Reference implementation: **BCR — https://github.com/chenxiaolong/BCR** (read this before starting).

Messaging: `ROLE_SMS` or `RECEIVE_SMS` receiver inbound; `SmsManager.sendTextMessage()` outbound on the chosen subscription.

⚠️ **Play Store policy:** SMS/Call-Log permissions are restricted to an approved list of core use cases. "Cross-device relay" is not obviously on it. Verify early — possibly moot if the host app is sideloaded onto a rooted device anyway.

### 6.3 Host media plane — THE hard problem

**Two halves of very unequal difficulty. Do not conflate them.** This is the most common analytical error on this project.

#### 6.3.1 Downlink capture (remote voice → client) — hard, but known territory

`AudioRecord` with `AudioSource.VOICE_CALL` / `VOICE_DOWNLINK` requires `android.permission.CAPTURE_AUDIO_OUTPUT` — `signature|privileged`. [VERIFIED] Third-party apps cannot hold it; enforced since Android 6 as a deliberate privacy decision.

Routes in, on a rooted host:

1. Magisk module placing a priv-app APK in `/system/priv-app` + `privapp-permissions` XML whitelist + SELinux policy adjustment
2. Platform-signed build (needs vendor key — OEM relationship only)
3. Direct HAL / tinyalsa capture of the voice-call PCM device, bypassing the framework

⚠️ **Portability:** `VOICE_CALL` capture is **not CDD-mandated**. Works commonly on Qualcomm; unreliable on some MediaTek and Samsung builds. **We must target specific device models. "Supports Android" is not a claim we can make.**

#### 6.3.2 Uplink injection (client mic → into the live call)

**⚠️ REVISED in v2 — this is no longer the make-or-break unknown.**

The original assessment was that no mechanism existed and a custom audio HAL would be needed. Source-reading `pulpoff/gsm2sip` shows **Qualcomm has an official digital injection path** and that it works from ordinary Java APIs on a rooted device:

1. `AudioManager.setParameters("incall_music_enabled=true")`
2. `tinymix 'Incall_Music Audio Mixer MultiMedia1' 1` (and `MultiMedia2`)
3. `AudioTrack` on `AudioAttributes.USAGE_MEDIA`

Their source confirms it is **digital injection into the modem uplink with no acoustic speaker→mic path**. No HAL patching, no Ghidra, no C library.

🚨 **Critical trap:** never use `PERFORMANCE_MODE_LOW_LATENCY` — it routes to MultiMedia5, which has no `Incall_Music` mixer, so audio plays locally and the far end hears silence with no error anywhere.

**Exynos has no `Incall_Music` mixer** and needs an ABOX NSRC bridge reroute instead (both NSRC0 and NSRC1 → SIFS0), with an unmutable speaker on at least one firmware. Treat Qualcomm as the primary target.

See `sim-relay-prior-art-research.md` §1 for the verified mechanism, exact controls, and the full gotcha list. See `sim-relay-stack-decisions.md` §2.1 for the implementation decision.

**The leading risk is now echo/duplex quality (§6.3.3), not injection.**

#### 6.3.3 🚨 Echo cancellation — the new leading risk

`gsm2sip` does not solve this. Platform AEC is structurally unavailable on this path: `AudioTrack` sits on `USAGE_MEDIA` while `AudioRecord` sits on a telephony source, so the framework AEC has no reference signal. They offload echo cancellation to Asterisk and fall back to software noise/echo gates with a double-talk ratio — turn-taking behaviour that suits an AI agent, not two humans interrupting each other.

**We have no server.** Strategy, in order:

1. **Capture `VOICE_DOWNLINK` only.** `VOICE_CALL` mixes both directions, so capturing it while injecting means capturing our own injection and sending it back. `VOICE_DOWNLINK` removes the loop structurally. Device support varies — carry a per-profile flag.
2. **Client-side platform voice processing** (iOS `.voiceChat`, Android `AcousticEchoCanceler`) for the client's own local echo. Required regardless.
3. **Host-side AEC** with our injected buffer as the reference signal (WebRTC AEC3). The correct answer if 1 and 2 fall short.

**M0 must test this,** not just whether audio crosses.

### 6.4 Client (iOS) — nothing privileged required

**Key insight: the client is an ordinary VoIP-shaped app.** This is why IKOS ships on the App Store. We are not fighting iOS here.

- **Audio:** `AVAudioSession`, category `.playAndRecord`, mode `.voiceChat`. This gives the VoiceProcessingIO unit: hardware AEC, noise suppression, AGC. **Use it — do not write custom echo cancellation.** Without AEC the client's speaker output leaks into its mic and is sent back up the call.
- **Incoming call UI:** `CallKit` — native full-screen UI, lock-screen answer, system call log, correct audio-session priority.
- **Wake paths — implement both:**
  1. **BLE background** (`bluetooth-central` background mode) → GATT notification wakes the app → `CXProvider.reportNewIncomingCall`. Serverless, works with no internet. **Primary.** Wake latency needs empirical testing; iOS may deprioritise under memory pressure.
  2. **PushKit VoIP push** — reliable, Apple-blessed, needs backend + internet on client (available via host hotspot). **Fallback.** Hard Apple rule: a PushKit VoIP push *must* immediately result in `reportNewIncomingCall` or the app is terminated.

**iOS limitations to set expectations on now:**
- Relayed SMS lands in our app, **not** iOS Messages. No keyboard autofill, no Safari OTP autofill. Users copy-paste. IKOS has the identical limitation and survives it — but it's friction on the highest-frequency use case.
- No HFP HF role (§5 R4). Hence our own audio transport.

⚠️ **App Store risk [business, not technical]:** Apple may reject an app relaying carrier services to an unregistered device (Guideline 5.0 legal / 4.2). Positioning and marketing copy materially affect the outcome. **There is no scalable iOS sideload story — have a distribution answer before engineering completes.**

### 6.5 Client (Android)

Simpler than iOS. Self-managed `ConnectionService` for native call UI and audio focus. `AcousticEchoCanceler` / `NoiseSuppressor` on capture. Relayed SMS can be written into the system SMS provider if our app is default SMS handler on the client — closer to native than iOS permits. Foreground service for the link.

### 6.6 Transport

**Transport was never the bottleneck.** Voice is cheap; the design question is reliability and power.

Bandwidth: Opus 16 kHz, 20 ms frames, ~24 kbps/direction → ~48 kbps full duplex. Trivial for every option.

| Option | Verdict |
|---|---|
| **BLE GATT** | 100–300 kbps practical with DLE on BLE 4.2+. Enough for Opus but tight; sensitive to iOS connection-interval caps. Excellent for control/wake. |
| Bluetooth Classic SPP/L2CAP | ❌ iOS third-party apps cannot use SPP without MFi certification |
| **WiFi (host hotspot, client joins)** | Best latency and bandwidth. Client is already on this network for tethering. Plain UDP or WebRTC over LAN. |
| Cloud relay (WebRTC + TURN) | Works anywhere; +50–150 ms and per-GB cost. Fallback only. |

**Recommended: dual transport.**
- **BLE = always-on control channel.** Cheap to keep alive, wakes the iOS app, carries call state / SMS / signalling.
- **WiFi (host hotspot) = media channel.** Brought up on demand at call start.

Degrades to BLE-only audio if WiFi is unavailable.

**Latency budget** — a cellular call already costs ~150–250 ms mouth-to-ear. We add:

| Stage | Budget |
|---|---|
| Capture buffer | 20 ms |
| Encode | 20 ms |
| Transport (LAN) | 5–30 ms |
| Jitter buffer | 40–60 ms |
| Decode + playout | 20 ms |
| **Added** | **~105–150 ms** |

Target under 400 ms end-to-end. Beyond that it feels like a satellite call and users talk over each other. Keep the jitter buffer adaptive.

### 6.7 Security — non-negotiable

**This link carries live voice and banking OTPs.** An unauthenticated BLE link is an account-takeover vector.

- Out-of-band pairing via QR code establishing a shared long-term key
- E2E encryption on both channels (Noise protocol or DTLS-SRTP). **Do not rely on BLE's own pairing.**
- Client authenticates the host on every reconnect. No auto-trust of anything advertising the right service UUID.

### 6.8 Background execution reliability

Chronically underestimated; budget seriously. It is the difference between a demo and a product.

- Host: foreground service `foregroundServiceType="phoneCall"` + `FOREGROUND_SERVICE_PHONE_CALL`; battery-optimisation exemption (aggressive whitelisting possible on rooted host)
- The long tail: iOS suspension, Android Doze, OEM battery killers, BLE reconnect storms, hotspot handoff. These only surface on real devices over days of use.
- **No degraded mode exists.** Host dead / out of range / Doze-killed = client has no service at all. A dongle has the same failure mode with a far simpler surface.

---

## 7. PATH B — Own hardware (compete with IKOS directly)

### 7.1 Architecture

Voice-capable LTE module + BLE MCU + battery + SIM slots. No analog audio codec needed — take digital PCM off the modem and encode in the MCU.

### 7.2 BOM [ESTIMATE — needs LCSC/Quectel distributor quote]

| Item | @1k | @10k |
|---|---|---|
| LTE Cat.1 voice-capable module (Quectel EC200U / SIMCom A7670 class) | $12 | $9 |
| BLE MCU (nRF52840 — needs BLE 5 + DLE) | $4.50 | $3.20 |
| PMIC / charger / protection | $1.20 | $0.80 |
| Battery 2000 mAh LiPo | $2.50 | $1.90 |
| 2× nano SIM connectors | $0.70 | $0.45 |
| PCB 4-layer | $2.50 | $1.40 |
| Antennas (LTE main + diversity + BLE) | $1.50 | $0.90 |
| USB-C + ESD + passives | $2.10 | $1.40 |
| 0.96" OLED (optional) | $1.80 | $1.20 |
| Enclosure, injection moulded | $1.80 | $1.10 |
| SMT assembly + test + pack | $3.50 | $2.20 |
| **Single-SIM total** | **~$34** | **~$24** |
| **True dual-SIM (2nd modem)** | **~$46** | **~$33** |

⚠️ **Dual-SIM gotcha:** most cheap modules do dual SIM *single* standby — only one radio active, the other unreachable. True DSDS needs a second modem: the single largest cost jump. **Check what IKOS actually ships before matching the spec** — they may be selling DSSS as "dual SIM."

### 7.3 NRE [ESTIMATE]

| Item | Islamabad rates | Outsourced intl. |
|---|---|---|
| Hardware design (schematic, layout, DFM, ~3 spins) | $4–10k | $10–25k |
| Firmware | $8–20k | $25–60k |
| iOS + Android apps | $6–15k | $15–40k |
| Injection mould tooling (2-part) | $6–12k | same |
| PTA type approval + test lab | $3–8k | same |
| **Total** | **$27–65k** | **$59–145k** |

Firmware scope: AT/OpenCPU modem control, PCM voice path, Opus codec, BLE stack + custom GATT profile, power management, OTA, provisioning, SMS, USSD passthrough. ~4–7 months for one strong embedded engineer.

### 7.4 True unit cost — BOM is not unit cost

| Layer | Dual-SIM @1k | @10k |
|---|---|---|
| BOM | $46 | $33 |
| Air freight | $1.50 | $1.00 |
| Import duty + sales tax ⚠️ [UNVERIFIED — varies by HS code, changes per finance bill] | $10–16 | $7–11 |
| QC failures / RMA reserve ~4% | $2 | $1.50 |
| Retail packaging + cable | $2 | $1.30 |
| **Landed cost** | **~$62–68** | **~$44–48** |
| NRE amortised over run | **+$27–65** | +$3–7 |
| **True cost/unit** | **$90–130** | **~$50** |

**At 1,000 units NRE dominates and you are at $90–130/unit against ~$89–125 retail — roughly break-even.** At 10,000 units margins are healthy, but that's ~$500k of working capital in inventory in a category PTA may act against.

**IMEI allocation:** the device needs its own IMEIs. Own GSMA TAC ≈ $1–1.5k plus process. **Shortcut:** Quectel/SIMCom modules ship with IMEIs pre-assigned under the vendor's TAC — you inherit them and skip GSMA. **Confirm with the module vendor before design freeze.**

### 7.5 The ODM shortcut — strongly worth evaluating

IKOS is almost certainly a white-label Shenzhen ODM product with a rebranded app; the category ("Bluetooth SIM adapter ODM") has been shipping for years.

- MOQ 500–1,000
- Landed unit cost $25–45
- Custom firmware/branding: $5–15k
- **Time to first units: 2–4 months instead of 10–14**

Amortised over 500 units that's $10–30/unit of NRE instead of $27–65. **Ships working product now**, and validates whether the market is real before committing tooling budget.

### 7.6 Rejected: sell at cost + subscription (R9)

Selling at cost recovers nothing at point of sale, so every unit becomes a loan — **more** working capital needed, not less.

1,000 units at $65 cost, $3/mo, ~$2.20 net after processing/infra/support:

| Model | Cash at sale | 24-month total |
|---|---|---|
| Outright @ $100 | **+$35,000 day one** | $35,000 |
| At-cost + $3/mo, 0% churn | $0 | $52,800 |
| At-cost + $3/mo, 5% monthly churn | $0 | **~$31,000** |

At realistic churn you collect *less over two years* than an outright sale yields immediately — against NRE already spent. Underwater 18+ months on a product that may be banned meanwhile.

Three further structural problems: (a) nothing enforces the subscription since the dongle works standalone — you'd have to engineer a cloud dependency purely to have a kill switch, creating a failure mode where your server bricks someone's phone; (b) recurring payment collection in Pakistan is hard (prepaid-first market, low card-on-file penetration, JazzCash/Easypaisa mandate friction at point of purchase); (c) it creates an identified, billed customer database for a device that may be declared illegal — a liability, not an asset.

**If subscription is revisited later, the correct shape is optional and additive** — cloud call-recording backup, web SMS access, multi-device pairing. Things that genuinely need a server, that users opt into, and whose absence doesn't brick the hardware.

---

## 8. Open questions

Ordered by how much damage getting them wrong causes.

### 8.1 Emergency calls ❓ SAFETY — highest priority

The client cannot dial emergency services (15, 1122, 130) independently. Normally a SIM-less handset gets emergency-only attach and networks are generally obliged to permit it — **but a DIRBS-blocked IMEI is blocked at network level, and whether Pakistani operators permit emergency attach from a blocked IMEI is an operator-config question, not a spec question.**

The product owner's position is that this is handled by the phone itself. **That is unconfirmed and must not be assumed.**

**Action: test on a real DIRBS-blocked handset.** Design an explicit, loud fallback UX either way. This has the worst downside of any item here, and is also the hardest thing to defend if something goes wrong.

### 8.2 Echo / duplex quality (§6.3.3) — promoted

Platform AEC is unavailable on this path and the reference implementation offloads it to a server we don't have. Can two humans hold a normal, interrupting conversation? Unresolved.

### 8.2b Uplink injection viability (§6.3.2) — demoted

Was the lead risk. Now a verification task on Qualcomm rather than an open research question. Still device-specific: confirm the `Incall_Music` mixer controls exist on our target, and that the host's speaker can actually be muted (proven impossible on at least one Exynos firmware).

### 8.3 Device target list

Which one or two rooted Android models are officially supported? Voice-call audio access varies by chipset; "all Android" is not supportable.

### 8.4 Import duty rate

Second-largest line in the hardware cost model, currently [UNVERIFIED]. Needs a clearing agent's number before financial modelling.

### 8.5 Play Store SMS policy

Does our use case qualify? Does it matter if the host is sideloaded anyway?

### 8.6 App Store positioning

How is the iOS client framed to survive review?

### 8.7 Regulatory / legal review

PTA's actual position on relay apps as distinct from bypass hardware. The app path's legal footing may differ meaningfully from the dongle's — the SIM stays in a registered handset and no IMEI is spoofed. Worth a real legal opinion, as it could be a genuine differentiator.

### 8.8 Relay-down UX

No degraded mode exists. What does the client show, and how fast does it detect?

---

## 9. Recommended next action — M0 spike

**Fund M0 as a standalone 3-week decision, not as phase one of a project.**

**Scope:** one specific rooted Snapdragon handset. Questions: *can we capture downlink and inject uplink on a live cellular call; can both parties talk over each other without echo; what is the real added latency; and can we mute the host's speaker?*

**Revised cost after prior-art research: 30–70 hours (2–4 weeks at 20h/week)**, down from 80–160, because `gsm2sip` published the discovery. M0 is now largely verification and calibration rather than exploration.

### 9.0 M0 exit criteria — all seven, not just the first

Per the independent review, "the far end can hear me" is necessary and nowhere near sufficient:

1. Clean downlink capture of the remote party, with stable timing
2. Digital uplink injection from a generated PCM stream
3. **Simultaneous** capture and injection during the same live call
4. Host speaker muted **and** host physical microphone excluded from the relayed conversation
5. No destructive feedback loop when capture and injection run together
6. **Acceptable double-talk** — both parties speak naturally without severe echo or clipping
7. **Reliable teardown and correct second-call behaviour** after mixer/routing state is restored

Criterion 7 is easy to skip and expensive to skip: `gsm2sip`'s source records that failing to restore `Voice Tx Device Mute` meant the *second* call never answered. A prototype that works once and breaks the next call has not passed M0.

### 9.0.1 Qualification suite — after first success, before building on it

- ≥100 consecutive calls
- Screen-off tests
- Reboot recovery
- 30-minute call duration
- Both incoming and outgoing
- VoLTE and fallback network modes where available
- **Multiple units of the exact same host model** — catches per-unit firmware variance before it becomes a support problem

### 9.0.2 No-go for a host model

- The modem/DSP voice path never exposes a usable injection route to the application processor
- Speaker muting cannot be achieved without breaking the voice path
- Downlink capture necessarily contains the injected uplink and can't be separated or cancelled to usable quality
- Firmware variability makes the technique unstable across identical production units

**A no-go on one handset is not a no-go for the concept.** Drop that hardware profile and evaluate another controlled target.

**Why this first:** it is the cheapest information available in the entire decision. It either kills Path A outright for ~3 weeks of cost, or converts the project's largest unknown into a known. Until it returns, "app vs hardware" is a preference. After it returns, it's arithmetic.

**Build nothing else until M0 resolves.** Choose the spike device carefully — chipset determines feasibility.

### 9.1 Milestones after M0

| # | Milestone | Exit criterion |
|---|---|---|
| **M0** | Audio spike | Bidirectional audio on a live call **plus acceptable echo behaviour under double-talk, measured latency, and a silent host**. Port `gsm2sip`'s source-probe screen as the harness. **Go/no-go.** |
| M1 | Control + messaging relay | iPhone shows caller ID, answers/rejects/dials; SMS + OTPs arrive. Audio still on host. |
| M2 | One-way audio | Remote party's voice heard on the iPhone. |
| M3 | Full duplex | Complete call conducted entirely on the iPhone. |
| M4 | Transport hardening | BLE/WiFi failover, reconnect, encryption, background reliability both OSes. |
| M5 | Productisation | Pairing UX, battery tuning, device compatibility matrix, distribution. |

**M0 and M1 parallelise** — different skill sets, no shared code. **M1 is worth building regardless of M0's outcome**: it validates pairing, transport, background execution and the entire non-audio product surface, all of which M2–M4 depend on.

### 9.2 Timeline [ESTIMATE]

**With 3 seniors (iOS, Android, embedded Linux) + backend:**

| Phase | Best | Likely | Bad |
|---|---|---|---|
| M0 | 2 wk | 3–4 wk | 6 wk |
| M1 | 4 wk | 6 wk | 8 wk |
| M2 | 2 wk | 3 wk | 5 wk |
| M3 | 3 wk | 6 wk | 16 wk |
| M4 | 5 wk | 8 wk | 12 wk |
| M5 | 4 wk | 7 wk | 10 wk |

- Working prototype (one device pair, rooted, engineers only): **3.5–5 months**
- Product a non-technical user can live on: **8–11 months**
- Multi-device + distribution sorted: **12–15 months**

**Solo across all four disciplines: 8–12 months to prototype, 18–24 months to product.** Not 3× the team estimate but more, because of the learning tax — smallest on Android (~30–40%), moderate on iOS (~80–100%), and 3–5× on the HAL work in §6.3.2, which has no tutorial, no Stack Overflow answer, and often no source for the blob being modified. That phase is where solo projects stall permanently. Solo also loses peer review on unfamiliar ground: a senior iOS dev flags a broken BLE wake strategy in ten minutes; alone it surfaces in week six.

**Variance concentrates in M3 and M4.** M3 is platform bring-up (vendor HAL reverse-engineering, SELinux, chipset ALSA routing) — notoriously unestimable until someone has the device open; the embedded engineer will have a far better number after M0. M4 is a long tail of real-device bugs that only appear over days of use.

**Not included in any figure above:** App Store review cycles, Play policy resolution, Magisk packaging for end users, device compatibility matrix, support tooling.

---

## 10. Path comparison

| Axis | Path A (app) | Path B (hardware) |
|---|---|---|
| Capital required | Near zero | $27–65k NRE + inventory |
| Time to market | 8–15 months | 2–4 months (ODM) / 10–14 (ground-up) |
| Technical risk | **One unresolved unknown (§6.3.2)** | Low — category ships today |
| Regulatory downside | Pull/pivot the app | Stranded inventory |
| Distribution control | ❌ Apple + Google can veto | ✅ Own channel + resellers |
| Addressable market | Intersection of: non-PTA iPhone owner ∩ spare Android ∩ will root it ∩ won't just buy IKOS | Anyone with a non-PTA phone |
| Proven demand | ❌ Unproven | ✅ IKOS sells at PKR 24,899–34,999 |
| Form factor | ❌ Second phone: charged, paired, in range | ✅ Dongle in a bag |
| Feature ceiling | ✅ Higher — host is a real phone with a real IP stack; can relay notifications, call logs, contacts, network status | Limited to what the board does |
| Margin at volume | Software margins | ~$50 cost vs ~$100 retail |

### 10.1 The honest tension

Path A's only real edge over IKOS is **"no hardware to buy."** But a rooted host narrows the market to people technical enough to root a phone — who are largely the same people who could just buy the dongle. **This is the one Path A weakness that engineering effort cannot fix**, and it should be confronted before M3, not after.

### 10.2 The paths converge more than they look

Path A's output isn't only an app — it's a working relay protocol plus two client apps. That is most of the software for a hardware product. Work on A is not wasted if the decision later flips to B.

---

## 11. Reference material

- **BCR (Basic Call Recorder)** — https://github.com/chenxiaolong/BCR — working reference for privileged call-audio capture on rooted Android; uses `CAPTURE_AUDIO_OUTPUT` + `CONTROL_INCALL_EXPERIENCE`. **Read before starting §6.3.**
- **IKOS** — https://ikosuae.com/ — incumbent product line (K1S / K6 / K7 / K7s)
- Retail price checks: dynamiteaccessories.pk, dynsol.pk, mega.pk
- Android `InCallService`, `TelecomManager`, `RoleManager` — developer.android.com
- Apple `CallKit`, `PushKit`, `AVAudioSession` (`.voiceChat` mode) — developer.apple.com
