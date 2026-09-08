#!/usr/bin/env bash

set -euo pipefail

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_directory/adb_target.sh" "$@"

verification_tmp="$(mktemp -d)"
trap 'rm -rf -- "$verification_tmp"' EXIT
package_dump="$verification_tmp/package.txt"
permission_dump="$verification_tmp/permissions.txt"

if ! adb_target_shell dumpsys package "$provision_package" >"$package_dump" 2>&1; then
    printf 'package=%s\n' "$provision_package"
    printf 'installed=false\n'
    exit 3
fi

adb_target_shell dumpsys package permissions >"$permission_dump" 2>&1 || true

permission_declared() {
    local permission_name="$1"
    awk -v permission="$permission_name" '
        /^[[:space:]]*requested permissions:/ { active=1; next }
        active && /^[[:space:]]*(install permissions:|User [0-9]+:)/ { active=0 }
        active && $1 == permission { found=1 }
        END { if (found) print "true"; else print "false" }
    ' "$package_dump"
}

permission_granted() {
    local permission_name="$1"
    if grep -F "$permission_name: granted=true" "$package_dump" >/dev/null; then
        printf 'true'
    else
        printf 'false'
    fi
}

call_audio_permission="android.permission.CALL_AUDIO_INTERCEPTION"
capture_audio_permission="android.permission.CAPTURE_AUDIO_OUTPUT"
modify_phone_permission="android.permission.MODIFY_PHONE_STATE"
call_audio_declared="$(permission_declared "$call_audio_permission")"
call_audio_granted="$(permission_granted "$call_audio_permission")"
capture_audio_granted="$(permission_granted "$capture_audio_permission")"
modify_phone_granted="$(permission_granted "$modify_phone_permission")"
permission_definition="$(awk -v permission="$call_audio_permission" '
    index($0, "Permission [" permission "]") { active=1 }
    active && !index($0, "Permission [" permission "]") && /^[[:space:]]*Permission \[/ { active=0 }
    active { print }
' "$permission_dump" | tr '\n' ' ' | sed -E 's/[[:space:]]+/ /g; s/^ //; s/ $//')"

printf 'package=%s\n' "$provision_package"
printf 'android_user=%s\n' "$provision_user"
printf 'installed=true\n'
printf 'call_audio_interception_declared=%s\n' "$call_audio_declared"
printf 'call_audio_interception_granted=%s\n' "$call_audio_granted"
printf 'capture_audio_output_granted=%s\n' "$capture_audio_granted"
printf 'modify_phone_state_granted=%s\n' "$modify_phone_granted"
printf 'platform_permission_definition=%s\n' "${permission_definition:-Unknown}"

if [[ "$call_audio_granted" == "true" && "$capture_audio_granted" == "false" && "$modify_phone_granted" == "false" ]]; then
    printf 'least_privilege_verdict=Pass\n'
    exit 0
fi

if [[ "$call_audio_granted" != "true" ]]; then
    printf 'least_privilege_verdict=RequiredPermissionMissing\n'
else
    printf 'least_privilege_verdict=UnexpectedPrivilegedPermissionGranted\n'
fi
exit 3
