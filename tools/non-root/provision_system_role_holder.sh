#!/usr/bin/env bash

set -euo pipefail

provision_package="com.simrelay.m0"
provision_apk="app/build/outputs/apk/debug/app-debug.apk"
provision_overlay_apk="build/non-root/role-overlay/SimRelayRoleOverlay.apk"
provision_overlay_package="com.simrelay.roleoverlay"
provision_serial=""
provision_adb="${SIMRELAY_ADB_BIN:-adb}"
provision_system_dir="/system/app/SimRelayM0"
provision_overlay_dir="/product/overlay"

while (($# > 0)); do
    case "$1" in
        --serial)
            [[ $# -ge 2 ]] || { echo "--serial requires a value" >&2; exit 2; }
            provision_serial="$2"
            shift 2
            ;;
        --package)
            [[ $# -ge 2 ]] || { echo "--package requires a value" >&2; exit 2; }
            provision_package="$2"
            shift 2
            ;;
        --apk)
            [[ $# -ge 2 ]] || { echo "--apk requires a value" >&2; exit 2; }
            provision_apk="$2"
            shift 2
            ;;
        --overlay-apk)
            [[ $# -ge 2 ]] || { echo "--overlay-apk requires a value" >&2; exit 2; }
            provision_overlay_apk="$2"
            shift 2
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 2
            ;;
    esac
done

[[ "$provision_package" =~ ^[A-Za-z0-9._]+$ ]] || { echo "Invalid package name" >&2; exit 2; }
[[ -f "$provision_apk" ]] || { echo "Application APK not found: $provision_apk" >&2; exit 1; }
[[ -f "$provision_overlay_apk" ]] || { echo "Overlay APK not found: $provision_overlay_apk" >&2; exit 1; }
command -v "$provision_adb" >/dev/null 2>&1 || { echo "adb was not found" >&2; exit 1; }

mapfile -t connected_devices < <("$provision_adb" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
if [[ -z "$provision_serial" ]]; then
    if ((${#connected_devices[@]} != 1)); then
        echo "Expected exactly one authorized device or use --serial" >&2
        exit 1
    fi
    provision_serial="${connected_devices[0]}"
elif ! printf '%s\n' "${connected_devices[@]}" | grep -Fxq "$provision_serial"; then
    echo "Requested device is not connected and authorized" >&2
    exit 1
fi

adb_shell() {
    "$provision_adb" -s "$provision_serial" shell "$@"
}

build_type="$(adb_shell getprop ro.build.type | tr -d '\r')"
qemu_flag="$(adb_shell getprop ro.boot.qemu | tr -d '\r')"
kernel_qemu_flag="$(adb_shell getprop ro.kernel.qemu | tr -d '\r')"
device_name="$(adb_shell getprop ro.product.device | tr -d '\r')"

is_emulator="false"
if [[ "$provision_serial" == emulator-* || "$qemu_flag" == "1" || "$kernel_qemu_flag" == "1" ]]; then
    is_emulator="true"
fi

if [[ "$is_emulator" != "true" ]]; then
    echo "REFUSING: target does not look like an emulator (serial=$provision_serial device=$device_name)." >&2
    echo "This script modifies a system partition and is only for emulator experiments." >&2
    exit 1
fi

if [[ "$build_type" != "userdebug" && "$build_type" != "eng" ]]; then
    echo "REFUSING: build type '$build_type' is not userdebug or eng; system partition is not writable." >&2
    exit 1
fi

echo "target=$provision_serial device=$device_name build_type=$build_type emulator=$is_emulator"

echo "== requesting adb root =="
"$provision_adb" -s "$provision_serial" root >/dev/null 2>&1 || true
"$provision_adb" -s "$provision_serial" wait-for-device
adb_user="$(adb_shell id -u | tr -d '\r')"
echo "adb_shell_uid=$adb_user"
if [[ "$adb_user" != "0" ]]; then
    echo "REFUSING: adb is not running as root on this image, so the system partition cannot be written." >&2
    exit 1
fi

echo "== remounting system read-write =="
"$provision_adb" -s "$provision_serial" remount

echo "== installing application as a system app =="
adb_shell mkdir -p "$provision_system_dir"
"$provision_adb" -s "$provision_serial" push "$provision_apk" "$provision_system_dir/SimRelayM0.apk"
adb_shell chmod 644 "$provision_system_dir/SimRelayM0.apk"

echo "== installing default-holder overlay onto a system partition =="
adb_shell mkdir -p "$provision_overlay_dir"
"$provision_adb" -s "$provision_serial" push "$provision_overlay_apk" "$provision_overlay_dir/SimRelayRoleOverlay.apk"
adb_shell chmod 644 "$provision_overlay_dir/SimRelayRoleOverlay.apk"

echo "== rebooting to apply system image changes =="
"$provision_adb" -s "$provision_serial" reboot
"$provision_adb" -s "$provision_serial" wait-for-device
until [[ "$(adb_shell getprop sys.boot_completed | tr -d '\r')" == "1" ]]; do
    sleep 3
done

echo "== enabling overlay if it is not already enabled =="
adb_shell cmd overlay enable --user 0 "$provision_overlay_package" 2>&1 | tr -d '\r' || true

echo "== verification =="
echo "overlay_state:"
adb_shell cmd overlay list android 2>&1 | tr -d '\r' | grep -i "simrelay" || echo "  (overlay not listed for target android)"
echo "config_systemCallStreaming:"
adb_shell cmd overlay lookup android "android:string/config_systemCallStreaming" 2>&1 | tr -d '\r'
echo "role_holders:"
adb_shell cmd role get-role-holders android.app.role.SYSTEM_CALL_STREAMING 2>&1 | tr -d '\r'
echo "package_flags:"
adb_shell dumpsys package "$provision_package" 2>&1 | grep -E 'codePath|flags=|privateFlags=' | head -5
echo "permission_state:"
adb_shell dumpsys package "$provision_package" 2>&1 \
    | grep -E 'CALL_AUDIO_INTERCEPTION|RECORD_AUDIO|CAPTURE_AUDIO_OUTPUT' \
    | sed 's/^[[:space:]]*//' | sort -u
