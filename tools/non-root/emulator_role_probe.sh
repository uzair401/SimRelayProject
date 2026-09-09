#!/usr/bin/env bash

set -euo pipefail

probe_package="com.simrelay.m0"
probe_role="android.app.role.SYSTEM_CALL_STREAMING"
probe_serial=""
probe_label=""
probe_adb="${SIMRELAY_ADB_BIN:-adb}"
probe_out_root="artifacts/non-root-emulator"

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
        --label)
            [[ $# -ge 2 ]] || { echo "--label requires a value" >&2; exit 2; }
            probe_label="$2"
            shift 2
            ;;
        --out)
            [[ $# -ge 2 ]] || { echo "--out requires a value" >&2; exit 2; }
            probe_out_root="$2"
            shift 2
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 2
            ;;
    esac
done

[[ "$probe_package" =~ ^[A-Za-z0-9._]+$ ]] || { echo "Invalid package name" >&2; exit 2; }
[[ -z "$probe_label" || "$probe_label" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "Invalid label" >&2; exit 2; }

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

adb_shell() {
    "$probe_adb" -s "$probe_serial" shell "$@"
}

api_level="$(adb_shell getprop ro.build.version.sdk | tr -d '\r')"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
run_name="${timestamp}-api${api_level}${probe_label:+-$probe_label}"
run_dir="${probe_out_root}/${run_name}"
mkdir -p "$run_dir"

echo "device=$probe_serial api=$api_level"
echo "run_dir=$run_dir"

{
    echo "probe_generated_at_utc=$timestamp"
    echo "adb_serial=$probe_serial"
    for property in \
        ro.build.version.sdk ro.build.version.release ro.build.version.security_patch \
        ro.build.fingerprint ro.build.type ro.build.tags ro.product.model \
        ro.product.device ro.product.name ro.hardware ro.debuggable ro.secure \
        ro.boot.verifiedbootstate
    do
        echo "$property=$(adb_shell getprop "$property" | tr -d '\r')"
    done
} > "$run_dir/build.txt"

{
    echo "== declared protection level of CALL_AUDIO_INTERCEPTION =="
    adb_shell dumpsys package permissions \
        | grep -A3 'android.permission.CALL_AUDIO_INTERCEPTION' || echo "not found"
    echo
    echo "== declared protection level of RECORD_AUDIO =="
    adb_shell dumpsys package permissions \
        | grep -A3 'android.permission.RECORD_AUDIO:' || echo "not found"
} > "$run_dir/permission-declarations.txt" 2>&1

{
    echo "== role holders =="
    for role in \
        "$probe_role" \
        android.app.role.DIALER \
        android.app.role.CALL_REDIRECTION \
        android.app.role.CALL_SCREENING \
        android.app.role.NOT_A_REAL_ROLE_CONTROL
    do
        echo "role=$role holders=[$(adb_shell cmd role get-role-holders "$role" 2>&1 | tr -d '\r' | tr '\n' ' ')]"
    done
} > "$run_dir/role-holders.txt" 2>&1

{
    echo "== effective platform value of config_systemCallStreaming =="
    adb_shell cmd overlay lookup android "android:string/config_systemCallStreaming" 2>&1 \
        | tr -d '\r' || echo "lookup unavailable"
    echo
    echo "== overlays targeting android =="
    adb_shell cmd overlay list android 2>&1 | tr -d '\r' || echo "overlay list unavailable"
} > "$run_dir/platform-config.txt" 2>&1

{
    echo "== package flags =="
    adb_shell dumpsys package "$probe_package" 2>&1 \
        | grep -E 'versionCode|codePath|flags=|privateFlags=|apkSigningVersion' \
        | head -20 || echo "(package not installed or no matching fields)"
    echo
    echo "== requested and granted permissions =="
    adb_shell dumpsys package "$probe_package" 2>&1 \
        | grep -E 'CALL_AUDIO_INTERCEPTION|RECORD_AUDIO|CAPTURE_AUDIO_OUTPUT|MODIFY_PHONE_STATE|BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION|READ_PHONE_STATE' \
        | sed 's/^[[:space:]]*//' | sort -u || echo "(package not installed)"
    echo
    echo "== platform declarations of the four tracked permissions =="
    for permission in \
        android.permission.CALL_AUDIO_INTERCEPTION \
        android.permission.RECORD_AUDIO \
        android.permission.CAPTURE_AUDIO_OUTPUT \
        android.permission.BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION
    do
        line="$(adb_shell dumpsys package permissions 2>&1 \
            | grep -A3 "Permission \[$permission\]" | grep 'prot=' | head -1 | tr -d '\r' || true)"
        echo "$permission ${line:-(not declared on this build)}"
    done
} > "$run_dir/package-state.txt" 2>&1

{
    echo "== call streaming qualification component visibility =="
    adb_shell cmd package query-services -a android.telecom.CallStreamingService 2>&1 \
        | tr -d '\r' || echo "query-services unavailable"
} > "$run_dir/qualification-component.txt" 2>&1

{
    echo "== audio mode and telephony summary =="
    adb_shell dumpsys audio 2>&1 | grep -E '^- mode:|Mode dump|audio mode' | head -10 \
        || echo "(no audio mode line matched)"
    adb_shell getprop gsm.sim.state | tr -d '\r' || true
} > "$run_dir/audio-telephony.txt" 2>&1

echo "collected: build.txt permission-declarations.txt role-holders.txt platform-config.txt package-state.txt qualification-component.txt audio-telephony.txt"
