#!/usr/bin/env bash

set -euo pipefail

probe_package="com.simrelay.m0"
probe_serial=""
probe_adb="${SIMRELAY_ADB_BIN:-adb}"

while (($# > 0)); do
    case "$1" in
        --serial)
            [[ $# -ge 2 ]] || { echo "--serial requires a value" >&2; exit 2; }
            probe_serial="$2"
            shift 2
            ;;
        --package)
            [[ $# -ge 2 ]] || { echo "--package requires a value" >&2; exit 2; }
            probe_package="$2"
            shift 2
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 2
            ;;
    esac
done

[[ "$probe_package" =~ ^[A-Za-z0-9._]+$ ]] || { echo "Invalid package name" >&2; exit 2; }

command -v "$probe_adb" >/dev/null 2>&1 || { echo "adb was not found" >&2; exit 1; }

mapfile -t connected_devices < <("$probe_adb" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
if [[ -z "$probe_serial" ]]; then
    if ((${#connected_devices[@]} != 1)); then
        echo "Expected exactly one authorized device or use --serial" >&2
        exit 1
    fi
    probe_serial="${connected_devices[0]}"
elif ! printf '%s\n' "${connected_devices[@]}" | grep -Fxq "$probe_serial"; then
    echo "Requested device is not connected and authorized" >&2
    exit 1
fi

adb_call() {
    "$probe_adb" -s "$probe_serial" "$@"
}

shell_call() {
    adb_call shell "$@"
}

get_prop() {
    local property_name="$1"
    local property_value
    property_value="$(shell_call getprop "$property_name" 2>/dev/null | tr -d '\r\n')"
    if [[ -z "$property_value" ]]; then
        printf 'Unknown'
    else
        printf '%s' "$property_value"
    fi
}

safe_slug() {
    printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed -E 's/^-+//; s/-+$//'
}

redact_stream() {
    sed -E \
        -e 's/((phone(number)?|incoming(number)?|subscriber(id)?|imsi|iccid|msisdn|line1(number)?|account(id|handle)?|address)[[:space:]]*[:=][[:space:]]*)[^[:space:],;]+/\1<redacted>/Ig' \
        -e 's/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/<redacted-email>/g' \
        -e 's/([[:xdigit:]]{2}:){5}[[:xdigit:]]{2}/<redacted-mac>/Ig'
}

probe_tmp="$(mktemp -d)"
trap 'rm -rf -- "$probe_tmp"' EXIT

manufacturer="$(get_prop ro.product.manufacturer)"
model="$(get_prop ro.product.model)"
device="$(get_prop ro.product.device)"
timestamp="$(date -u +%Y%m%dT%H%M%S-%N)Z"
device_slug="$(safe_slug "$manufacturer-$model-$device")"
[[ -n "$device_slug" ]] || device_slug="unknown-device"
script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_directory="$(cd "$script_directory/.." && pwd)"
artifact_directory="$project_directory/artifacts/device-characterization/$timestamp-$device_slug"
mkdir -p "$artifact_directory"

build_properties=(
    ro.product.manufacturer
    ro.product.brand
    ro.product.model
    ro.product.device
    ro.product.name
    ro.product.board
    ro.hardware
    ro.hardware.chipname
    ro.board.platform
    ro.soc.manufacturer
    ro.soc.model
    ro.bootloader
    ro.build.fingerprint
    ro.build.id
    ro.build.display.id
    ro.build.version.release
    ro.build.version.sdk
    ro.build.version.security_patch
    ro.build.type
    ro.build.tags
    ro.product.cpu.abilist
    ro.product.cpu.abi
    ro.product.cpu.abi2
)

{
    printf 'schema_version=1\n'
    printf 'captured_at_utc=%s\n' "$timestamp"
    printf 'manufacturer=%s\n' "$manufacturer"
    printf 'model=%s\n' "$model"
    printf 'device=%s\n' "$device"
    printf 'product=%s\n' "$(get_prop ro.product.name)"
    printf 'board=%s\n' "$(get_prop ro.product.board)"
    printf 'hardware=%s\n' "$(get_prop ro.hardware)"
    printf 'android_version=%s\n' "$(get_prop ro.build.version.release)"
    printf 'api_level=%s\n' "$(get_prop ro.build.version.sdk)"
    printf 'security_patch=%s\n' "$(get_prop ro.build.version.security_patch)"
    printf 'build_fingerprint=%s\n' "$(get_prop ro.build.fingerprint)"
} >"$artifact_directory/device.txt"

{
    for property_name in "${build_properties[@]}"; do
        printf '%s=%s\n' "$property_name" "$(get_prop "$property_name")"
    done
} >"$artifact_directory/build-properties.txt"

shell_identity="$(shell_call id 2>/dev/null | tr -d '\r\n' || true)"
su_path="$(shell_call which su 2>/dev/null | tr -d '\r\n' || true)"
flash_locked="$(get_prop ro.boot.flash.locked)"
verified_boot="$(get_prop ro.boot.verifiedbootstate)"
vbmeta_state="$(get_prop ro.boot.vbmeta.device_state)"
if [[ "$flash_locked" == "1" ]]; then
    bootloader_state="Locked"
elif [[ "$flash_locked" == "0" ]]; then
    bootloader_state="Unlocked"
elif [[ "$vbmeta_state" == "locked" ]]; then
    bootloader_state="Locked"
elif [[ "$vbmeta_state" == "unlocked" ]]; then
    bootloader_state="Unlocked"
else
    bootloader_state="Unknown"
fi
if [[ "$shell_identity" == uid=0* ]]; then
    adb_root_state="Active"
    adb_root_capability="Available"
else
    adb_root_state="NotActive"
    adb_root_capability="UnknownNotTested"
fi
if [[ -n "$su_path" ]]; then
    su_state="Present"
else
    su_state="NotFoundOnShellPath"
    su_path="Unknown"
fi

{
    printf 'ro.boot.flash.locked=%s\n' "$flash_locked"
    printf 'ro.boot.verifiedbootstate=%s\n' "$verified_boot"
    printf 'ro.boot.vbmeta.device_state=%s\n' "$vbmeta_state"
    printf 'bootloader_unlock_state=%s\n' "$bootloader_state"
    printf 'build_type=%s\n' "$(get_prop ro.build.type)"
    printf 'build_tags=%s\n' "$(get_prop ro.build.tags)"
    printf 'ro.debuggable=%s\n' "$(get_prop ro.debuggable)"
    printf 'ro.secure=%s\n' "$(get_prop ro.secure)"
    printf 'adb_shell_identity=%s\n' "$shell_identity"
    printf 'adb_root_state=%s\n' "$adb_root_state"
    printf 'adb_root_capability=%s\n' "$adb_root_capability"
    printf 'su_state=%s\n' "$su_state"
    printf 'su_path=%s\n' "$su_path"
} | redact_stream >"$artifact_directory/boot-security.txt"

package_dump="$probe_tmp/package"
if shell_call dumpsys package "$probe_package" >"$package_dump" 2>&1; then
    {
        printf 'package=%s\n' "$probe_package"
        shell_call pm path "$probe_package" 2>/dev/null || true
        shell_call cmd package list packages -U "$probe_package" 2>/dev/null || true
        awk '
            /^[[:space:]]*Package \[/ { in_package=1; print; next }
            in_package && /^[[:space:]]+(userId|codePath|resourcePath|versionCode|versionName|flags|privateFlags|primaryCpuAbi|secondaryCpuAbi|usesNonSdkApi)=/ { print; next }
            in_package && /^[[:space:]]*requested permissions:/ { section="requested"; print; next }
            in_package && /^[[:space:]]*install permissions:/ { section="install"; print; next }
            in_package && /^[[:space:]]*User 0:/ { section="user0"; print; next }
            section == "requested" && /^[[:space:]]+[A-Za-z0-9_.]+permission\.[A-Z0-9_]+[[:space:]]*$/ { print; next }
            section == "install" && /^[[:space:]]+[A-Za-z0-9_.]+permission\.[A-Z0-9_]+: granted=/ { print; next }
            section == "user0" && /^[[:space:]]*runtime permissions:/ { print; next }
            section == "user0" && /^[[:space:]]+[A-Za-z0-9_.]+permission\.[A-Z0-9_]+: granted=/ { print; next }
            /^[[:space:]]*(User [0-9]+:|Queries:|Dexopt state:)/ { if ($0 !~ /User 0:/) section="" }
        ' "$package_dump"
    } | redact_stream >"$artifact_directory/package.txt"
else
    {
        printf 'package=%s\n' "$probe_package"
        printf 'install_state=NotInstalled\n'
    } >"$artifact_directory/package.txt"
fi

permissions=(
    android.permission.CALL_AUDIO_INTERCEPTION
    android.permission.CAPTURE_AUDIO_OUTPUT
    android.permission.MODIFY_PHONE_STATE
    android.permission.RECORD_AUDIO
    android.permission.READ_PHONE_STATE
)

{
    printf 'package=%s\n' "$probe_package"
    for permission_name in "${permissions[@]}"; do
        if grep -Fq "$permission_name" "$package_dump" 2>/dev/null; then
            declared="true"
        else
            declared="false"
        fi
        if grep -F "$permission_name" "$package_dump" 2>/dev/null | grep -Fq 'granted=true'; then
            granted="true"
        else
            granted="false"
        fi
        case "$permission_name" in
            android.permission.CALL_AUDIO_INTERCEPTION|android.permission.CAPTURE_AUDIO_OUTPUT|android.permission.MODIFY_PHONE_STATE)
                protection="privileged_or_signature_or_role"
                ;;
            android.permission.RECORD_AUDIO|android.permission.READ_PHONE_STATE)
                protection="runtime"
                ;;
            *)
                protection="unknown"
                ;;
        esac
        if [[ "$declared" == "false" ]]; then
            state="NotDeclared"
        elif [[ "$granted" == "true" ]]; then
            state="DeclaredAndGranted"
        elif [[ "$protection" == "runtime" ]]; then
            state="DeclaredRuntimePermissionNotGranted"
        else
            state="DeclaredPrivilegedPermissionUnavailableToNormalApp"
        fi
        printf '%s declared=%s granted=%s protection=%s state=%s\n' \
            "$permission_name" "$declared" "$granted" "$protection" "$state"
    done
    printf 'requested_permissions:\n'
    awk '
        /requested permissions:/ { active=1; next }
        active && /install permissions:|runtime permissions:|User [0-9]+:/ { active=0 }
        active && /^[[:space:]]+[A-Za-z0-9_.]+permission\.[A-Z0-9_]+[[:space:]]*$/ { print }
    ' "$package_dump" 2>/dev/null | redact_stream
} >"$artifact_directory/permissions.txt"

audio_raw="$probe_tmp/audio"
if shell_call dumpsys audio >"$audio_raw" 2>&1; then
    awk 'BEGIN { IGNORECASE=1 }
        /^[[:space:]]*mModeOwnerPid:/ { next }
        /^[[:space:]]*Audio mode/ ||
        /^[[:space:]]*mMode[=:]/ ||
        /^[[:space:]]*mic mute/ ||
        /^[[:space:]]*speakerphone/ ||
        /^[[:space:]]*Connected devices:/ ||
        /^[[:space:]]*Preferred devices for strategy:/ ||
        /^[[:space:]]*Computed Preferred communication device:/ ||
        /^[[:space:]]*Applied Preferred communication device:/ ||
        /^[[:space:]]*Active communication device:/ { print }
    ' "$audio_raw" | sed -E 's/[[:space:]]+mCurrentImeUid=.*$//' | redact_stream >"$artifact_directory/audio.txt"
    [[ -s "$artifact_directory/audio.txt" ]] || printf 'status=NoSafeFieldsObserved\n' >"$artifact_directory/audio.txt"
else
    printf 'status=Unavailable\n' >"$artifact_directory/audio.txt"
fi

telecom_raw="$probe_tmp/telecom"
if shell_call dumpsys telecom >"$telecom_raw" 2>&1; then
    awk 'BEGIN { IGNORECASE=1 }
        /audio mode|audio route|call audio|endpoint|ringer|mute/ {
            if ($0 !~ /address|handle|number|account|caller|contact|tel:/) print
        }
    ' "$telecom_raw" | redact_stream >"$artifact_directory/telecom.txt"
    [[ -s "$artifact_directory/telecom.txt" ]] || printf 'status=NoSafeFieldsObserved\n' >"$artifact_directory/telecom.txt"
else
    printf 'status=Unavailable\n' >"$artifact_directory/telecom.txt"
fi

telephony_raw="$probe_tmp/telephony"
if shell_call dumpsys telephony.registry >"$telephony_raw" 2>&1; then
    awk '
        /^[[:space:]]*mCallState=/ ||
        /^[[:space:]]*mDataConnectionState=/ ||
        /^[[:space:]]*mDataActivity=/ ||
        /^[[:space:]]*mVoiceActivationState=/ ||
        /^[[:space:]]*mDataActivationState=/ { print }
    ' "$telephony_raw" | redact_stream >"$artifact_directory/telephony.txt"
else
    printf 'status=Unavailable\n' >"$artifact_directory/telephony.txt"
fi

logcat_raw="$probe_tmp/logcat"
adb_call logcat -d -s SimRelayM0:I '*:S' >"$logcat_raw" 2>&1 || true
if grep -q 'event=' "$logcat_raw"; then
    redact_stream <"$logcat_raw" >"$artifact_directory/probe-summary.txt"
    app_probe_state="ObservedFromCurrentLogcat"
else
    printf 'app_probe=NotTested\n' >"$artifact_directory/probe-summary.txt"
    app_probe_state="NotTested"
fi

mapfile -t app_run_directories < <(
    adb_call exec-out run-as "$probe_package" find files/diagnostics \
        -mindepth 1 -maxdepth 1 -type d 2>/dev/null || true
)
if ((${#app_run_directories[@]} > 0)); then
    latest_app_run="$(printf '%s\n' "${app_run_directories[@]}" | tr -d '\r' | sort | tail -n 1)"
    app_diagnostic_files=(
        application.json
        permissions.json
        probe.json
        qualification.json
        app.log
    )
    for app_file in "${app_diagnostic_files[@]}"; do
        local_name="app-$app_file"
        [[ "$app_file" == "app.log" ]] && local_name="app-events.txt"
        app_file_raw="$probe_tmp/$app_file"
        if adb_call exec-out run-as "$probe_package" cat "$latest_app_run/$app_file" >"$app_file_raw" 2>/dev/null; then
            redact_stream <"$app_file_raw" >"$artifact_directory/$local_name"
        fi
    done
fi

latest_event_value() {
    local event_name="$1"
    local field_name="$2"
    local required_text="${3:-}"
    local event_line
    event_line="$(grep "event=$event_name " "$logcat_raw" | grep -F "$required_text" | tail -n 1 || true)"
    printf '%s' "$event_line" | sed -n -E "s/.*(^|[[:space:]\"]$field_name=)([^[:space:]\"]+).*/\2/p"
}

framework_interceptability="$(latest_event_value framework_api_state interceptability)"
framework_downlink="$(latest_event_value framework_api_state downlink)"
framework_uplink="$(latest_event_value framework_api_state uplink)"
framework_capability="$(latest_event_value capability_changed state)"
session_readiness="$(latest_event_value readiness_changed state)"
selected_backend="$(latest_event_value backend_selected backend)"
pstn_interceptable="$(latest_event_value pstn_interceptable value)"
call_audio_permission="$(latest_event_value permission_state granted android.permission.CALL_AUDIO_INTERCEPTION)"

if [[ "$framework_interceptability" == "Accessible" && "$framework_downlink" == "Accessible" && "$framework_uplink" == "Accessible" ]]; then
    framework_api_present="true"
    framework_api_accessible="true"
elif [[ "$framework_interceptability" == "NotPresent" || "$framework_downlink" == "NotPresent" || "$framework_uplink" == "NotPresent" ]]; then
    framework_api_present="false"
    framework_api_accessible="NotTested"
elif [[ -n "$framework_interceptability$framework_downlink$framework_uplink" ]]; then
    framework_api_present="Unknown"
    framework_api_accessible="false"
else
    framework_api_present="Unknown"
    framework_api_accessible="Unknown"
fi

[[ -n "$framework_capability" ]] || framework_capability="Unknown"
[[ -n "$session_readiness" ]] || session_readiness="Unknown"
[[ -n "$selected_backend" ]] || selected_backend="Unknown"
[[ -n "$pstn_interceptable" ]] || pstn_interceptable="Unknown"
[[ -n "$call_audio_permission" ]] || call_audio_permission="Unknown"
if [[ "$framework_capability" == "PermissionMissing" ]]; then
    required_provisioning="privileged_or_role_permission"
else
    required_provisioning="Unknown"
fi

{
    printf 'schema_version=1\n'
    printf 'manufacturer=%s\n' "$manufacturer"
    printf 'model=%s\n' "$model"
    printf 'device=%s\n' "$device"
    printf 'board=%s\n' "$(get_prop ro.product.board)"
    printf 'hardware=%s\n' "$(get_prop ro.hardware)"
    printf 'android_version=%s\n' "$(get_prop ro.build.version.release)"
    printf 'api_level=%s\n' "$(get_prop ro.build.version.sdk)"
    printf 'build_fingerprint=%s\n' "$(get_prop ro.build.fingerprint)"
    printf 'security_state=%s\n' "$bootloader_state/$verified_boot/$vbmeta_state"
    printf 'framework_api_present=%s\n' "$framework_api_present"
    printf 'framework_api_accessible=%s\n' "$framework_api_accessible"
    printf 'framework_capability=%s\n' "$framework_capability"
    printf 'call_audio_interception_permission_granted=%s\n' "$call_audio_permission"
    printf 'pstn_interceptable=%s\n' "$pstn_interceptable"
    printf 'session_readiness=%s\n' "$session_readiness"
    printf 'supported_sample_rates=NotTested\n'
    printf 'rx_result=NotTested\n'
    printf 'tx_result=NotTested\n'
    printf 'full_duplex_result=NotTested\n'
    printf 'mic_isolation=NotTested\n'
    printf 'speaker_isolation=NotTested\n'
    printf 'latency=NotTested\n'
    printf 'stability=NotTested\n'
    printf 'required_backend=%s\n' "$selected_backend"
    printf 'required_provisioning=%s\n' "$required_provisioning"
    printf 'app_probe_state=%s\n' "$app_probe_state"
} >"$artifact_directory/qualification-profile.txt"

printf 'artifact_directory=%s\n' "$artifact_directory"
