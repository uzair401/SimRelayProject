# Phase 0B-2 Device #1 Provisioning Design

## Status

Design complete. Provisioning has not started. Device #1 was reconnected on 2026-09-08 and the remaining ADB-readable baseline was captured. Every destructive or Knox-impacting action remains behind explicit user approval.

No root, shell, Samsung, MediaTek, bootloader, flashing, or provisioning behavior is present in the application or `FrameworkInterceptionBackend`.

## Frozen baseline

Repository commit:

```text
61c70c49035c3c06b909d7825f80f0cdec014672
```

APK SHA-256:

```text
fa8d513630b6dee652da78da34213c2f28ad3d27cd9c2b6809572dd1eac00dd2
```

Phase 0B-1 device evidence:

```text
model=SM-A225F
device=a22
hardware=mt6769t
android_version=13
api_level=33
build_fingerprint=samsung/a22nsxx/a22:13/TP1A.220624.014/A225FXXSBDYE1:user/release-keys
bootloader=A225FXXSBDYE1
flash_locked=1
verified_boot_state=green
vbmeta_device_state=locked
CALL_AUDIO_INTERCEPTION=false
framework_api_methods=Accessible
framework_capability=PermissionMissing
sales_code=PAK
official_csc=A225FOJMBDYE1
oem_unlock_allowed=1
knox_warranty_bit=0
```

Current qualification artifact:

```text
artifacts/device-characterization/20260908T165812-256534671Z-samsung-sm-a225f-a22
```

The idle application probe selected `framework_interception`, observed all three framework methods as accessible, and reported capability and readiness as `PermissionMissing`. No framework audio session was opened.

## Minimum privilege decision

Android 13 declares `android.permission.CALL_AUDIO_INTERCEPTION` as `signature|privileged`. It does not include the `role` protection flag on this API level.

The mechanisms are distinct:

| Mechanism | Meaning | Device #1 result |
|---|---|---|
| Root | Linux UID 0 or equivalent execution | Does not grant an Android package permission |
| System app | APK under a non-privileged system app directory | Insufficient for a privileged permission |
| Privileged app | APK under a partition's `priv-app` directory | Eligible only with the required same-partition allowlist |
| Platform-signed app | APK signed with the certificate used by the `android` package | Would satisfy the signature branch, but Samsung's private platform key is unavailable |
| Role holder | App assigned a configured Android role | Android 13 does not expose a role branch for this permission |
| Privileged-permission allowlist | Explicit package-to-permission XML on the APK's partition | Required for the privileged-app branch |

`pm grant android.permission.CALL_AUDIO_INTERCEPTION` is not a viable normal-app path and must not be attempted.

The minimum viable path is:

```text
system/priv-app/SimRelayM0/SimRelayM0.apk
system/etc/permissions/privapp-permissions-com.simrelay.m0.xml
```

The allowlist grants only:

```text
android.permission.CALL_AUDIO_INTERCEPTION
```

Because the current manifest also requests two other privileged permissions, the XML explicitly denies them:

```text
android.permission.CAPTURE_AUDIO_OUTPUT
android.permission.MODIFY_PHONE_STATE
```

This satisfies privileged-permission allowlist enforcement without granting permissions unused by the framework backend.

## Systemless provisioning design

The selected design is a minimal Magisk module because the stock system partitions are read-only and verified. Magisk's module system can merge files under a module's `system` directory into the corresponding system paths before Android scans packages.

The generated module contains only:

```text
module.prop
system/priv-app/SimRelayM0/SimRelayM0.apk
system/etc/permissions/privapp-permissions-com.simrelay.m0.xml
```

It contains no `service.sh`, `post-fs-data.sh`, system properties, SELinux rules, vendor files, mixer changes, native binaries, or additional permissions.

Build it locally only after the APK intended for installation is finalized:

```bash
tools/provisioning/build_privapp_module.sh \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --output artifacts/provisioning/simrelay-framework-privapp.zip
```

Building the ZIP is non-destructive. Installing it requires a working Magisk environment and a reboot.

## Verification

After any future provisioning change:

```bash
tools/provisioning/verify_installation.sh --serial DEVICE_SERIAL --user 0
tools/provisioning/verify_privilege.sh --serial DEVICE_SERIAL --user 0
```

Success requires all of the following observations:

```text
privileged_flag=true
call_audio_interception_granted=true
capture_audio_output_granted=false
modify_phone_state_granted=false
least_privilege_verdict=Pass
```

An unchanged data-app update can remain the active APK path while inheriting the underlying system package's `SYSTEM` and `PRIVILEGED` flags. The verification script therefore records both path and PackageManager flags instead of treating the active code path alone as authoritative.

A copied file, successful module installation message, root prompt, or system-app flag is not evidence of permission success.

Current read-only verification on Android user 0:

```text
apk_path=/data/app/.../com.simrelay.m0-.../base.apk
package_uid=10284
system_flag=false
privileged_flag=false
privileged_partition_path=false
installation_verdict=NotObservedAsPrivilegedApp
call_audio_interception_declared=true
call_audio_interception_granted=false
capture_audio_output_granted=false
modify_phone_state_granted=false
platform_permission_protection=signature|privileged
least_privilege_verdict=RequiredPermissionMissing
```

## Remaining evidence before approval

ADB-readable evidence captured on 2026-09-08:

```text
ro.csc.sales_code=PAK
ro.boot.sales_code=PAK
ril.sales_code=Unknown
ro.bootloader=A225FXXSBDYE1
ro.build.display.id=TP1A.220624.014.A225FXXSBDYE1
ro.build.version.incremental=A225FXXSBDYE1
ril.official_cscver=A225FOJMBDYE1
ro.omc.build.version=A225FOJMBDYE1
ro.csc.country_code=PAKISTAN
sys.oem_unlock_allowed=1
ro.boot.flash.locked=1
ro.boot.verifiedbootstate=green
ro.boot.vbmeta.device_state=locked
ro.boot.veritymode=enforcing
ro.boot.warranty_bit=0
ro.boot.kg=0x4
ro.boot.em.status=0x0
```

The numeric or hexadecimal OEM properties are recorded as evidence and are not treated as authoritative human-readable KnoxGuard or unlock status. Still required:

```text
Download Mode OEM Lock state
Download Mode KnoxGuard/RMM state
Download Mode Warranty Void state
Magisk ramdisk determination
```

The Download Mode values are required because ADB properties do not prove that this regional unit permits unlocking or that KnoxGuard will accept unofficial images.

## Firmware and recovery prerequisites

Current known firmware identity:

```text
model=SM-A225F
bootloader=A225FXXSBDYE1
build_display_id=TP1A.220624.014.A225FXXSBDYE1
sales_code=PAK
official_csc=A225FOJMBDYE1
```

Samsung's Pakistan support site confirms the `SM-A225F/DSN` model family but does not publish the complete Odin firmware archive. The exact recovery archive has not been acquired or hashed. No firmware may be patched or flashed until a complete package matching `SM-A225F`, sales code `PAK`, CSC build `A225FOJMBDYE1`, and bootloader revision `B` has been obtained from Samsung's firmware service and its contained versions have been verified.

Before unlocking or flashing, retain outside the repository:

- A complete user-data backup verified by restoring representative files.
- Recovery codes and exports for applications whose data cannot be restored automatically.
- The exact Samsung firmware package obtained from Samsung's firmware service for `SM-A225F`, the observed sales code, and a build compatible with the installed bootloader revision.
- Original `BL`, `AP`, `CP`, `CSC`, and `HOME_CSC` archives plus SHA-256 hashes.
- The locally built SimRelay APK and its SHA-256 hash.
- The official Magisk APK used for the operation and its SHA-256 hash.
- Access to a host with Samsung USB drivers and a tested Samsung flashing tool.

Never patch an image from another model, region, build, or device. Never use a patched image supplied by another person. Patch the complete matching `AP` archive on Device #1, as required by Magisk's Samsung installation procedure.

## Recovery routes

Download Mode is the primary recovery route. For this hardware family it is entered from power-off with a USB-connected host while holding both volume keys, then following the on-screen warning. Merely entering and exiting Download Mode does not authorize unlocking or flashing.

If a Magisk module causes a boot problem, first disable or remove that module through Magisk's supported recovery mechanism. If Android cannot boot, use the matching stock firmware package and Samsung Download Mode for a full firmware restore.

Magisk explicitly warns against restoring only an individual stock `boot`, `init_boot`, `recovery`, or `vbmeta` after installation. Recovery from an incompatible boot image is a complete Samsung firmware restore, normally with a data wipe. Samsung rollback fuses can prevent installing an older bootloader revision.

## Destructive approval gate

The likely provisioning path requires two destructive stages:

### Stage A: unlock the Samsung bootloader

Exact action:

```text
Enable OEM unlocking in Developer options.
Power off and enter Download Mode.
Long-press Volume Up on the unlock screen and confirm the bootloader unlock.
```

Data effect: mandatory factory reset.

Reversibility: the bootloader can generally be relocked only after returning completely to compatible stock firmware, but erased data cannot be recovered without a backup. Relocking an incompletely restored device can prevent boot.

Knox effect: unlocking permits unofficial images. Loading the later Magisk-patched Samsung image will irreversibly trip the Knox Warranty Bit. Do not assume the device can return to Knox-trusted state.

Risk: failed unlock state, KnoxGuard refusal, failed setup, or loss of all local data.

Recovery: matching stock firmware through Download Mode, followed by device setup and backup restoration.

### Stage B: install Magisk on Samsung firmware

Exact action after matching firmware is acquired and verified:

```text
Copy the matching AP tar to Device #1.
Use the official Magisk app on Device #1 to patch that AP tar.
Copy the patched tar back with adb pull, not MTP.
In Samsung Odin, assign original BL, patched AP, original CP, and original CSC.
Use CSC, not HOME_CSC, for the initial Magisk installation.
Flash and accept the required factory reset.
Complete Magisk's additional setup and reboot.
```

Data effect: a second full data wipe is required by Magisk's Samsung installation procedure.

Reversibility: Magisk software can be removed and stock firmware restored, but the Knox Warranty Bit cannot be reset.

Knox effect: Magisk installation will permanently trip the Knox Warranty Bit. Samsung Pay, Secure Folder, Knox Workspace, device-bound protected data, enterprise attestation, banking applications, and DRM-dependent features may stop working or remain unavailable after stock restoration.

Risk: boot loop or failure to boot if the wrong AP/BL/CP/CSC combination, wrong regional firmware, interrupted flash, rollback-incompatible bootloader, or incorrect ramdisk/recovery choice is used. Loss of power or USB during flashing increases brick risk.

Recovery: re-enter Download Mode and perform a complete restore using verified matching stock `BL`, `AP`, `CP`, and `CSC`. Restore user data only after stock boot is confirmed.

No part of Stage A or Stage B is approved by the Phase 0B-2 request itself.

## Primary references

- Android 13 platform permission declaration: <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/res/AndroidManifest.xml>
- Android 13 framework call-redirection APIs: <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/media/java/android/media/AudioManager.java>
- AOSP privileged permission allowlisting: <https://source.android.com/docs/core/permissions/perms-allowlist>
- Magisk installation, including Samsung requirements: <https://github.com/topjohnwu/Magisk/blob/master/docs/install.md>
- Magisk module layout and system overlay behavior: <https://github.com/topjohnwu/Magisk/blob/master/docs/guides.md>
- Samsung Knox Warranty Bit behavior: <https://docs.samsungknox.com/admin/knox-platform-for-enterprise/faq/>
- Samsung Pakistan support page for `SM-A225F/DSN`: <https://www.samsung.com/pk/support/model/SM-A225FZKHMEB/>
