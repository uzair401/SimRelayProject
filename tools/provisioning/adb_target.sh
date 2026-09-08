#!/usr/bin/env bash

set -euo pipefail

provision_package="com.simrelay.m0"
provision_serial=""
provision_user="0"
provision_adb="${SIMRELAY_ADB_BIN:-adb}"

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
        --user)
            [[ $# -ge 2 ]] || { echo "--user requires a value" >&2; exit 2; }
            provision_user="$2"
            shift 2
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 2
            ;;
    esac
done

[[ "$provision_package" =~ ^[A-Za-z0-9._]+$ ]] || { echo "Invalid package name" >&2; exit 2; }
[[ "$provision_user" =~ ^[0-9]+$ ]] || { echo "Invalid Android user ID" >&2; exit 2; }
command -v "$provision_adb" >/dev/null 2>&1 || { echo "adb was not found" >&2; exit 1; }

mapfile -t provision_devices < <("$provision_adb" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
if [[ -z "$provision_serial" ]]; then
    if ((${#provision_devices[@]} != 1)); then
        echo "Expected exactly one authorized device or use --serial" >&2
        exit 1
    fi
    provision_serial="${provision_devices[0]}"
elif ! printf '%s\n' "${provision_devices[@]}" | grep -Fxq "$provision_serial"; then
    echo "Requested device is not connected and authorized" >&2
    exit 1
fi

adb_target() {
    "$provision_adb" -s "$provision_serial" "$@"
}

adb_target_shell() {
    adb_target shell "$@"
}
