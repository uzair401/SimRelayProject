#!/usr/bin/env bash

set -euo pipefail

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_directory/adb_target.sh" "$@"

verification_tmp="$(mktemp -d)"
trap 'rm -rf -- "$verification_tmp"' EXIT
package_dump="$verification_tmp/package.txt"

if ! adb_target_shell dumpsys package "$provision_package" >"$package_dump" 2>&1; then
    printf 'package=%s\n' "$provision_package"
    printf 'installed=false\n'
    exit 3
fi

if ! grep -Fq "Package [$provision_package]" "$package_dump"; then
    printf 'package=%s\n' "$provision_package"
    printf 'installed=false\n'
    exit 3
fi

apk_path="$(adb_target_shell pm path "$provision_package" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -n 1)"
uid_line="$(adb_target_shell pm list packages --user "$provision_user" -U "$provision_package" 2>/dev/null | tr -d '\r' | head -n 1 || true)"

case "$apk_path" in
    /system/priv-app/*|/product/priv-app/*|/system_ext/priv-app/*|/vendor/priv-app/*)
        privileged_path="true"
        ;;
    *)
        privileged_path="false"
        ;;
esac

if grep -E 'privateFlags=.*((^|[[:space:]])PRIVILEGED([[:space:]\]]|$)|PRIVATE_FLAG_PRIVILEGED)' "$package_dump" >/dev/null; then
    privileged_flag="true"
else
    privileged_flag="false"
fi

if grep -E 'flags=.*(^|[[:space:]])SYSTEM([[:space:]\]]|$)' "$package_dump" >/dev/null; then
    system_flag="true"
else
    system_flag="false"
fi

printf 'package=%s\n' "$provision_package"
printf 'android_user=%s\n' "$provision_user"
printf 'installed=true\n'
printf 'apk_path=%s\n' "${apk_path:-Unknown}"
printf 'package_uid=%s\n' "${uid_line:-Unknown}"
printf 'system_flag=%s\n' "$system_flag"
printf 'privileged_flag=%s\n' "$privileged_flag"
printf 'privileged_partition_path=%s\n' "$privileged_path"

if [[ "$privileged_flag" == "true" && ( "$privileged_path" == "true" || "$system_flag" == "true" ) ]]; then
    printf 'installation_verdict=PrivilegedAppObserved\n'
    exit 0
fi

printf 'installation_verdict=NotObservedAsPrivilegedApp\n'
exit 3
