# Phase 0B-2 Interim Report

Date: 2026-09-08

Status: stopped before privileged provisioning

## Executive result

Device #1 was characterized through authorized, read-only ADB access. SimRelay remains an ordinary data application without `android.permission.CALL_AUDIO_INTERCEPTION`. The framework interception methods are present and reflection-accessible, but the capability probe correctly stops at `PermissionMissing`.

No bootloader unlock, factory reset, firmware download, firmware patch, flash, Magisk installation, root action, permission grant, reboot, SELinux change, verified-boot change, or system/vendor modification was performed.

The framework backend has not failed. Its real PSTN viability remains undetermined until the required permission is legitimately granted. RX, TX, full-duplex, isolation, feedback, and second-call results are therefore `NotTested`, not failures.

## 1. Device state before provisioning

```text
manufacturer=samsung
model=SM-A225F
device=a22
product=a22nsxx
board=a22
hardware=mt6769t
android_version=13
api_level=33
security_patch=2025-05-01
build_fingerprint=samsung/a22nsxx/a22:13/TP1A.220624.014/A225FXXSBDYE1:user/release-keys
build_id=TP1A.220624.014
build_display_id=TP1A.220624.014.A225FXXSBDYE1
build_incremental=A225FXXSBDYE1
build_type=user
build_tags=release-keys
bootloader=A225FXXSBDYE1
sales_code=PAK
official_csc=A225FOJMBDYE1
country_code=PAKISTAN
```

Repository baseline:

```text
commit=61c70c49035c3c06b909d7825f80f0cdec014672
apk_sha256=fa8d513630b6dee652da78da34213c2f28ad3d27cd9c2b6809572dd1eac00dd2
```

## 2. Provisioning mechanism chosen

The selected design is a least-privilege privileged application installation with a same-partition privileged-permission allowlist, delivered systemlessly through a minimal Magisk module.

The proposed system overlay contains only:

```text
module.prop
system/priv-app/SimRelayM0/SimRelayM0.apk
system/etc/permissions/privapp-permissions-com.simrelay.m0.xml
```

The allowlist grants only:

```text
android.permission.CALL_AUDIO_INTERCEPTION
```

It explicitly denies these currently unused privileged permissions:

```text
android.permission.CAPTURE_AUDIO_OUTPUT
android.permission.MODIFY_PHONE_STATE
```

This is a provisioning design only. It has not been installed or tested on Device #1.

## 3. Why that mechanism is required

On this Android 13 build, the platform defines `CALL_AUDIO_INTERCEPTION` with `signature|privileged` protection. It is not a runtime permission and cannot be legitimately granted to the current normal data application with `pm grant`.

The relevant mechanisms are not equivalent:

| Mechanism | Result |
|---|---|
| Root access | Does not itself grant an Android package permission |
| Normal system app | Does not satisfy privileged-app eligibility |
| Privileged app | Eligible when installed under `priv-app` and correctly allowlisted |
| Platform signing | Would satisfy the signature branch, but Samsung's private platform key is unavailable |
| Role holder | This Android 13 permission declaration has no role protection branch |
| Privileged allowlist | Required for the privileged-app branch and must reside on the APK's partition |

The stock verified partitions are not writable through ordinary ADB. A systemless overlay is the minimum design that avoids direct persistent edits to those partitions, but obtaining the environment needed to apply it would still require a separate destructive Samsung provisioning workflow.

## 4. Destructive actions performed

None.

The following were specifically not performed:

- bootloader unlock
- factory reset or data wipe
- Download Mode transition
- firmware or partition flashing
- Magisk installation
- boot, recovery, `init_boot`, AP, or vbmeta patching
- verified-boot disablement
- root or `su` execution
- privileged permission grant attempt
- SELinux or hidden-API-policy changes

## 5. Final boot and security state

The observed state remained unchanged:

```text
ro.boot.flash.locked=1
ro.boot.verifiedbootstate=green
ro.boot.vbmeta.device_state=locked
ro.boot.veritymode=enforcing
ro.boot.warranty_bit=0
ro.boot.kg=0x4
ro.boot.em.status=0x0
sys.oem_unlock_allowed=1
adb_shell_uid=2000
su_on_shell_path=false
```

`sys.oem_unlock_allowed=1` is only recorded evidence that unlocking may be permitted. It does not mean the bootloader is unlocked. The hexadecimal OEM properties were not translated into unsupported KnoxGuard conclusions. Download Mode values remain manually unverified.

## 6. Final SimRelay permission state

```text
package=com.simrelay.m0
android_user=0
package_uid=10284
apk_location=/data/app
system_flag=false
privileged_flag=false
CALL_AUDIO_INTERCEPTION declared=true granted=false
CAPTURE_AUDIO_OUTPUT declared=true granted=false
MODIFY_PHONE_STATE declared=true granted=false
RECORD_AUDIO declared=true granted=true
READ_PHONE_STATE declared=true granted=true
```

Verifier result:

```text
installation_verdict=NotObservedAsPrivilegedApp
least_privilege_verdict=RequiredPermissionMissing
```

## 7. Framework API accessibility

All required framework methods were observed as reflection-accessible:

```text
isPstnCallAudioInterceptable=Accessible
getCallDownlinkExtractionAudioRecord=Accessible
getCallUplinkInjectionAudioTrack=Accessible
selected_backend=framework_interception
```

No framework audio object was created during the probe.

## 8. `isPstnCallAudioInterceptable` result

```text
result=Unknown
capability=PermissionMissing
session_readiness=PermissionMissing
call_state=Idle
audio_mode=MODE_NORMAL
```

The bridge did not invoke the protected capability call without its required permission. The idle phone was not classified as unsupported.

## 9. Downlink RX result

```text
result=NotTested
reason=CALL_AUDIO_INTERCEPTION is not granted
```

No downlink session was opened and no call-audio WAV was created.

## 10. Uplink TX result

```text
result=NotTested
reason=CALL_AUDIO_INTERCEPTION is not granted
```

No uplink session was opened and no tone was injected into a call.

## 11. Physical microphone exclusion result

```text
result=NotTested
```

The required digital-silence and deterministic-tone PSTN experiments were not started.

## 12. Host speaker isolation result

```text
result=NotTested
```

No PSTN downlink capture session was opened.

## 13. Full-duplex result

```text
result=NotTested
```

No simultaneous framework RX/TX session was opened.

## 14. Recapture and feedback result

```text
result=Inconclusive
reason=No controlled injected-tone PSTN experiment was performed
```

No echo cancellation or routing workaround was introduced.

## 15. Second-call result

```text
result=NotTested
```

No first call-audio session occurred, so teardown and second-call recovery could not be evaluated.

## 16. Artifacts created

Primary connected-device artifact:

```text
artifacts/device-characterization/20260908T165812-256534671Z-samsung-sm-a225f-a22/
```

It contains:

```text
device.txt
build-properties.txt
boot-security.txt
permissions.txt
package.txt
audio.txt
telecom.txt
telephony.txt
probe-summary.txt
qualification-profile.txt
app-application.json
app-permissions.json
app-probe.json
app-qualification.json
app-events.txt
```

An earlier same-session artifact was also created before the fresh application probe:

```text
artifacts/device-characterization/20260908T165358-497480942Z-samsung-sm-a225f-a22/
```

The stored telecom and telephony output was reduced to approved diagnostic fields. No phone number, IMSI, ICCID, subscriber identifier, SMS, contact, call history, or account data was intentionally collected.

## 17. Build and test status

Baseline and post-tooling validation completed successfully:

```text
./gradlew testDebugUnitTest = PASS
./gradlew lintDebug = PASS
./gradlew assembleDebug = PASS
git diff --check = PASS
shell syntax validation = PASS
```

Combined final Gradle result:

```text
BUILD SUCCESSFUL
51 actionable tasks: 1 executed, 50 up-to-date
```

The APK hash remained unchanged:

```text
fa8d513630b6dee652da78da34213c2f28ad3d27cd9c2b6809572dd1eac00dd2
```

## 18. Generic code changes

No Android application code, framework backend code, manifest, Gradle configuration, audio logic, or UI code was changed during Phase 0B-2 work.

The generic application remains free of root, Magisk, Samsung, MediaTek, shell, flashing, and vendor-specific behavior.

Generic external provisioning tools added:

```text
tools/provisioning/adb_target.sh
tools/provisioning/verify_installation.sh
tools/provisioning/verify_privilege.sh
tools/provisioning/build_privapp_module.sh
```

The verification tools support explicit ADB serial, package name, and Android user selection. They verify actual PackageManager flags and grants rather than inferring success from copied files.

## 19. Device-specific provisioning changes

No device-specific provisioning change was applied.

Device #1 planning and evidence are isolated in:

```text
docs/provisioning/PHASE_0B2_DEVICE1.md
docs/provisioning/PHASE_0B2_REPORT.md
artifacts/device-characterization/20260908T165812-256534671Z-samsung-sm-a225f-a22/
```

No manufacturer or chipset branch was added to application logic.

## 20. Remaining blockers

1. `CALL_AUDIO_INTERCEPTION` is not granted.
2. SimRelay is not installed as a privileged application.
3. Device #1 has a locked bootloader and green verified boot.
4. The exact matching complete Samsung recovery firmware archive has not been acquired and hashed.
5. Download Mode OEM Lock, KnoxGuard/RMM, and Warranty Void displays have not been manually verified.
6. Magisk ramdisk/recovery installation mode has not been determined from an exact matching AP archive.
7. User-data backup and recovery readiness have not been confirmed.
8. Any bootloader unlock, wipe, patched-AP flash, Magisk installation, and Knox-impacting action requires a separate explicit approval after the recovery prerequisites are complete.

Samsung's public Pakistan support page confirms the `SM-A225F/DSN` model family but does not provide the complete Odin firmware archive required for recovery.

## 21. FrameworkInterceptionBackend viability on Device #1

```text
status=UndeterminedButStillCandidate
```

Positive evidence:

- Device #1 runs API 33.
- All three framework methods are present and accessible through the isolated bridge.
- The framework backend is selected.
- The probe fails for one specific, expected reason: the required permission is missing.
- No evidence currently rules out the framework implementation or OEM audio policy path.

Missing evidence:

- protected interceptability result after permission grant
- real PSTN downlink extraction
- real PSTN uplink injection
- full-duplex operation
- microphone and speaker isolation
- feedback classification
- clean second-call behavior

It would be incorrect to descend into `LegacyPrivilegedBackend` or `VendorAudioBackend` based on the current result.

## 22. Recommendation for the next phase

Do not begin Phase 0B-3 or vendor audio work.

If destructive provisioning remains desired, continue Phase 0B-2 only after:

1. acquiring and hashing the exact matching complete firmware package;
2. verifying its model, CSC, bootloader revision, and component versions;
3. completing and validating user-data backups;
4. manually recording Download Mode OEM Lock, KnoxGuard/RMM, and Warranty Void state;
5. determining the correct Magisk Samsung patch target from that exact AP package; and
6. presenting the required destructive-action disclosure and receiving explicit user approval.

After legitimate provisioning, verify the actual permission grant before any PSTN test. If it is granted, rerun the idle framework probe and then perform the controlled RX, TX, microphone exclusion, speaker isolation, short full-duplex, recapture, and second-call smoke tests in that order.

If no destructive device change is authorized, Phase 0B-2 remains safely stopped at the permission boundary with the framework path neither proven nor disproven.

## Relevant `SimRelayM0` Logcat

```text
event=probe_started detail="callState=Unknown audioMode=0"
event=call_state_changed detail="from=Unknown to=Idle"
event=backend_selected detail="backend=framework_interception"
event=framework_api_state detail="interceptability=Accessible downlink=Accessible uplink=Accessible"
event=pstn_interceptable detail="value=Unknown"
event=capability_changed detail="backend=framework_interception state=PermissionMissing"
event=readiness_changed detail="state=PermissionMissing callState=Idle audioMode=0"
event=permission_state detail="name=android.permission.CALL_AUDIO_INTERCEPTION declared=true granted=false protection=SignatureOrPrivilegedOrRole"
event=probe_completed detail="backend=framework_interception capability=PermissionMissing readiness=PermissionMissing"
```

The complete structured events, including monotonic `elapsed_ns` values, are preserved in the primary artifact.

## Files created or changed

```text
docs/provisioning/PHASE_0B2_DEVICE1.md
docs/provisioning/PHASE_0B2_REPORT.md
tools/provisioning/adb_target.sh
tools/provisioning/verify_installation.sh
tools/provisioning/verify_privilege.sh
tools/provisioning/build_privapp_module.sh
artifacts/device-characterization/20260908T165358-497480942Z-samsung-sm-a225f-a22/
artifacts/device-characterization/20260908T165812-256534671Z-samsung-sm-a225f-a22/
```

No commit was created.

## Primary references

- Android 13 permission declaration: <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/res/AndroidManifest.xml>
- Android 13 framework APIs: <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/media/java/android/media/AudioManager.java>
- AOSP privileged-permission allowlisting: <https://source.android.com/docs/core/permissions/perms-allowlist>
- Magisk installation requirements: <https://github.com/topjohnwu/Magisk/blob/master/docs/install.md>
- Magisk module layout: <https://github.com/topjohnwu/Magisk/blob/master/docs/guides.md>
- Samsung Knox Warranty Bit behavior: <https://docs.samsungknox.com/admin/knox-platform-for-enterprise/faq/>
- Samsung Pakistan `SM-A225F/DSN` support: <https://www.samsung.com/pk/support/model/SM-A225FZKHMEB/>
