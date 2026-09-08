# SIM Relay — Non-Root Provisioning Feasibility

**Track:** non-root experiment (`experiment/non-root-provisioning`)
**Date:** 2026-09-08
**Scope:** How the existing `FrameworkInterceptionBackend` could obtain `android.permission.CALL_AUDIO_INTERCEPTION` without root on the HOST.
**Status:** research pass complete; no emulator experiment performed yet; no application architecture change proposed.

Evidence in this document is classified as `ConfirmedByAospSource`, `ConfirmedOnProductionDevice`, `ConfirmedOnEmulator`, `Inferred`, `Unknown`, or `NotTested`. AOSP citations give repository, branch, path and line numbers as fetched on 2026-09-08.

---

## 1. Bottom line

1. **An ordinary sideloaded APK can never obtain `CALL_AUDIO_INTERCEPTION`** on Android 13, 14, 15, 16 or current `main`. There is no user-grantable path, no runtime-permission path, and no enterprise path. `ConfirmedByAospSource`
2. **A legitimate non-root mechanism does exist from Android 14 (API 34): the `android.app.role.SYSTEM_CALL_STREAMING` role**, which grants exactly `CALL_AUDIO_INTERCEPTION` + `RECORD_AUDIO`. `ConfirmedByAospSource`
3. **That permission pair is sufficient.** `CAPTURE_AUDIO_OUTPUT` is *not* required for the framework interception path — the native audio policy accepts `CALL_AUDIO_INTERCEPTION` for `VOICE_DOWNLINK`. `ConfirmedByAospSource`
4. **But the role is `systemOnly` + `static` + `visible="false"`, with its holder chosen by a platform string resource that AOSP ships empty.** So the role can only be held by a preinstalled system app named by the OEM's `framework-res` configuration. `ConfirmedByAospSource`
5. Therefore, for SIM Relay: **"no root" is achievable. "No OEM / system-image integration" is not**, on any Android version currently shipping. These are different things and must not be conflated in product messaging.
6. **Device #1 (Samsung SM-A225F, Android 13 / API 33) cannot use the role path at all.** Its platform declares the permission as `signature|privileged` with no `role` branch, and the role does not exist on API 33. `ConfirmedOnProductionDevice`
7. The strongest realistic non-root deployment is therefore **OEM/system integration on an Android 14+ host** (role path preferred, privileged-app path as the fallback), not a stock-retail-device mechanism.

Nothing here invalidates the rooted track. Root remains the way to *prove PSTN RX/TX feasibility now*; the role path is how the same proven backend would ship without root later.

---

## 2. The permission this all turns on

`FrameworkInterceptionBackend` needs one permission and nothing else:

```
android.permission.CALL_AUDIO_INTERCEPTION
```

`app/src/main/java/com/simrelay/m0/audio/framework/FrameworkInterceptionBackend.kt:71` checks it with `context.checkSelfPermission(...)`. That check is **agnostic to how the permission was obtained** — privileged allowlist, platform signature, or role. This is the single most important architectural fact in this document: *the backend needs no change for a role-based grant.*

### 2.1 Protection level by Android version

| Android | API | Declared protection level | Role branch? | Evidence |
|---|---|---|---|---|
| 13 | 33 | `signature\|privileged` | **No** | `frameworks/base` `android13-release` `core/res/AndroidManifest.xml:5041-5042` `ConfirmedByAospSource` |
| 14 | 34 | `signature\|privileged\|role` | **Yes** | `android14-release` `core/res/AndroidManifest.xml:5878-5879` `ConfirmedByAospSource` |
| 15 | 35 | `signature\|privileged\|role` | Yes | `android15-release` `core/res/AndroidManifest.xml:6350-6351` `ConfirmedByAospSource` |
| 16 | 36 | `signature\|privileged\|role` | Yes | `android16-release` `core/res/AndroidManifest.xml:6796-6797` `ConfirmedByAospSource` |
| main | — | `signature\|privileged\|role` | Yes | `main` `core/res/AndroidManifest.xml:6594-6595` `ConfirmedByAospSource` |

Android 13 → 14 is the inflection point for this entire track. The `role` protection flag is what makes any non-root, non-platform-signed grant conceivable.

**Device #1 corroboration** (`ConfirmedOnProductionDevice`, read-only ADB, no state changed):

```
Permission [android.permission.CALL_AUDIO_INTERCEPTION] (edcd623):
    sourcePackage=android
    uid=1000 gids=[] type=0 prot=signature|privileged
```

The retail Samsung Android 13 build matches AOSP `android13-release` exactly: no `role` branch. This is a cleaner, more precise restatement of the Phase 0B-2 conclusion, and it independently confirms it.

### 2.2 What the APIs additionally require (unchanged by provisioning route)

From `frameworks/base` `android16-release` `media/java/android/media/AudioManager.java` (`ConfirmedByAospSource`):

- `isPstnCallAudioInterceptable()` — line 9361, `@TestApi @SystemApi @RequiresPermission(CALL_AUDIO_INTERCEPTION)`.
- `getCallUplinkInjectionAudioTrack(AudioFormat)` — line 9462. Builds an `AudioTrack` with `USAGE_CALL_ASSISTANT` + `CONTENT_TYPE_SPEECH` + `setCallRedirectionMode(redirectMode)`.
- `getCallDownlinkExtractionAudioRecord(AudioFormat)` — line 9534. Builds an `AudioRecord` with internal capture preset **`VOICE_DOWNLINK`** + `setCallRedirectionMode(redirectMode)`.
- Both throw `IllegalStateException` unless the audio mode is one of `MODE_IN_CALL`, `MODE_IN_COMMUNICATION`, `MODE_CALL_SCREENING`, `MODE_CALL_REDIRECT`, `MODE_COMMUNICATION_REDIRECT`; and `UnsupportedOperationException` when the PSTN redirect mode is active but `isPstnCallAudioInterceptable()` is false.
- Accepted formats: 8 kHz–48 kHz, mono or stereo, PCM 16-bit or float 32-bit.

Two consequences worth recording:

- This **independently validates `PHASE_0A_REVIEW.md` H2** (do not hard-code `MODE_IN_CALL`) and validates the current design choice of letting the framework decide legality. `ConfirmedByAospSource`
- The downlink path is **downlink-only (`VOICE_DOWNLINK`), not `VOICE_CALL`**. That is structurally favourable for the echo problem flagged in `sim-relay-stack-decisions.md` §5.1: injected uplink should not be recaptured. It is *evidence for the hypothesis*, not proof of acoustic behaviour on real hardware. `Inferred`

### 2.3 `CAPTURE_AUDIO_OUTPUT` is not required

This matters because the role does **not** grant `CAPTURE_AUDIO_OUTPUT`. If it were required, the role path would be dead on arrival.

Android 14 — `frameworks/av` `android14-release` `services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp:647-655` (`ConfirmedByAospSource`):

```cpp
bool canCaptureOutput = captureAudioOutputAllowed(attributionSource);
bool canInterceptCallAudio = callAudioInterceptionAllowed(attributionSource);
bool isCallAudioSource = inputSource == AUDIO_SOURCE_VOICE_UPLINK
         || inputSource == AUDIO_SOURCE_VOICE_DOWNLINK
         || inputSource == AUDIO_SOURCE_VOICE_CALL;
if (isCallAudioSource && !canInterceptCallAudio && !canCaptureOutput) {
    return binderStatusFromStatusT(PERMISSION_DENIED);
}
```

Android 16 — same file, `checkPermissionForInput()` at line 717 makes `CALL_AUDIO_INTERCEPTION` the *primary* check for `VOICE_UPLINK`/`VOICE_DOWNLINK`/`VOICE_CALL`, with `CAPTURE_AUDIO_OUTPUT` retained only as a legacy fallback that AOSP marks for removal (lines 702-715, 765-768). `callAudioInterceptionAllowed()` checks that one permission and nothing else (`frameworks/av` `media/utils/ServiceUtilities.cpp:450-459`).

The framework-side attribute validation agrees: `USAGE_CALL_ASSISTANT` with `FLAG_CALL_REDIRECTION` is accepted on `CALL_AUDIO_INTERCEPTION` *or* `MODIFY_AUDIO_ROUTING` (`frameworks/base` `android16-release` `services/core/java/com/android/server/audio/AudioService.java:11236-11251`), and `mSupportedSystemUsages` defaults to `{USAGE_CALL_ASSISTANT}` (same file, lines 1200-1201), so no additional device configuration is needed to pass the system-usage gate. `ConfirmedByAospSource`

This also retrospectively validates the Phase 0B-2 least-privilege allowlist design, which deliberately requested only `CALL_AUDIO_INTERCEPTION` and denied `CAPTURE_AUDIO_OUTPUT` and `MODIFY_PHONE_STATE`.

**Open risk, not yet tested:** Android 16 gates *concurrent-capture bypass* on `CAPTURE_AUDIO_OUTPUT` or `BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION` (`AudioPolicyInterfaceImpl.cpp:875-889`). Whether a role-provisioned holder (which has neither) is subject to concurrent-capture silencing during a live call is **`NotTested`** and belongs in the experiment plan.

---

## 3. The system call streaming role, in detail

`packages/modules/Permission` `android16-release` `PermissionController/res/xml/roles.xml:1787-1810` (`ConfirmedByAospSource`; the Android 14 definition at `android14-release:1592-1611` is identical apart from an added `exclusivity="user"` attribute):

```xml
<role
    name="android.app.role.SYSTEM_CALL_STREAMING"
    allowBypassingQualification="true"
    defaultHolders="config_systemCallStreaming"
    exclusive="true"
    exclusivity="user"
    minSdkVersion="34"
    static="true"
    systemOnly="true"
    visible="false">
    <permissions>
        <permission name="android.permission.CALL_AUDIO_INTERCEPTION" />
        <permission name="android.permission.RECORD_AUDIO" />
    </permissions>
    <required-components>
        <service permission="android.permission.BIND_CALL_STREAMING_SERVICE">
            <intent-filter>
                <action name="android.telecom.CallStreamingService" />
            </intent-filter>
        </service>
    </required-components>
</role>
```

Attribute semantics, from `PermissionController/role-controller/java/com/android/role/controller/model/Role.java` (`android16-release`):

| Attribute | Meaning | Evidence |
|---|---|---|
| `minSdkVersion="34"` | Role does not exist before Android 14 | `roles.xml` above; absent entirely from `android13-release/PermissionController/res/xml/roles.xml` |
| `systemOnly="true"` | Holder must be a **system app**: `mSystemOnly && (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0` → not qualified | `Role.java:829` |
| `static="true"` | "the role will always be assigned to its default holders" | `Role.java:228` |
| `visible="false"` | Not shown in Settings; no user-facing grant UI | `Role.java:238-240` |
| `exclusive="true"` | At most one holder | `Role.java:145-147` |
| `defaultHolders="config_systemCallStreaming"` | Holder package read from a **platform** string resource | `Role.java:530-555` |
| `allowBypassingQualification="true"` | Qualification may be bypassed by a caller holding `BYPASS_ROLE_QUALIFICATION` (test/CI facility) | `Role.java:125-127`, `RoleManager.java:822-824` |

### 3.1 The OEM hook is in `framework-res`, and AOSP ships it empty

`Role.getDefaultHoldersAsUser()` resolves the resource against the **platform** package, not PermissionController (`Role.java:549-555`):

```java
Resources resources = context.getResources();
int resourceId = resources.getIdentifier(mDefaultHoldersResourceName, "string", "android");
if (resourceId == 0) {
    Log.w(LOG_TAG, "Cannot find resource for default holder: " + mDefaultHoldersResourceName);
    return Collections.emptyList();
}
```

And the platform value is an **empty string** in both Android 14 and Android 16 (`frameworks/base` `core/res/res/values/config.xml:2249` and `:2517`) (`ConfirmedByAospSource`):

```xml
<!-- The name of the package that will hold the call streaming role. -->
<string name="config_systemCallStreaming" translatable="false"></string>
```

So on stock AOSP **nobody holds this role**. Assigning it means an OEM (or an AOSP builder) sets `config_systemCallStreaming` to a package name, either by building `framework-res` with that value or by shipping a `framework-res` runtime resource overlay. That is the precise, minimal, documented integration point for this deployment model.

### 3.2 A manually added holder does not survive

`packages/modules/Permission` `android16-release` `PermissionController/role-controller/java/com/android/role/controller/service/RoleControllerServiceImpl.java:166-208`, inside `onGrantDefaultRoles()` (`ConfirmedByAospSource`):

- Current holders that no longer qualify are removed ("Removing package that no longer qualifies for the role").
- Then: `if (currentPackageNamesSize == 0 || isStaticRole)` → for a **static** role the default holders are re-added on every reconciliation pass.
- Default/fallback holders are themselves re-checked with `role.isPackageQualifiedAsUser(...)`.

Reconciliation runs on boot, package install/update and user unlock. Consequently any hand-assigned holder of a static role is transient, and a non-system package would in any case fail `isPackageQualified` at `Role.java:829`.

### 3.3 Role membership does grant an install-time permission

The chain is real, not assumed: role holders receive a `ROLE` permission flag, and the permission policy converts that into an actual grant (`frameworks/base` `android16-release` `services/permission/java/com/android/server/permission/access/permission/AppIdPermissionPolicy.kt:290-305`) (`ConfirmedByAospSource`):

```kotlin
val isSystemOrInstalled =
    packageState.isSystem || packageState.getUserStateOrDefault(userId).isInstalled
newFlags =
    if (isSystemOrInstalled &&
            (newFlags.hasBits(PermissionFlags.ROLE) || newFlags.hasBits(PermissionFlags.PREGRANT))) {
        newFlags or PermissionFlags.RUNTIME_GRANTED
    } else { ... }
```

The role controller sets `PackageManager.FLAG_PERMISSION_GRANTED_BY_ROLE` when granting (`PermissionController/role-controller/.../model/Permissions.java:253, 673`). Note that the *permission-policy* side only requires "system or installed" — the system-app restriction for this route comes from the **role definition's** `systemOnly`, not from the permission policy.

### 3.4 The role's service contract is VoIP-shaped

`frameworks/base` `android16-release` `telecomm/java/android/telecom/CallStreamingService.java` is `@SystemApi @hide` and documents itself as (`ConfirmedByAospSource`):

> This service is implemented by an app that wishes to provide functionality for a general call streaming sender for **voip calls**.

Its callbacks (`onCallStreamingStarted(StreamingCall)`, `onCallStreamingStopped()`, `onCallStreamingStateChanged(int)`) concern Telecom's transactional VoIP call-streaming feature — streaming a *VoIP* call to another device. The class javadoc even tells the sender to "start to intercept the device audio using audio records and audio tracks from Audio frameworks", which is exactly our mechanism, but the call objects it hands out are VoIP calls.

**Important distinction:** the role is useful to SIM Relay as a *permission-delivery vehicle*. Its `required-components` obliges us to declare a `CallStreamingService` (guarded by `BIND_CALL_STREAMING_SERVICE`) to qualify, but the **PSTN** interception we need is authorised by the permission and gated by `isPstnCallAudioInterceptable()` / audio mode — not by Telecom's streaming session. Whether Telecom ever drives our `CallStreamingService` for a PSTN call is **`Unknown`** and, on the evidence above, unlikely. We should not design around receiving those callbacks. `Inferred`

### 3.5 API surface a normal app may legitimately call

From `packages/modules/Permission` `android16-release` `framework-s/java/android/app/role/RoleManager.java`:

| API | Visibility | Notes |
|---|---|---|
| `isRoleAvailable(String)` (line 341) | **public SDK** | Usable by our app to detect whether the role exists on this build |
| `isRoleHeld(String)` (line 359) | **public SDK** | Checks only the calling package |
| `ROLE_SYSTEM_CALL_STREAMING` (line 211) | `@SystemApi @hide` | Constant not in public SDK; the *string* `"android.app.role.SYSTEM_CALL_STREAMING"` is usable |
| `addRoleHolderAsUser(...)` (line 446) | `@SystemApi`, needs `MANAGE_ROLE_HOLDERS` | Not callable by us |
| `setBypassingRoleQualification(boolean)` (line 824) | `@SystemApi`, needs `BYPASS_ROLE_QUALIFICATION` | Test facility |
| `getRoleHolders(...)` | needs `MANAGE_ROLE_HOLDERS` | Not callable by us |

This is the basis for the honest in-app diagnostic described in §7: we can *detect and report* role availability and holding without reflection and without pretending we can request the role.

---

## 4. Answers to the twelve questions

**1. Can an ordinary sideloaded APK obtain `CALL_AUDIO_INTERCEPTION`?**
**No, on every version examined (13–16, main).** It is never a runtime permission, so `pm grant` and runtime request dialogs do not apply. The `signature` branch needs the platform key; `privileged` needs `priv-app` placement plus an allowlist on the same partition; `role` (14+) needs the `SYSTEM_CALL_STREAMING` role, which requires `FLAG_SYSTEM` and an OEM-set default holder. No user-grantable route exists. `ConfirmedByAospSource`

**2. On which Android versions does the system call-streaming role exist?**
**Android 14 (API 34) onward.** `minSdkVersion="34"` in the role definition, and the role is entirely absent from `android13-release` `roles.xml`. Present in 14, 15, 16 and `main`. `ConfirmedByAospSource`

**3. Does the role grant `CALL_AUDIO_INTERCEPTION`?**
**Yes — and also `RECORD_AUDIO`.** Those are the only two permissions in its `<permissions>` set. `CALL_AUDIO_INTERCEPTION` appears exactly **once** in the whole of `roles.xml`, in this role. `ConfirmedByAospSource`

**4. Is the role user-requestable or system/OEM-controlled?**
**System/OEM-controlled.** `visible="false"` (no Settings UI), `static="true"` (holders always reset to defaults), `systemOnly="true"` (holder must be a system app), holder named by the platform resource `config_systemCallStreaming`. A user cannot grant it, and `createRequestRoleIntent` would be meaningless for an invisible static role. `ConfirmedByAospSource`

**5. What must an application implement to qualify?**
A `Service` declared with `android:permission="android.permission.BIND_CALL_STREAMING_SERVICE"` and an intent filter for `android.telecom.CallStreamingService`, per the role's `required-components` and the `CallStreamingService` javadoc example. In addition it must be a system app (`FLAG_SYSTEM`) and be named in `config_systemCallStreaming`. `ConfirmedByAospSource`

**6. Can we reproduce this on an AOSP emulator?**
**Not yet attempted — `NotTested`.** It is plausible and is the core of Experiment 3 in the plan: on a `userdebug` AOSP/emulator image, `adb root` + writable `/system` (or a `framework-res` overlay) can set the default holder and place the app as a system app. The local environment currently has the emulator binary and KVM but **no system images installed and no AVDs**, so this requires an SDK download that has not been authorised. On a non-writable emulator image, `adb shell cmd role add-role-holder` plus `set-bypassing-role-qualification` exists as a *test* facility (shell holds `MANAGE_ROLE_HOLDERS` and `BYPASS_ROLE_QUALIFICATION`, `frameworks/base` `packages/Shell/AndroidManifest.xml:295,297`), but §3.2 shows a static role's holder is reconciled back to defaults, so this is at best a transient laboratory trick and explicitly **not** a deployment model.

**7. What is required for OEM integration?**
Least-privilege version: ship SIM Relay as a **system app** on the system image, declare the `CallStreamingService` component, and set `config_systemCallStreaming` to our package via `framework-res` (build value or RRO). No `privapp-permissions` XML, no `CAPTURE_AUDIO_OUTPUT`, no SELinux change, no root at runtime. Requires Android 14+. `ConfirmedByAospSource` for the mechanism; `NotTested` end-to-end.

**8. What is required for privileged system-app deployment?**
APK in `/system/priv-app/` (or another privileged directory) **plus** a `privapp-permissions` allowlist entry for `CALL_AUDIO_INTERCEPTION` on the **same partition** as the APK. This is the mechanism the rooted track already designed in `docs/provisioning/PHASE_0B2_REPORT.md` §2. It works on Android 13, which the role path does not. It is rootless *at runtime* but requires system-image write access to install.

**9. What is required for platform-signed deployment?**
Signing with the OEM's platform key, satisfying the `signature` branch directly. Unavailable to us for Samsung (their private platform key), and only meaningful with an OEM/assembler relationship or our own AOSP build. Unchanged from Android 13 to 16.

**10. Which options require NO root during normal operation?**
**All of B, C, D.** Role-provisioned system app, privileged app, and platform-signed app all run with no root, no `su`, no Magisk and no SELinux modification at runtime. Root/unlocking may be needed *once* to install onto a device whose system partition we do not otherwise control — that is a provisioning-time concern, not a runtime dependency. Option A (ordinary APK) needs no root but also never works.

**11. Which options still require OEM/system-image involvement?**
**B, C and D all do.** There is currently **no mechanism that yields `CALL_AUDIO_INTERCEPTION` on an arbitrary stock retail device without either OEM cooperation or modifying the system image.** This is the single most important negative result of this pass. `ConfirmedByAospSource`

**12. Which approach appears most realistic for SIM Relay?**
Depends on which question is being answered:

- **To prove M0 feasibility now:** the rooted privileged-app path on Device #1 (Android 13). Unchanged; that is the other track's job.
- **To ship without root:** **Option B (role) on an Android 14+ host**, with Option C (privileged app) as the fallback for hosts or OEMs where the role is not configurable. B is the cleanest because it is least-privilege (two permissions, no allowlist file, no `CAPTURE_AUDIO_OUTPUT`) and uses a documented, purpose-built Android integration point.
- **If a "buy any phone from a shop" product is required:** no non-root path exists today. The honest options are an OEM/assembler partnership, a controlled/preloaded device SKU, or accepting root-based provisioning for the host.

---

## 5. Deployment model comparison

| | A. Ordinary APK | B. Role (`SYSTEM_CALL_STREAMING`) | C. Privileged app | D. Platform-signed | E. Enterprise / device owner |
|---|---|---|---|---|---|
| Min Android | any | **14 (API 34)** | any | any | any |
| Gets `CALL_AUDIO_INTERCEPTION` | **Never** | Yes | Yes | Yes | **Never** |
| Root at runtime | No | No | No | No | No |
| System image involvement | None | **Required** (system app + `framework-res` value) | **Required** (`priv-app` + allowlist) | **Required** (platform key) | n/a |
| Extra permissions pulled in | — | `RECORD_AUDIO` (needed anyway) | whatever the allowlist grants | signature-level breadth | — |
| Allowlist XML needed | — | **No** | Yes | No | — |
| User-visible grant step | — | None | None | None | — |
| Works on Device #1 (API 33) | No | **No** (no role branch) | Yes | Yes (key unavailable) | No |
| Verdict | dead end | **preferred non-root target** | fallback / Android 13 path | OEM-only | not applicable |

**On E (enterprise), explicitly:** `DevicePolicyManager.setPermissionGrantState` is gated on `MANAGE_DEVICE_POLICY_RUNTIME_PERMISSIONS` (`frameworks/base` `android16-release` `core/java/android/app/admin/DevicePolicyManager.java:14265` and its javadoc) and applies to **runtime** permissions. `CALL_AUDIO_INTERCEPTION` is an install-time `signature|privileged|role` permission, so a device owner cannot grant it. Device-owner status does not confer signature or privileged permissions. `ConfirmedByAospSource` — this closes the question the brief asked us not to assume.

**Also closed:** becoming the **call redirection** or **call screening** app does *not* help. `android.app.role.CALL_REDIRECTION` declares no `<permissions>` at all, and `android.app.role.CALL_SCREENING` grants only the `notifications` permission-set plus a `SYSTEM_ALERT_WINDOW` app-op (`roles.xml:475-522`). Both are user-grantable, neither carries call-audio interception. `ConfirmedByAospSource`

---

## 6. Version strategy

The application floor stays at `minSdk 24`. Provisioning capability is reported per API generation rather than forcing newer assumptions into older paths.

| Host Android | Non-root provisioning options |
|---|---|
| 7.0–12L (24–32) | None for framework interception; framework APIs predate/behave differently and the role does not exist. Framework backend reports `UnsupportedByOs` below API 33 (existing behaviour, `FrameworkAudioApiBridge.MinimumApi = 33`). |
| 13 (33) | Privileged app **or** platform signature only. No role branch. Confirmed on Device #1. |
| 14–16 (34–36) | **Role path available** (system app + OEM `framework-res` value), plus privileged app and platform signature. |

No change to `minSdk`, and no Android 14+ assumption should be pushed into the API 24–33 execution paths.

---

## 7. What this means for the existing architecture

**Nothing in `CallAudioBackend`, `FrameworkInterceptionBackend`, `FrameworkAudioApiBridge`, the PCM/WAV layer, the session state machine, or the diagnostics model needs to change to support a role-based grant.** The permission check at `FrameworkInterceptionBackend.kt:71` is provisioning-agnostic by construction, which is exactly the property `AGENTS.md` §8 asked for. Do **not** fork the backend into rooted and non-root variants.

Two additive, non-architectural gaps are worth closing (see `NON_ROOT_FINDINGS.md` for merge candidates):

1. **The app cannot currently tell the three grant routes apart.** `PermissionProtectionMapper` collapses signature, privileged and role into one `SignatureOrPrivilegedOrRole` value (`PermissionSnapshot.kt:119-131`), so an Android 13 host (no role branch) and an Android 14+ host (role branch available) look identical in a qualification profile. For a track whose entire question is *which provisioning route does this device support*, that distinction is the point.
2. **There is no in-app report of role availability/holding**, which `isRoleAvailable` / `isRoleHeld` make available through the public SDK with no reflection. On-device shell probing cannot substitute: `cmd role get-role-holders` returns empty output for both an unknown role and a known role with no holders, so it cannot prove availability (verified on Device #1: a deliberately bogus role name and `SYSTEM_CALL_STREAMING` both returned empty, while `ASSISTANT` and `DIALER` returned real holders). `ConfirmedOnProductionDevice`

A minimal, honest diagnostic covering both — and reporting `System integration required` rather than offering a fake grant button — is implemented in this branch and described in `NON_ROOT_EXPERIMENT_PLAN.md` §Prototype.

---

## 8. What remains unknown

| # | Question | Status |
|---|---|---|
| 1 | Does a role-provisioned holder actually receive the grant end-to-end on a real Android 14+ build? | `NotTested` |
| 2 | Does `isPstnCallAudioInterceptable()` return true on any specific Android 14+ device? | `NotTested` — the rooted track's PSTN questions are unaffected by provisioning route |
| 3 | Are role-provisioned holders (without `CAPTURE_AUDIO_OUTPUT`/`BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION`) subject to concurrent-capture silencing during a live call? | `NotTested` — flagged in §2.3 |
| 4 | Do OEMs ship a non-empty `config_systemCallStreaming` on retail Android 14+ devices, and if so which package? | `Unknown` — measurable read-only per device |
| 5 | Does Telecom ever drive a `CallStreamingService` for a PSTN call, or is it VoIP-only in practice? | `Unknown`; source suggests VoIP-only |
| 6 | Is there an OEM/assembler partner willing to set the role holder? | Commercial question, out of scope here |

---

## 9. Primary sources

All fetched 2026-09-08 from `https://android.googlesource.com/platform/<repo>/+/refs/heads/<branch>/<path>`.

- `frameworks/base` — `core/res/AndroidManifest.xml` (android13/14/15/16-release, main); `core/res/res/values/config.xml` (android14/16-release); `media/java/android/media/AudioManager.java` (android13/14/16-release); `services/core/java/com/android/server/audio/AudioService.java` (android14/16-release); `services/permission/java/com/android/server/permission/access/permission/AppIdPermissionPolicy.kt` (android16-release); `telecomm/java/android/telecom/CallStreamingService.java` (android14/16-release, main); `packages/Shell/AndroidManifest.xml` (android16-release); `core/java/android/app/admin/DevicePolicyManager.java` (android16-release)
- `packages/modules/Permission` — `PermissionController/res/xml/roles.xml` (android13/14/16-release, main); `PermissionController/role-controller/java/com/android/role/controller/model/{Role,RoleParser,Permissions}.java`; `PermissionController/role-controller/java/com/android/role/controller/service/RoleControllerServiceImpl.java`; `service/java/com/android/role/{RoleService,RoleShellCommand}.java`; `framework-s/java/android/app/role/RoleManager.java` (android14/16-release, main)
- `frameworks/av` — `services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp` (android14/16-release); `media/utils/ServiceUtilities.cpp` (android16-release)
- Device #1 read-only ADB evidence: `dumpsys package permissions`, `cmd role get-role-holders`, `getprop` (no state modified)
