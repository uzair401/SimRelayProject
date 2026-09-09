#!/usr/bin/env bash

set -euo pipefail

overlay_holder_package="com.simrelay.m0"
overlay_package="com.simrelay.roleoverlay"
overlay_min_sdk="34"
overlay_priority="9999"
overlay_static="true"
overlay_out_dir="build/non-root/role-overlay"
overlay_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
overlay_build_tools=""

while (($# > 0)); do
    case "$1" in
        --holder)
            [[ $# -ge 2 ]] || { echo "--holder requires a value" >&2; exit 2; }
            overlay_holder_package="$2"
            shift 2
            ;;
        --out)
            [[ $# -ge 2 ]] || { echo "--out requires a value" >&2; exit 2; }
            overlay_out_dir="$2"
            shift 2
            ;;
        --min-sdk)
            [[ $# -ge 2 ]] || { echo "--min-sdk requires a value" >&2; exit 2; }
            overlay_min_sdk="$2"
            shift 2
            ;;
        --priority)
            [[ $# -ge 2 ]] || { echo "--priority requires a value" >&2; exit 2; }
            overlay_priority="$2"
            shift 2
            ;;
        --static)
            [[ $# -ge 2 ]] || { echo "--static requires true or false" >&2; exit 2; }
            overlay_static="$2"
            shift 2
            ;;
        --build-tools)
            [[ $# -ge 2 ]] || { echo "--build-tools requires a value" >&2; exit 2; }
            overlay_build_tools="$2"
            shift 2
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 2
            ;;
    esac
done

[[ "$overlay_holder_package" =~ ^[A-Za-z0-9._]+$ ]] || { echo "Invalid holder package" >&2; exit 2; }
[[ "$overlay_min_sdk" =~ ^[0-9]+$ ]] || { echo "Invalid min sdk" >&2; exit 2; }
[[ -n "$overlay_sdk" ]] || { echo "Set ANDROID_HOME or ANDROID_SDK_ROOT" >&2; exit 1; }

if [[ -z "$overlay_build_tools" ]]; then
    overlay_build_tools="$(ls -1d "$overlay_sdk"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)"
fi
[[ -n "$overlay_build_tools" && -d "$overlay_build_tools" ]] || { echo "No build-tools found" >&2; exit 1; }

platform_jar="$(ls -1 "$overlay_sdk"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
[[ -n "$platform_jar" ]] || { echo "No platform android.jar found" >&2; exit 1; }

aapt2="$overlay_build_tools/aapt2"
zipalign="$overlay_build_tools/zipalign"
apksigner="$overlay_build_tools/apksigner"
for tool in "$aapt2" "$zipalign" "$apksigner"; do
    [[ -x "$tool" ]] || { echo "Missing build tool: $tool" >&2; exit 1; }
done
command -v keytool >/dev/null 2>&1 || { echo "keytool was not found" >&2; exit 1; }

source_dir="$overlay_out_dir/src"
rm -rf "$source_dir"
mkdir -p "$source_dir/res/values" "$overlay_out_dir"

cat > "$source_dir/AndroidManifest.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$overlay_package">
    <uses-sdk android:minSdkVersion="$overlay_min_sdk" />
    <application android:hasCode="false" />
    <overlay
        android:targetPackage="android"
        android:priority="$overlay_priority"
        android:isStatic="$overlay_static" />
</manifest>
EOF

cat > "$source_dir/res/values/config.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="config_systemCallStreaming" translatable="false">$overlay_holder_package</string>
</resources>
EOF

"$aapt2" compile --dir "$source_dir/res" -o "$overlay_out_dir/compiled.zip"
"$aapt2" link \
    -o "$overlay_out_dir/unsigned.apk" \
    --manifest "$source_dir/AndroidManifest.xml" \
    -I "$platform_jar" \
    "$overlay_out_dir/compiled.zip"

keystore="$overlay_out_dir/overlay-debug.keystore"
if [[ ! -f "$keystore" ]]; then
    keytool -genkeypair -keystore "$keystore" -storepass android -keypass android \
        -alias overlay -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=SimRelay Non-Root Overlay, OU=Experiment, O=SimRelay, C=US" >/dev/null 2>&1
fi

rm -f "$overlay_out_dir/aligned.apk"
"$zipalign" -f 4 "$overlay_out_dir/unsigned.apk" "$overlay_out_dir/aligned.apk"
"$apksigner" sign \
    --ks "$keystore" --ks-pass pass:android --key-pass pass:android \
    --min-sdk-version "$overlay_min_sdk" \
    --out "$overlay_out_dir/SimRelayRoleOverlay.apk" \
    "$overlay_out_dir/aligned.apk"

echo "overlay_package=$overlay_package"
echo "holder_package=$overlay_holder_package"
echo "overlay_apk=$overlay_out_dir/SimRelayRoleOverlay.apk"
echo "This script only builds the overlay. Installing it onto a system partition is a separate explicit step."
