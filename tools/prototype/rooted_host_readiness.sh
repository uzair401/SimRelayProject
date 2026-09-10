#!/usr/bin/env bash

set -euo pipefail

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_directory/../provisioning/adb_target.sh" "$@"

readiness_tmp="$(mktemp -d)"
trap 'rm -rf -- "$readiness_tmp"' EXIT

redact_stream() {
    sed -E \
        -e 's/((phone(number)?|incoming(number)?|subscriber(id)?|imsi|iccid|msisdn|line1(number)?|account(id|handle)?|address)[[:space:]]*[:=][[:space:]]*)[^[:space:],;]+/\1<redacted>/Ig' \
        -e 's/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/<redacted-email>/g'
}

property_value() {
    local property_name="$1"
    local value
    value="$(adb_target_shell getprop "$property_name" 2>/dev/null | tr -d '\r\n')"
    printf '%s' "${value:-Unknown}"
}

field_or_unknown() {
    local value="$1"
    printf '%s' "${value:-Unknown}"
}

permission_granted() {
    local permission_name="$1"
    if grep -F "$permission_name" "$package_dump" | grep -Fq 'granted=true'; then
        printf 'true'
    else
        printf 'false'
    fi
}

permission_declared() {
    local permission_name="$1"
    if grep -Fq "$permission_name" "$package_dump"; then
        printf 'true'
    else
        printf 'false'
    fi
}

latest_event() {
    local event_name="$1"
    grep -F "event=$event_name" "$logcat_dump" | tail -n 1 || true
}

timestamp="$(date -u +%Y%m%dT%H%M%S-%N)Z"
manufacturer="$(property_value ro.product.manufacturer)"
model="$(property_value ro.product.model)"
device="$(property_value ro.product.device)"
device_slug="$(printf '%s' "$manufacturer-$model-$device" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed -E 's/^-+//; s/-+$//')"
[[ -n "$device_slug" ]] || device_slug="unknown-device"
project_directory="$(cd "$script_directory/../.." && pwd)"
artifact_relative="artifacts/rooted-host-readiness/$timestamp-$device_slug"
artifact_directory="$project_directory/$artifact_relative"
mkdir -p "$artifact_directory"

package_dump="$readiness_tmp/package.txt"
logcat_dump="$readiness_tmp/logcat.txt"
adb_target_shell dumpsys package "$provision_package" >"$package_dump" 2>&1 || true
adb_target logcat -d -s SimRelayM0:I SimRelayPrototype:I '*:S' >"$logcat_dump" 2>&1 || true

{
    printf 'captured_at_utc=%s\n' "$timestamp"
    printf 'manufacturer=%s\n' "$manufacturer"
    printf 'model=%s\n' "$model"
    printf 'device=%s\n' "$device"
    printf 'api_level=%s\n' "$(property_value ro.build.version.sdk)"
    printf 'android_version=%s\n' "$(property_value ro.build.version.release)"
    printf 'build_fingerprint=%s\n' "$(property_value ro.build.fingerprint)"
    printf 'security_patch=%s\n' "$(property_value ro.build.version.security_patch)"
    printf 'flash_locked=%s\n' "$(property_value ro.boot.flash.locked)"
    printf 'verified_boot_state=%s\n' "$(property_value ro.boot.verifiedbootstate)"
    printf 'vbmeta_device_state=%s\n' "$(property_value ro.boot.vbmeta.device_state)"
    printf 'shell_identity=%s\n' "$(adb_target_shell id 2>/dev/null | tr -d '\r\n' || true)"
    printf 'su_path=%s\n' "$(field_or_unknown "$(adb_target_shell which su 2>/dev/null | tr -d '\r\n' || true)")"
} | redact_stream >"$artifact_directory/device.txt"

apk_path="$(adb_target_shell pm path "$provision_package" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -n 1 || true)"
if grep -Fq "Package [$provision_package]" "$package_dump"; then
    installed="true"
else
    installed="false"
fi
if grep -E 'privateFlags=.*(PRIVILEGED|PRIVATE_FLAG_PRIVILEGED)' "$package_dump" >/dev/null; then
    privileged_flag="true"
else
    privileged_flag="false"
fi
if grep -E 'flags=.*(^|[[:space:]])SYSTEM([[:space:]\]]|$)' "$package_dump" >/dev/null; then
    system_flag="true"
else
    system_flag="false"
fi
{
    printf 'package=%s\n' "$provision_package"
    printf 'installed=%s\n' "$installed"
    printf 'apk_path=%s\n' "$(field_or_unknown "$apk_path")"
    printf 'system_app=%s\n' "$system_flag"
    printf 'privileged_app=%s\n' "$privileged_flag"
    grep -E '^[[:space:]]*(userId|codePath|versionCode|versionName|flags|privateFlags)=' "$package_dump" || true
} | redact_stream >"$artifact_directory/package.txt"

record_audio_appop="$(adb_target_shell appops get --user "$provision_user" "$provision_package" RECORD_AUDIO 2>/dev/null | tr -d '\r' | tr '\n' ' ' | awk '{$1=$1};1' || true)"
permissions=(
    android.permission.CALL_AUDIO_INTERCEPTION
    android.permission.RECORD_AUDIO
    android.permission.CAPTURE_AUDIO_OUTPUT
    android.permission.MODIFY_PHONE_STATE
)
{
    for permission_name in "${permissions[@]}"; do
        printf '%s declared=%s granted=%s\n' \
            "$permission_name" \
            "$(permission_declared "$permission_name")" \
            "$(permission_granted "$permission_name")"
    done
    printf 'record_audio_appop=%s\n' "$(field_or_unknown "$record_audio_appop")"
} >"$artifact_directory/permissions.txt"

framework_api_event="$(latest_event framework_api_state)"
backend_event="$(latest_event backend_selected)"
capability_event="$(latest_event capability_changed)"
readiness_event="$(latest_event readiness_changed)"
interceptable_event="$(latest_event pstn_interceptable)"
{
    printf 'framework_api=%s\n' "$(field_or_unknown "$framework_api_event")"
    printf 'backend=%s\n' "$(field_or_unknown "$backend_event")"
    printf 'capability=%s\n' "$(field_or_unknown "$capability_event")"
    printf 'readiness=%s\n' "$(field_or_unknown "$readiness_event")"
    printf 'pstn_interceptable=%s\n' "$(field_or_unknown "$interceptable_event")"
} | redact_stream >"$artifact_directory/framework.txt"

{
    printf 'session_open=%s\n' "$(field_or_unknown "$(latest_event session_open_started)")"
    printf 'capture_started=%s\n' "$(field_or_unknown "$(latest_event capture_started)")"
    printf 'first_rx=%s\n' "$(field_or_unknown "$(latest_event first_rx)")"
    printf 'capture_failure=%s\n' "$(field_or_unknown "$(grep -F 'event=failure' "$logcat_dump" | grep -E 'capture|downlink|read' | tail -n 1 || true)")"
} | redact_stream >"$artifact_directory/audio-rx.txt"

{
    printf 'session_open=%s\n' "$(field_or_unknown "$(latest_event session_open_started)")"
    printf 'injection_started=%s\n' "$(field_or_unknown "$(latest_event injection_started)")"
    printf 'first_tx=%s\n' "$(field_or_unknown "$(latest_event first_tx)")"
    printf 'injection_failure=%s\n' "$(field_or_unknown "$(grep -F 'event=failure' "$logcat_dump" | grep -E 'injection|uplink|write' | tail -n 1 || true)")"
} | redact_stream >"$artifact_directory/audio-tx.txt"

{
    printf 'call_state=%s\n' "$(field_or_unknown "$(latest_event call_state_changed)")"
    printf 'audio_mode=%s\n' "$(field_or_unknown "$(latest_event audio_mode_changed)")"
    printf 'full_duplex=%s\n' "$(field_or_unknown "$(latest_event full_duplex_started)")"
    printf 'stop=%s\n' "$(field_or_unknown "$(latest_event stop_requested)")"
    printf 'released=%s\n' "$(field_or_unknown "$(latest_event session_released)")"
    printf 'prototype_cleanup=%s\n' "$(field_or_unknown "$(latest_event session_cleanup)")"
} | redact_stream >"$artifact_directory/session.txt"

redact_stream <"$logcat_dump" >"$artifact_directory/logcat.txt"

{
    printf 'artifact_directory=%s\n' "$artifact_relative"
    printf 'installed=%s\n' "$installed"
    printf 'privileged_app=%s\n' "$privileged_flag"
    printf 'call_audio_interception_granted=%s\n' "$(permission_granted android.permission.CALL_AUDIO_INTERCEPTION)"
    printf 'record_audio_granted=%s\n' "$(permission_granted android.permission.RECORD_AUDIO)"
    printf 'framework_probe_observed=%s\n' "$([[ -n "$framework_api_event" ]] && printf true || printf false)"
    printf 'first_rx_observed=%s\n' "$([[ -n "$(latest_event first_rx)" ]] && printf true || printf false)"
    printf 'first_tx_observed=%s\n' "$([[ -n "$(latest_event first_tx)" ]] && printf true || printf false)"
    printf 'session_release_observed=%s\n' "$([[ -n "$(latest_event session_released)" ]] && printf true || printf false)"
} >"$artifact_directory/summary.txt"

printf '%s\n' "$artifact_directory"
