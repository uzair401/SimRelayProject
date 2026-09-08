# SIM Relay — Non-Root Experiment Plan

**Track:** non-root experiment (`experiment/non-root-provisioning`)
**Date:** 2026-09-08
**Depends on:** `NON_ROOT_FEASIBILITY.md`
**Principle:** every experiment states in advance what conclusion it is *allowed* to support. Emulator results never become claims about production Android.

Evidence classes: `ConfirmedByAospSource`, `ConfirmedOnEmulator`, `ConfirmedOnProductionDevice`, `Inferred`, `Unknown`, `NotTested`.

---

## 0. Environment status and authorisations needed

Inspected read-only on 2026-09-08:

| Resource | State |
|---|---|
| `ANDROID_HOME` | `/home/uzair401/Android/Sdk` |
| Platforms installed | `android-36`, `android-37.0` |
| Emulator binary | present (`emulator/emulator`) |
| `cmdline-tools` | `latest` present |
| **System images** | **none installed** (`system-images/` does not exist) |
| **AVDs** | **none** |
| KVM | `/dev/kvm` present (hardware acceleration available) |
| Connected device | `R58R83A9F7N` — Device #1, Samsung SM-A225F, Android 13 / API 33 |

**Authorisation required before Experiment 2 and beyond.** Creating the emulator environment means `sdkmanager` downloading system images (roughly 1.5–3 GB each), which modifies the Android SDK installation. Per `AGENTS.md` §28 that needs explicit user approval. Requested set, smallest useful first:

```
system-images;android-34;google_apis;x86_64      # Experiment 2 (role discovery, non-writable)
system-images;android-36;google_apis;x86_64      # Experiment 2 on the newest generation
system-images;android-34;aosp_atd;x86_64         # Experiment 3 candidate (AOSP, userdebug-like)
```

Device #1 is owned by the rooted track. This plan uses it **read-only only** (property and `dumpsys` reads, no installs, no grants, no reboots, no state changes).

---

## Experiment 1 — Ordinary APK baseline

**Goal.** Establish, on real hardware, that a normally installed SIM Relay APK cannot obtain `CALL_AUDIO_INTERCEPTION`, and capture the provisioning-route diagnostic that distinguishes *why*.

**Environment.** Device #1 (Android 13 / API 33), app installed in `/data/app` as an ordinary application. Any additional stock device or emulator is equally valid.

**Steps.**
1. `git branch --show-current` → must be `experiment/non-root-provisioning`.
2. Build and install the debug APK; launch; run the capability probe.
3. Export diagnostics; read `app-probe.json`, `app-permissions.json` and the new provisioning-route fields.
4. Read-only ADB corroboration: `adb shell dumpsys package permissions | grep -A3 CALL_AUDIO_INTERCEPTION` and `adb shell cmd role get-role-holders android.app.role.SYSTEM_CALL_STREAMING`.

**Expected result.** Framework methods accessible; capability `PermissionMissing`; provisioning route reported as "privileged or platform-signature required" on API 33 (no role branch); role reported unavailable.

**Evidence collected.** Diagnostic run directory; permission protection level string; role availability/holding; API level.

**Success condition.** The app reports the correct *reason* for denial and names the provisioning route the OS actually offers, without crashing and without offering a grant affordance it cannot honour.

**Failure condition.** The app claims the permission is obtainable, offers a "Grant role" action, misreports API 33 as role-capable, or crashes.

**Legitimate conclusion.** *Only* that an ordinary install cannot obtain the permission on the tested build, and that the diagnostic classification is correct there. Says nothing about Android 14+.

**Status.** Substantially pre-confirmed: Phase 0B-2 already showed `PermissionMissing` on Device #1, and this pass added `prot=signature|privileged` (`ConfirmedOnProductionDevice`). Re-run once the prototype diagnostic is built.

---

## Experiment 2 — Android 14/15/16 role discovery on a stock emulator image

**Goal.** Determine, per Android generation, whether the role exists, whether anything holds it, and what the platform's `config_systemCallStreaming` is set to on a Google-provided image.

**Environment.** Emulator, `google_apis` x86_64 images for API 34 and API 36 (non-writable `/system`, production-like `user`-style build). Requires the download authorised in §0.

**Steps.**
1. Create AVD; boot; install the debug APK normally (no root, no remount).
2. Run the capability probe; export diagnostics. Record `isRoleAvailable`, `isRoleHeld`, API level, permission protection level, framework API accessibility.
3. `adb shell cmd role get-role-holders android.app.role.SYSTEM_CALL_STREAMING`.
4. Control query on a deliberately invalid role name, to prove the command distinguishes nothing by empty output.
5. Read the platform default-holder value without modifying the device:
   `adb pull /system/framework/framework-res.apk` then
   `aapt2 dump resources framework-res.apk | grep -A3 config_systemCallStreaming`.
6. Repeat for each API level.

**Expected result.** API 34/36: protection level includes `role`; `isRoleAvailable` true; `isRoleHeld` false; no holders; `config_systemCallStreaming` empty (matching AOSP).

**Evidence collected.** Per-API diagnostic exports; role availability; role holders; resolved default-holder string; protection level.

**Success condition.** The role is observed to exist on API 34+ and not on API 33, and the resolved default holder is observed rather than assumed.

**Failure condition.** `isRoleAvailable` false on API 34+ (would contradict `minSdkVersion="34"`), or protection level lacking `role` on API 34+ (would contradict AOSP and invalidate the whole role hypothesis).

**Legitimate conclusion.** Facts about *Google emulator images* only — `ConfirmedOnEmulator`. Retail OEM builds may set a holder or alter the declaration and must be surveyed separately (Experiment 6). Also: emulator absence of a holder does not prove retail absence.

**Status.** `NotTested` — blocked on SDK download authorisation.

---

## Experiment 3 — Configured role holder on a writable/AOSP image

**Goal.** Prove or disprove the full chain **system app + declared `CallStreamingService` + `config_systemCallStreaming` → role held → `CALL_AUDIO_INTERCEPTION` granted**, with no root at application runtime.

**Environment.** Emulator with a writable system (AOSP/`aosp_atd` or an `-writable-system` boot, `adb root` available). This is a *development image*, deliberately not production.

**Steps.**
1. Add to the app (behind the API-34 gate, no behavioural dependency): a `CallStreamingService`-shaped service declared with `android:permission="android.permission.BIND_CALL_STREAMING_SERVICE"` and an intent filter for `android.telecom.CallStreamingService`. Compiling against `@SystemApi` is not possible with the public SDK, so the qualification component is declared in the manifest and backed by a plain `Service` — qualification is evaluated by the role controller from the manifest, not from the superclass.
2. Place the APK as a **system app** (`/system/app/...`) so `FLAG_SYSTEM` is set. Verify with `adb shell dumpsys package com.simrelay.m0 | grep -i flags`.
3. Set the default holder. Preferred: a `framework-res` RRO (or an AOSP build value) setting `config_systemCallStreaming` to `com.simrelay.m0`. Verify the resolved value as in Experiment 2 step 5.
4. Reboot; trigger role reconciliation; then read `adb shell cmd role get-role-holders android.app.role.SYSTEM_CALL_STREAMING`.
5. Laboratory-only control, clearly *not* a deployment mechanism: on the same development image, attempt `adb shell cmd role set-bypassing-role-qualification true` followed by `cmd role add-role-holder`, then reboot and re-read the holder. The point is to *measure* the static-role reconciliation described in `NON_ROOT_FEASIBILITY.md` §3.2, not to propose it as a route.

**Expected result.** After step 4 the app holds the role and `CALL_AUDIO_INTERCEPTION` shows as granted. After step 5, any hand-added non-system holder is removed by reconciliation.

**Evidence collected.** `FLAG_SYSTEM` state; resolved default holder; role holders before/after reboot; permission grant state; diagnostic export.

**Success condition.** Role held **and** permission granted **and** the app's own probe advances past `PermissionMissing`, with no `su` and no root call from the app.

**Failure condition.** Role not held despite correct system placement, declared component and configured default holder; or role held but permission still denied (which would break the `PermissionFlags.ROLE` chain established in feasibility §3.3).

**Legitimate conclusion.** That the mechanism *works as documented on a development image* — `ConfirmedOnEmulator`. It does **not** show that any retail device can be provisioned this way, because writing the system image and setting a platform resource is exactly the OEM dependency we are measuring.

**Status.** `NotTested`.

---

## Experiment 4 — Permission grant verification

**Goal.** Verify the grant with the same rigour the rooted track applies, so that "granted" is never inferred from a copied file or a successful command.

**Environment.** Whatever image Experiment 3 produced.

**Steps.**
1. `adb shell dumpsys package com.simrelay.m0 | grep -A2 CALL_AUDIO_INTERCEPTION` — read actual grant state.
2. Confirm `RECORD_AUDIO` grant state and its `GRANTED_BY_ROLE` flag if visible.
3. Confirm the app is **not** privileged and **not** platform-signed (so the grant is attributable to the role and nothing else): check `privileged` flag and signing certificate.
4. Reuse `tools/provisioning/verify_privilege.sh` and `verify_installation.sh` where applicable — they already assert real `PackageManager` state rather than inferring it.
5. Re-run the in-app probe and export.

**Expected result.** `CALL_AUDIO_INTERCEPTION granted=true`, privileged flag false, platform signature absent.

**Success condition.** Grant confirmed *and* attributable to role membership alone.

**Failure condition.** Grant present but the app is also privileged or platform-signed — the experiment would be confounded and must be redone on a clean image.

**Legitimate conclusion.** Attribution of the grant to the role, on that image only.

**Status.** `NotTested`.

---

## Experiment 5 — Existing `FrameworkInterceptionBackend` behaviour under a role grant

**Goal.** Confirm that the **unmodified** backend advances correctly once the permission arrives, and observe how far it gets on an emulated modem.

**Environment.** Experiment 3/4 image, plus the emulator console's simulated GSM modem.

**Steps.**
1. Idle probe: expect capability to leave `PermissionMissing`. Record whether it reports `Supported`, `PstnInterceptionUnsupported`, or a mapped failure — all are informative.
2. Record `isPstnCallAudioInterceptable()` result now that the protected call is legitimately reachable.
3. Bring up a simulated call: emulator console (`telnet localhost 5554`, `auth`, `gsm call 15555215556`), answer it, and record `AudioManager.mode` and call state.
4. With the call active, attempt downlink capture, then uplink injection, then short full duplex, using the existing UI actions and the existing sample-rate candidate ladder.
5. Export diagnostics for each attempt (the artifact store already gives each attempt its own ID).

**Expected result (hypothesis, not assumption).** Capability leaves `PermissionMissing`. `isPstnCallAudioInterceptable()` most likely returns **false** on an emulator, because AOSP's check looks for internal telephony TX/RX audio devices which the emulated modem probably does not expose; session opens would then fail with a mapped `UnsupportedOperationException` → `PstnInterceptionUnsupported`. That would be a *clean, expected* negative.

**Evidence collected.** Capability/readiness states, interceptability result, audio mode transitions, per-attempt failure classification, any RX WAV and metrics.

**Success condition.** The provisioning half is proven (permission arrives, probe advances, no crash, deterministic teardown) and every failure is classified rather than swallowed.

**Failure condition.** Crash, unclassified failure, leaked `AudioRecord`/`AudioTrack`, or a state machine that cannot return to `Ready`.

**Legitimate conclusion.** Only that provisioning works and the backend behaves correctly under it. **An emulator cannot prove PSTN audio feasibility** — that remains the rooted track's job on real hardware with a real SIM. A false interceptability result on an emulator is *not* evidence against the framework path on real devices.

**Status.** `NotTested`.

---

## Experiment 6 — Production OEM requirements survey (read-only)

**Goal.** Establish what retail Android 14+ devices actually ship, so the product decision is based on measurement rather than on AOSP defaults.

**Environment.** Any accessible retail Android 14/15/16 handsets. Strictly read-only ADB; no installs required beyond the diagnostic APK where permitted.

**Steps, per device.**
1. `getprop ro.build.version.sdk`, `ro.build.fingerprint`.
2. `dumpsys package permissions | grep -A3 CALL_AUDIO_INTERCEPTION` → does this OEM's declaration include `role`?
3. `cmd role get-role-holders android.app.role.SYSTEM_CALL_STREAMING` → does a preinstalled package already hold it?
4. Pull `framework-res.apk` and dump `config_systemCallStreaming` → is it populated, and with what?
5. Where permitted, install the diagnostic APK and record `isRoleAvailable` / `isRoleHeld` and the provisioning-route verdict.
6. Record everything into a per-device qualification entry.

**Expected result.** Unknown. Two outcomes matter: OEMs that leave the holder empty (role assignable in principle, needs partnership) versus OEMs that already assign it to their own package (role unavailable to us — `exclusive="true"` means one holder).

**Success condition.** A populated per-device table covering at least one device per Android generation and per major OEM we care about.

**Failure condition.** N/A — this is a survey. Absence of devices is a scheduling problem, not a negative result.

**Legitimate conclusion.** Per-device, per-firmware facts only. Consistent with `AGENTS.md` §7-8: compatibility must be measurable and explicit, never generalised to "Android".

**Status.** `NotTested`. Device #1 (API 33) already surveyed and is out of scope for the role question.

---

## Deferred experiment — concurrent capture under a role grant

**Goal.** Answer feasibility §2.3's open risk: Android 16 gates concurrent-capture bypass on `CAPTURE_AUDIO_OUTPUT` or `BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION`, neither of which the role grants. Determine whether a role-provisioned holder's downlink capture can be silenced or pre-empted during a live call.

**Blocked on.** A real Android 16 host with a genuine role grant and a live PSTN call — i.e. Experiment 6 finding a suitable device, or an OEM partnership. Not answerable on an emulator, and not answerable on Device #1.

**Status.** `NotTested`. Recorded so it is not forgotten if the role path is chosen; it would be a late, expensive surprise.

---

## Prototype implemented in this branch

Per the brief's third deliverable, the smallest honest prototype the research justifies — read-only detection and reporting, no fake affordances:

- `app/src/main/java/com/simrelay/m0/diagnostics/ProvisioningPathSnapshot.kt`
  - Pure evaluator `ProvisioningPathEvaluator` mapping (API level, role availability, role held, permission granted, system-app flag) → a `ProvisioningRoute` verdict.
  - Android-facing `capture()` using only public SDK APIs: `Build.VERSION.SDK_INT`, `RoleManager.isRoleAvailable`, `RoleManager.isRoleHeld`, `ApplicationInfo.FLAG_SYSTEM`, `checkSelfPermission`. **No reflection, no hidden constants** — the `@SystemApi` role constant is referenced only as its documented string value, and `RoleManager` use is runtime-gated to API 29+.
- Wired into `DiagnosticReport` JSON and surfaced as one UI row.
- When the route requires system integration the UI says **`System integration required`**; there is deliberately no "Grant role" button, because an ordinary app cannot request a `visible="false"`, `static="true"`, `systemOnly="true"` role.
- Unit tests in `app/src/test/java/com/simrelay/m0/diagnostics/ProvisioningPathTest.kt` cover: API < 29, API 33 (no role branch), API 34 role available but unheld, role held with permission granted, permission granted by some other route, and the system-app distinction.

What it deliberately does **not** do: request the role, claim the role can be requested, branch on manufacturer, alter backend selection, or change any audio behaviour.

---

## Ordering and stop conditions

1. **Experiment 1** — runnable now, no authorisation needed.
2. **Stop and request SDK download authorisation.**
3. **Experiment 2** — cheap, high information, no writable image.
4. **Experiment 3 → 4 → 5** — only if Experiment 2 confirms the role exists as documented.
5. **Experiment 6** — opportunistic, whenever Android 14+ hardware is available.

Stop and report if any experiment contradicts `NON_ROOT_FEASIBILITY.md`; a contradiction means the AOSP reading is wrong and the conclusions must be revised before more work is built on them.
