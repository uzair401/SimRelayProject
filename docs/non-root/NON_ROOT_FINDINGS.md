# SIM Relay — Non-Root Findings Ledger

**Track:** non-root experiment (`experiment/non-root-provisioning`)
**Purpose:** discoveries recorded one at a time so ChatGPT, Claude, Codex and the user can later decide, per finding, what (if anything) should be merged into the generic architecture on `main`.
**Rule:** nothing in this ledger is copied into core code silently. A finding proposes; review disposes.

Confidence values: `ConfirmedByAospSource`, `ConfirmedOnProductionDevice`, `ConfirmedOnEmulator`, `Inferred`, `Unknown`, `NotTested`.

---

### NR-001 — `CALL_AUDIO_INTERCEPTION` gained a `role` protection branch in Android 14

- **Android/API:** 13 (33) vs 14–16 (34–36) and `main`
- **Environment:** AOSP source
- **Observation:** Android 13 declares the permission `signature|privileged`. Android 14 onward declares it `signature|privileged|role`. Android 13 therefore has no non-root, non-platform-signed grant path at all.
- **Evidence:** `frameworks/base` `core/res/AndroidManifest.xml` — `android13-release:5041-5042`, `android14-release:5878-5879`, `android15-release:6350-6351`, `android16-release:6796-6797`, `main:6594-6595`
- **Confidence:** `ConfirmedByAospSource`
- **Impact on architecture:** None on code. Decisive for host-device selection: a non-root product requires an Android 14+ host.
- **Potential main-branch change:** Record the API-33 vs API-34+ provisioning distinction in the device qualification profile, so a qualification record states which grant routes the host's OS actually offers.

---

### NR-002 — Device #1's retail firmware matches AOSP exactly: no role branch

- **Android/API:** 13 (33)
- **Environment:** Samsung SM-A225F, retail build, read-only ADB (no state modified)
- **Observation:** `dumpsys package permissions` reports `prot=signature|privileged` for `CALL_AUDIO_INTERCEPTION`. Samsung has not added a role branch.
- **Evidence:** on-device `dumpsys package permissions` output, 2026-09-08; matches `android13-release` AOSP declaration
- **Confidence:** `ConfirmedOnProductionDevice`
- **Impact on architecture:** Confirms the rooted track's privileged-app provisioning design is the only route on this handset. The non-root role path cannot be tested on Device #1 at all.
- **Potential main-branch change:** Capture the raw permission protection level in diagnostics — it is a single, cheap, unambiguous field that told us in one line what §3 of the Phase 0B-2 report had to argue at length.

---

### NR-003 — `SYSTEM_CALL_STREAMING` role grants exactly the permissions the framework backend needs

- **Android/API:** 14+ (`minSdkVersion="34"`)
- **Environment:** AOSP source
- **Observation:** The role's permission set is exactly `CALL_AUDIO_INTERCEPTION` + `RECORD_AUDIO`. `CALL_AUDIO_INTERCEPTION` occurs exactly once in the entire `roles.xml`, in this role. No other role — including the user-grantable `CALL_REDIRECTION` and `CALL_SCREENING` roles — carries it.
- **Evidence:** `packages/modules/Permission` `PermissionController/res/xml/roles.xml` — `android16-release:1787-1810` (role), `:1798` (permission), `:475-522` (call redirection/screening roles, which grant no call-audio permission); absent from `android13-release`
- **Confidence:** `ConfirmedByAospSource`
- **Impact on architecture:** The role is a *complete* permission-delivery mechanism for `FrameworkInterceptionBackend`. Also kills the "become the call-redirection app" idea cleanly.
- **Potential main-branch change:** None to code. Worth recording in the architecture docs so the option is not re-litigated.

---

### NR-004 — `CAPTURE_AUDIO_OUTPUT` is not required for the framework interception path

- **Android/API:** 14 and 16 (both verified)
- **Environment:** AOSP source (`frameworks/av` native audio policy)
- **Observation:** For `VOICE_UPLINK`/`VOICE_DOWNLINK`/`VOICE_CALL`, Android 14 accepts `CALL_AUDIO_INTERCEPTION` **or** `CAPTURE_AUDIO_OUTPUT`; Android 16 makes `CALL_AUDIO_INTERCEPTION` the primary check and keeps `CAPTURE_AUDIO_OUTPUT` only as a legacy fallback marked for removal.
- **Evidence:** `frameworks/av` `services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp` — `android14-release:647-655`, `android16-release:702-715, 717-772`; `media/utils/ServiceUtilities.cpp:450-459`
- **Confidence:** `ConfirmedByAospSource`
- **Impact on architecture:** Makes the role path viable (the role does not grant `CAPTURE_AUDIO_OUTPUT`). Retrospectively validates the Phase 0B-2 least-privilege allowlist that requested only `CALL_AUDIO_INTERCEPTION`.
- **Potential main-branch change:** Consider dropping `CAPTURE_AUDIO_OUTPUT` and `MODIFY_PHONE_STATE` from the manifest, since neither is used by the framework backend and both widen the requested-privilege surface. **Do not action here** — the legacy backends may want them, so this is a `main` decision, not a non-root decision.

---

### NR-005 — The role is system-only, static, invisible, and its holder is an OEM platform resource

- **Android/API:** 14+
- **Environment:** AOSP source
- **Observation:** `systemOnly="true"` requires `ApplicationInfo.FLAG_SYSTEM`; `static="true"` means holders are always reset to the configured defaults; `visible="false"` means no user grant UI; the holder is read from the **platform** string `config_systemCallStreaming`, which AOSP ships **empty**. Non-qualifying holders are removed on every role reconciliation.
- **Evidence:** `Role.java` (`android16-release` role-controller) `:228` (static), `:233` (systemOnly), `:829` (`FLAG_SYSTEM` check), `:530-555` (`getIdentifier(name, "string", "android")`); `RoleControllerServiceImpl.java:166-208` (reconciliation); `frameworks/base` `core/res/res/values/config.xml:2249` (A14) and `:2517` (A16) — empty string
- **Confidence:** `ConfirmedByAospSource`
- **Impact on architecture:** "No root" ≠ "no OEM integration". The role removes the `privapp-permissions` allowlist and `CAPTURE_AUDIO_OUTPUT`, but not system-image involvement. There is no arbitrary-stock-device non-root route.
- **Potential main-branch change:** Product/architecture doc wording only. The distinction must be stated wherever "non-root" appears, or it will be misread as "installable from a store".

---

### NR-006 — Role membership really does grant an install-time permission

- **Android/API:** 16 (mechanism present from the introduction of the `role` flag)
- **Environment:** AOSP source
- **Observation:** A `ROLE` permission flag on an installed or system package is converted into an actual grant by the permission policy; the role controller stamps `FLAG_PERMISSION_GRANTED_BY_ROLE`. The permission-policy side requires only "system or installed" — the system-app restriction comes from the role definition, not the permission policy.
- **Evidence:** `frameworks/base` `services/permission/java/com/android/server/permission/access/permission/AppIdPermissionPolicy.kt:290-305`; `PermissionController/role-controller/.../model/Permissions.java:253, 673`
- **Confidence:** `ConfirmedByAospSource`
- **Impact on architecture:** Closes the chain role → permission → existing backend. No backend change needed.
- **Potential main-branch change:** None.

---

### NR-007 — `FrameworkInterceptionBackend` needs no change for any provisioning route

- **Android/API:** all
- **Environment:** this repository
- **Observation:** The backend's only authorisation check is `context.checkSelfPermission(CALL_AUDIO_INTERCEPTION)`, which is agnostic to how the permission arrived. Combined with NR-004 and NR-006, a role-provisioned host and a privileged-app host are indistinguishable to the backend.
- **Evidence:** `app/src/main/java/com/simrelay/m0/audio/framework/FrameworkInterceptionBackend.kt:71`
- **Confidence:** `ConfirmedByAospSource` for the platform half; direct code reading for ours
- **Impact on architecture:** Strong positive. Confirms the `AGENTS.md` §8 boundary held under a real test. **Do not** fork the backend into rooted/non-root variants.
- **Potential main-branch change:** None. Worth stating explicitly in the architecture docs as a validated property.

---

### NR-008 — The app cannot currently distinguish signature / privileged / role grant routes

- **Android/API:** all
- **Environment:** this repository
- **Observation:** `PermissionProtectionMapper` collapses signature, privileged and role into a single `SignatureOrPrivilegedOrRole` value. An Android 13 host (no role branch) and an Android 14+ host (role branch available) produce identical qualification records, even though their provisioning options differ completely.
- **Evidence:** `app/src/main/java/com/simrelay/m0/diagnostics/PermissionSnapshot.kt:119-131`
- **Confidence:** direct code reading
- **Impact on architecture:** Diagnostics gap, not a defect. It matters because device qualification is supposed to be measurable and explicit.
- **Potential main-branch change:** **Recommended.** Report the provisioning route per host. Implemented in this branch as `ProvisioningPathSnapshot` using only public SDK APIs (`RoleManager.isRoleAvailable` / `isRoleHeld`, `FLAG_SYSTEM`, `SDK_INT`). Additive and read-only; a good merge candidate once reviewed.

---

### NR-009 — `cmd role get-role-holders` cannot prove role availability

- **Android/API:** 13 (observed); expected to generalise
- **Environment:** Device #1, read-only ADB
- **Observation:** The shell command prints empty output both for a role with no holders **and** for a completely invalid role name (a deliberately bogus role returned the same empty result as `SYSTEM_CALL_STREAMING`), while `DIALER` and `ASSISTANT` returned real holders. Empty output is therefore not evidence of anything.
- **Evidence:** on-device control test, 2026-09-08 (`bogus` → empty, `SYSTEM_CALL_STREAMING` → empty, `DIALER` → `com.samsung.android.dialer`, `ASSISTANT` → `com.google.android.googlequicksearchbox`)
- **Confidence:** `ConfirmedOnProductionDevice`
- **Impact on architecture:** Justifies the in-app `isRoleAvailable` probe: role availability must be read through the API, not inferred from shell output. Also a methodological warning for the experiment plan.
- **Potential main-branch change:** None directly; feeds NR-008.

---

### NR-010 — Downlink extraction is `VOICE_DOWNLINK`-based, which is favourable for the echo risk

- **Android/API:** 16 (and 13/14 by inspection of the same API)
- **Environment:** AOSP source
- **Observation:** `getCallDownlinkExtractionAudioRecord` builds an `AudioRecord` with the internal capture preset `VOICE_DOWNLINK` plus a call-redirection mode — not `VOICE_CALL`. `getCallUplinkInjectionAudioTrack` uses `USAGE_CALL_ASSISTANT` + `CONTENT_TYPE_SPEECH`. Both require the audio mode to be one of `MODE_IN_CALL`, `MODE_IN_COMMUNICATION`, `MODE_CALL_SCREENING`, `MODE_CALL_REDIRECT`, `MODE_COMMUNICATION_REDIRECT`, and PSTN mode additionally requires `isPstnCallAudioInterceptable()`.
- **Evidence:** `frameworks/base` `android16-release` `media/java/android/media/AudioManager.java:9462` (uplink), `:9534` (downlink), javadoc at `:9440-9460` and `:9512-9532`
- **Confidence:** `ConfirmedByAospSource` for the mechanism; `Inferred` for the acoustic consequence
- **Impact on architecture:** Supports the hope in `sim-relay-stack-decisions.md` §5.1 that dedicated downlink extraction avoids recapturing our own injected uplink — the structural precondition for double-talk without host-side AEC. Independently reconfirms `PHASE_0A_REVIEW.md` H2 (do not hard-code `MODE_IN_CALL`).
- **Potential main-branch change:** None. Evidence for the echo strategy, to be settled by the rooted track's real-call measurements.

---

### NR-011 — Enterprise/device-owner APIs cannot grant this permission

- **Android/API:** 16 (and by design generally)
- **Environment:** AOSP source
- **Observation:** `DevicePolicyManager.setPermissionGrantState` is gated on `MANAGE_DEVICE_POLICY_RUNTIME_PERMISSIONS` and applies to runtime permissions. `CALL_AUDIO_INTERCEPTION` is an install-time `signature|privileged|role` permission. Device-owner status confers no signature or privileged permissions.
- **Evidence:** `frameworks/base` `android16-release` `core/java/android/app/admin/DevicePolicyManager.java:14265` and its javadoc
- **Confidence:** `ConfirmedByAospSource`
- **Impact on architecture:** Closes deployment model E. No enterprise/MDM shortcut exists.
- **Potential main-branch change:** None. Recorded so it is not revisited.

---

### NR-012 — The role's service contract is VoIP-oriented

- **Android/API:** 14+
- **Environment:** AOSP source
- **Observation:** `CallStreamingService` is `@SystemApi @hide` and documents itself as a "general call streaming sender for **voip calls**"; its callbacks carry `StreamingCall` objects from Telecom's transactional VoIP API. Declaring the service is required to *qualify* for the role, but our PSTN interception is authorised by the permission and gated by `isPstnCallAudioInterceptable()`, not by a Telecom streaming session.
- **Evidence:** `frameworks/base` `android16-release` `telecomm/java/android/telecom/CallStreamingService.java` (class javadoc, `SERVICE_INTERFACE`, callback set)
- **Confidence:** `ConfirmedByAospSource` for the contract; `Unknown` whether Telecom ever drives it for PSTN calls
- **Impact on architecture:** Do not design around receiving `CallStreamingService` callbacks for PSTN calls. Treat the service purely as a qualification component.
- **Potential main-branch change:** None yet. If the role path is adopted, the manifest gains a qualification-only service — that is a provisioning concern and must not leak into call-state or audio logic.

---

### NR-013 — RESOLVED: Android 16 concurrent-capture policy is not a blocker for the role path

- **Android/API:** 16
- **Environment:** AOSP source
- **Observation:** The concern was that concurrent-capture bypass is gated on `CAPTURE_AUDIO_OUTPUT` or `BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION`, neither of which the role grants, and that `updateUidStates_l` denies capture while `isInCall` unless the client can bypass (`return !(isInCall && !canCaptureCall)`). **That restriction is overridden for call-audio clients.** In the same function, virtual sources are allowed unconditionally:

  ```cpp
  } else if (isVirtualSource(source)) {
      // Allow capture for virtual (remote submix, call audio TX or RX...) sources
      allowCapture = true;
  }
  ```

  and `isVirtualSource()` returns true for `AUDIO_SOURCE_VOICE_UPLINK`, `AUDIO_SOURCE_VOICE_DOWNLINK` and `AUDIO_SOURCE_VOICE_CALL`. `silenceAllRecordings_l()` likewise skips virtual sources. Since `getCallDownlinkExtractionAudioRecord` uses the `VOICE_DOWNLINK` capture preset (NR-010), a role-provisioned holder is exempt from both the in-call concurrent-capture restriction and blanket silencing.
- **Evidence:** `frameworks/av` `android16-release` `services/audiopolicy/service/AudioPolicyService.cpp:1062-1099` (in-call gate and virtual-source override), `:1202-1216` (`isVirtualSource` body), `:1180-1187` (`silenceAllRecordings_l`); `services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp:875-889` (origin of the concern)
- **Confidence:** `ConfirmedByAospSource` (runtime confirmation still pending a real call on a role-provisioned Android 16 host)
- **Impact on architecture:** Removes the main identified late risk from the role path. `CAPTURE_AUDIO_OUTPUT` and `BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION` are not needed for downlink capture on Android 16.
- **Potential main-branch change:** None. Strengthens the case that the least-privilege permission set is sufficient on both tracks.

---

### NR-015 — A static role can only be held by a package already named in the platform default-holder config

- **Android/API:** 14+
- **Environment:** AOSP source
- **Observation:** This is stronger than NR-005. `Role.isPackageQualifiedAsUser()` ends with:

  ```java
  if (mStatic && !getDefaultHoldersAsUser(user, context).contains(packageName)) {
      return false;
  }
  ```

  Because `SYSTEM_CALL_STREAMING` is `static="true"`, a package **cannot qualify at all** unless it is already listed in `config_systemCallStreaming`. Being a system app and declaring the required service are necessary but not sufficient. The only short-circuit is `isBypassingQualification()`, the `BYPASS_ROLE_QUALIFICATION` test facility. So `cmd role add-role-holder` cannot succeed for this role on a stock build regardless of how the app is packaged.
- **Evidence:** `packages/modules/Permission` `android16-release` `PermissionController/role-controller/java/com/android/role/controller/model/Role.java:649-695` (qualification), `:530-555` (default holder resolution)
- **Confidence:** `ConfirmedByAospSource`
- **Impact on architecture:** The platform `config_systemCallStreaming` value is not merely the *normal* way to assign the role — it is the *only* way. This makes the framework-res configuration the single hard OEM dependency of the non-root route, and it means the role cannot be granted by ADB, by a user, or by any app-side packaging choice.
- **Potential main-branch change:** None to code. It sharpens the minimum-OEM-integration statement in the product docs.

---

### NR-016 — The exact role qualification contract, and the minimum app-side integration

- **Android/API:** 14+
- **Environment:** AOSP source, implemented in this branch
- **Observation:** The role controller resolves the required component with `PackageManager.queryIntentServices(Intent("android.telecom.CallStreamingService"))` scoped to the package, and accepts a service only when `resolveInfo.serviceInfo.permission` equals `android.permission.BIND_CALL_STREAMING_SERVICE` exactly. The role declares no component flags and no metadata requirements, and `isComponentQualified()` returns true unconditionally, so nothing further is required of the component. `isRequired()` applies the requirement only when `applicationInfo.targetSdkVersion >= minTargetSdkVersion` (unset for this role).
- **Evidence:** `PermissionController/role-controller/java/com/android/role/controller/model/RequiredComponent.java:125-127, 178-250, 266-268`; `RequiredService.java:26-52`; `IntentFilterData.java:97-107`
- **Confidence:** `ConfirmedByAospSource`
- **Impact on architecture:** The app-side integration is a single exported `<service>` declaration guarding on `BIND_CALL_STREAMING_SERVICE` with that action. Implemented as `com.simrelay.m0.provisioning.CallStreamingQualificationService`, isolated in a `provisioning` package, inert where the role does not exist. It cannot extend the `@SystemApi` `CallStreamingService` with the public SDK, and per NR-012 it is a qualification component only — it must not be treated as a call-audio path.
- **Potential main-branch change:** Deferred. The manifest declaration is harmless on all API levels but should only move to `main` if the role route is adopted, since it advertises a Telecom service the app does not implement.

---

### NR-017 — The `RECORD_AUDIO` app op still gates capture on every provisioning route

- **Android/API:** 16 (mechanism long-standing)
- **Environment:** AOSP source
- **Observation:** In `updateUidStates_l`, the app-op check runs *before* the virtual-source exemption: `if (!current->hasOp()) { allowCapture = false; }`. So a denied or foreground-only `RECORD_AUDIO` app op silences capture even for call-audio sources and even with `CALL_AUDIO_INTERCEPTION` held.
- **Evidence:** `frameworks/av` `android16-release` `services/audiopolicy/service/AudioPolicyService.cpp:1093-1095`
- **Confidence:** `ConfirmedByAospSource`
- **Impact on architecture:** Independent of provisioning route, so it affects the rooted track identically. It is the same hazard the rooted research recorded (`sim-relay-stack-decisions.md` §2.5: `PermissionController` resetting `RECORD_AUDIO` to `MODE_FOREGROUND` and breaking background capture). Relevant when the host must capture with the screen off.
- **Potential main-branch change:** Worth recording in the shared architecture notes as a route-independent risk, so neither track assumes it is a root or role artefact.

---

### NR-018 — The complete non-root chain works, proven end to end on API 34 and API 36

- **Android/API:** 34 and 36
- **Environment:** `SimRelay_API34` / `SimRelay_API36` AVDs, `google_apis` x86_64, `userdebug`/`dev-keys`, `-writable-system`
- **Observation:** Each link verified independently, on both API levels:
  `config_systemCallStreaming=com.simrelay.m0` → role qualification → `role holders = com.simrelay.m0` → `CALL_AUDIO_INTERCEPTION: granted=true, flags=[ GRANTED_BY_ROLE]`. `RECORD_AUDIO` likewise `GRANTED_BY_ROLE`. `CAPTURE_AUDIO_OUTPUT` remained **not granted**. The app was `flags=[ SYSTEM ... ]` with no `PRIVILEGED` private flag and no platform signature, so the grant is attributable to role membership alone — the platform's own `GRANTED_BY_ROLE` flag states this.
- **Evidence:** `artifacts/non-root-emulator/*-api34-role-holder/`, `*-api36-role-holder/` (`package-state.txt`, `role-holders.txt`, `platform-config.txt`, `app-export/provisioning.json`)
- **Confidence:** `ConfirmedOnEmulator`
- **Impact on architecture:** The non-root provisioning model is real, not theoretical. Role-holder state alone was explicitly *not* treated as success; the `PackageManager` grant was verified separately.
- **Potential main-branch change:** None. Validates the existing architecture unchanged.

---

### NR-019 — The platform states the grant mechanism out loud

- **Android/API:** 34
- **Environment:** emulator, ordinary APK in `/data/app`
- **Observation:** `pm grant com.simrelay.m0 android.permission.CALL_AUDIO_INTERCEPTION` fails with
  `java.lang.SecurityException: Permission android.permission.CALL_AUDIO_INTERCEPTION is managed by role`.
- **Evidence:** shell transcript, API 34 baseline run
- **Confidence:** `ConfirmedOnEmulator`
- **Impact on architecture:** Runtime confirmation of NR-001/NR-003: from Android 14 the role is *the* grant path, and ADB cannot substitute. Also closes off any "just `pm grant` it" suggestion.
- **Potential main-branch change:** None.

---

### NR-020 — The default holder is provisioned by a preinstalled **static** RRO, exactly as Google does it

- **Android/API:** 34 and 36
- **Environment:** emulator
- **Observation:** On stock Google images, `config_systemCallStreaming` is **already populated** (`com.google.android.gms`) by `/product/overlay/GoogleConfigOverlay.apk` (`com.google.android.overlay.googleconfig`, `mPriority=7`, immutable, `STATE_ENABLED`), proven with `cmd overlay lookup --verbose`. Reproducing this required two corrections: the overlay must out-prioritise that one, and it must be **static** — a `isStatic="false"` overlay was registered but returned to `STATE_DISABLED` on every reboot, so the config reverted to GMS before role reconciliation ran. As `isStatic="true"` in `/product/overlay` it came up `STATE_ENABLED`, `mIsMutable=false`, priority 12, and persisted.
- **Evidence:** `platform-config.txt` in both baseline and role-holder runs; `dumpsys overlay` transcripts; `tools/non-root/build_role_overlay.sh`
- **Confidence:** `ConfirmedOnEmulator` for the mechanism; `ConfirmedOnEmulator` that Google itself uses a preinstalled RRO for this value
- **Impact on architecture:** The RRO is only an emulator representation of OEM/system-image provisioning — **not** a retail deployment mechanism. Its value is showing that the required OEM action is small and conventional: one config string in a preinstalled overlay, which is already how the value is set on shipping Google builds.
- **Potential main-branch change:** None to app code.

---

### NR-021 — On Android 16 GMS already holds the role, and the role is exclusive

- **Android/API:** 34 vs 36 (behaviour differs)
- **Environment:** emulator, stock images before provisioning
- **Observation:** API 36 baseline: `SYSTEM_CALL_STREAMING holders = [com.google.android.gms]`, because GMS declares `com.google.android.gms.callstreaming.service.CallStreamingService` guarded by `BIND_CALL_STREAMING_SERVICE`. API 34 baseline: holders **empty**, because GMS is named in the config but declares no such service and therefore fails qualification. Our provisioning displaced GMS and made SimRelay the sole holder on API 36.
- **Evidence:** `role-holders.txt` and `qualification-component.txt` for both baselines; `cmd package query-services -a android.telecom.CallStreamingService`
- **Confidence:** `ConfirmedOnEmulator`
- **Impact on architecture:** Product-critical. The role is `exclusive="true"`, so on a GMS Android 16 host SimRelay can only hold it by **displacing Google's call-streaming feature**. An OEM partner would have to accept that trade, and it cannot be done on a retail device at all. This is a stronger constraint than the API 34 picture suggested and should feed the product decision directly.
- **Potential main-branch change:** None to code; belongs in the product/compatibility narrative.

---

### NR-022 — Real call downlink PCM captured with the unmodified backend, no root

- **Android/API:** 34
- **Environment:** emulator, role-provisioned system app, emulated GSM call answered (`mCallState=2`, `audioMode=2` = `MODE_IN_CALL`)
- **Observation:** `FrameworkInterceptionBackend` — **unmodified** — reported `capability=Supported`, `isPstnCallAudioInterceptable()=true`, opened a downlink session at 48 kHz and delivered real audio: `sampleCount=3111212`, `durationMillis=64816`, `rms=11592.7`, `peakAbsolute=32734`, `isExactZero=false`. `rx.wav` is 6,222,468 bytes, exactly `3111212 × 2 + 44`. Teardown was clean (`session_released`). **Uplink injection failed**: `UnsupportedOperationException: Cannot create AudioTrack`, matching AudioManager's throw when the injection track cannot be built — the emulated audio HAL exposes no uplink injection device. Full duplex therefore failed at the uplink step, and the code correctly released the already-open downlink first.
- **Evidence:** `artifacts/non-root-emulator/*-api34-role-holder/session-attempt/` (`metrics.json`, `session-events.txt`)
- **Confidence:** `ConfirmedOnEmulator`
- **Impact on architecture:** Classified per the agreed states: `PermissionProvisioningWorks` **and** `FrameworkApiAccessible` **and** downlink RX working, with uplink TX unavailable for want of emulated hardware. The uplink result is *not* a provisioning failure and says nothing about real handsets — proving uplink injection remains the rooted track's job on real hardware.
- **Potential main-branch change:** None.

---

### NR-023 — Uplink HAL unavailability is misreported as `UnsupportedAudioFormat`

- **Android/API:** 34
- **Environment:** emulator, role-provisioned
- **Observation:** When `getCallUplinkInjectionAudioTrack` throws `UnsupportedOperationException("Cannot create the AudioTrack")`, `CapabilityFailureMapper` classifies it as `UnsupportedAudioFormat`, so the diagnostic blames the requested PCM format when the real cause is that the platform cannot create an injection track at all. The candidate-rate ladder then retries every format pointlessly. The downlink equivalent mapped correctly (`IllegalStateException: not available in mode MODE_RINGTONE` → `CallNotActive`).
- **Evidence:** `session-events.txt` (`state=UnsupportedAudioFormat ... message=Cannot create AudioTrack`)
- **Confidence:** `ConfirmedOnEmulator`
- **Impact on architecture:** A real generic defect, and exactly the class of problem `PHASE_0A_REVIEW.md` M7 warned about — exception class alone is too coarse. It would mislead device qualification on real handsets, attributing a missing HAL path to a format problem.
- **Potential main-branch change:** **Recommended for `main`.** Distinguish "injection track could not be created" from "format rejected", e.g. a distinct state for track/record creation failure. Deliberately **not** changed on this branch: acceptance point 5 says the backend stays untouched absent a demonstrated generic defect, and this finding is the record of one. Codex owns the fix.

---

### NR-024 — `BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION` does not exist on either released build

- **Android/API:** 34 and 36
- **Environment:** emulator
- **Observation:** The permission is **not declared** on the API 34 or the API 36 platform, though AOSP's audio policy references it behind `concurrent_audio_record_bypass_permission()`. It is evidently flag-gated and not present in these releases.
- **Evidence:** `package-state.txt` "platform declarations of the four tracked permissions" in both runs
- **Confidence:** `ConfirmedOnEmulator`
- **Impact on architecture:** Part of NR-013's premise is moot on shipping builds: no app can hold a permission the platform does not define, so the concurrent-capture bypass cannot be a differentiator today.
- **Potential main-branch change:** None.

---

### NR-025 — Emulator audio-mode behaviour differs between API 34 and API 36, capping what can be tested

- **Android/API:** 34 vs 36
- **Environment:** emulator, `emu gsm call` + `accept`
- **Observation:** On API 34 an answered emulated call reached `MODE_IN_CALL` (`audioMode=2`), enabling a real session. On API 36 the same sequence leaves the audio mode at `MODE_RINGTONE` and then `NORMAL` while telephony reports `mCallState=2`, for at least 30 s; `cmd telecom answer-ringing-call` does not exist and `KEYCODE_HEADSETHOOK` had no effect. So no in-call session can be opened on the API 36 emulator.
- **Evidence:** audio-mode polling transcript; `session_open_failed ... CallNotActive ... not available in mode MODE_RINGTONE`
- **Confidence:** `ConfirmedOnEmulator`
- **Impact on architecture:** **This is why NR-013 stays `ConfirmedByAospSource`.** The Android 16 in-call capture that would confirm the virtual-source exemption at runtime is unreachable on this emulator. API 34 supplies supporting runtime evidence (in-call capture succeeded holding only role-granted permissions, with `CAPTURE_AUDIO_OUTPUT` denied), but that is API 34, not 16. Closing NR-013 needs real Android 16 hardware.
- **Potential main-branch change:** None.

---

### NR-014 — Local environment cannot yet run emulator experiments

- **Android/API:** n/a
- **Environment:** developer workstation
- **Observation:** Emulator binary and KVM are present; **no system images and no AVDs** are installed. Emulator experiments require an SDK download, which modifies the Android SDK installation and needs explicit approval under `AGENTS.md` §28.
- **Evidence:** read-only inspection of `$ANDROID_HOME` on 2026-09-08
- **Confidence:** `ConfirmedOnProductionDevice` (of the workstation state)
- **Impact on architecture:** None. Blocks Experiments 2–5.
- **Potential main-branch change:** None.
