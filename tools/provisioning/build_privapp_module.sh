#!/usr/bin/env bash

set -euo pipefail

module_apk=""
module_output=""
module_package="com.simrelay.m0"

while (($# > 0)); do
    case "$1" in
        --apk)
            [[ $# -ge 2 ]] || { echo "--apk requires a value" >&2; exit 2; }
            module_apk="$2"
            shift 2
            ;;
        --output)
            [[ $# -ge 2 ]] || { echo "--output requires a value" >&2; exit 2; }
            module_output="$2"
            shift 2
            ;;
        --package)
            [[ $# -ge 2 ]] || { echo "--package requires a value" >&2; exit 2; }
            module_package="$2"
            shift 2
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 2
            ;;
    esac
done

[[ -n "$module_apk" ]] || { echo "--apk is required" >&2; exit 2; }
[[ -f "$module_apk" ]] || { echo "APK does not exist: $module_apk" >&2; exit 2; }
[[ "$module_package" =~ ^[A-Za-z0-9._]+$ ]] || { echo "Invalid package name" >&2; exit 2; }
command -v zip >/dev/null 2>&1 || { echo "zip was not found" >&2; exit 1; }
command -v apkanalyzer >/dev/null 2>&1 || { echo "apkanalyzer was not found" >&2; exit 1; }

observed_package="$(apkanalyzer manifest application-id "$module_apk")"
[[ "$observed_package" == "$module_package" ]] || {
    echo "APK package $observed_package does not match $module_package" >&2
    exit 2
}

if [[ -z "$module_output" ]]; then
    module_output="$(pwd)/simrelay-framework-privapp.zip"
fi

mkdir -p "$(dirname "$module_output")"
module_output_directory="$(cd "$(dirname "$module_output")" && pwd)"
module_output="$module_output_directory/$(basename "$module_output")"
[[ ! -e "$module_output" ]] || { echo "Output already exists: $module_output" >&2; exit 2; }
module_tmp="$(mktemp -d)"
trap 'rm -rf -- "$module_tmp"' EXIT
module_root="$module_tmp/simrelay_framework_privapp"
mkdir -p "$module_root/system/priv-app/SimRelayM0"
mkdir -p "$module_root/system/etc/permissions"
cp "$module_apk" "$module_root/system/priv-app/SimRelayM0/SimRelayM0.apk"

printf '%s\n' \
    'id=simrelay_framework_privapp' \
    'name=SimRelay Framework Privileged App' \
    'version=0B-2' \
    'versionCode=1' \
    'author=SimRelay' \
    'description=Least-privilege systemless provisioning for framework PSTN interception' \
    >"$module_root/module.prop"

printf '%s\n' \
    '<?xml version="1.0" encoding="utf-8"?>' \
    '<permissions>' \
    "    <privapp-permissions package=\"$module_package\">" \
    '        <permission name="android.permission.CALL_AUDIO_INTERCEPTION" />' \
    '        <deny-permission name="android.permission.CAPTURE_AUDIO_OUTPUT" />' \
    '        <deny-permission name="android.permission.MODIFY_PHONE_STATE" />' \
    '    </privapp-permissions>' \
    '</permissions>' \
    >"$module_root/system/etc/permissions/privapp-permissions-$module_package.xml"

(
    cd "$module_root"
    zip -q -r "$module_output" .
)

printf 'module=%s\n' "$module_output"
printf 'apk_sha256=%s\n' "$(sha256sum "$module_apk" | awk '{print $1}')"
