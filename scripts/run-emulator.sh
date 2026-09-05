#!/usr/bin/env bash
# Builds, installs, and launches Papr on an Android emulator.
#
# Usage: scripts/run-emulator.sh [avd-name]
#   AVD name defaults to $PAPR_AVD, falling back to the first available AVD.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK_DIR" ] && [ -f local.properties ]; then
    SDK_DIR="$(sed -n 's/^sdk.dir=//p' local.properties)"
fi
if [ -z "$SDK_DIR" ]; then
    echo "error: could not determine Android SDK location (set ANDROID_HOME or local.properties)" >&2
    exit 1
fi

ADB="$SDK_DIR/platform-tools/adb"
EMULATOR="$SDK_DIR/emulator/emulator"
AVDMANAGER=$(find "$SDK_DIR/cmdline-tools" -name avdmanager 2>/dev/null | head -1 || true)

AVD_NAME="${1:-${PAPR_AVD:-}}"
if [ -z "$AVD_NAME" ]; then
    AVD_NAME=$("$EMULATOR" -list-avds | head -1)
fi
if [ -z "$AVD_NAME" ]; then
    echo "error: no AVDs found. Create one in Android Studio's Device Manager first." >&2
    exit 1
fi

if ! "$ADB" devices | grep -q "device$"; then
    echo "Booting emulator: $AVD_NAME"
    nohup "$EMULATOR" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
        > /tmp/papr-emulator.log 2>&1 &
    "$ADB" wait-for-device
    until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
        sleep 3
    done
    echo "Emulator booted."
else
    echo "Using already-running device/emulator."
fi

echo "Building and installing debug APK..."
./gradlew installDebug --console=plain

echo "Launching Papr..."
"$ADB" shell am start -n com.papr.scanner/.MainActivity
