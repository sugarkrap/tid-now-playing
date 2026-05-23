#!/usr/bin/env bash
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/home/makaron/android-sdk}"
ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.config/.android/avd}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
AVD="tid_now_playing"
APK="app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="com.tid.nowplaying"
ACTIVITY="$PACKAGE/.MainActivity"

# ── helpers ──────────────────────────────────────────────────────────────────

log()  { printf '\e[1;34m▶\e[0m %s\n' "$*"; }
ok()   { printf '\e[1;32m✔\e[0m %s\n' "$*"; }
err()  { printf '\e[1;31m✖\e[0m %s\n' "$*" >&2; exit 1; }

emulator_serial() {
    "$ADB" devices | awk '/emulator-[0-9]+\tdevice/{print $1; exit}'
}

emulator_running() {
    [[ -n "$(emulator_serial)" ]]
}

wait_for_boot() {
    local serial=$1
    log "Waiting for boot to complete…"
    "$ADB" -s "$serial" wait-for-device
    until [[ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
        sleep 2
    done
    ok "Device ready ($serial)"
}

# ── commands ─────────────────────────────────────────────────────────────────

cmd_emulator() {
    if emulator_running; then
        ok "Emulator already running ($(emulator_serial))"
        return
    fi
    log "Starting emulator ($AVD)…"
    ANDROID_AVD_HOME="$ANDROID_AVD_HOME" \
        "$EMULATOR" -avd "$AVD" -no-audio -gpu swiftshader_indirect \
        >/tmp/emulator-"$AVD".log 2>&1 &
    disown
    # Give it a moment to register with adb
    sleep 3
    "$ADB" start-server >/dev/null 2>&1
    local serial
    for i in $(seq 1 15); do
        serial=$(emulator_serial)
        [[ -n "$serial" ]] && break
        sleep 2
    done
    [[ -n "$serial" ]] || err "Emulator didn't appear in adb devices after 30s"
    wait_for_boot "$serial"
}

cmd_build() {
    log "Building debug APK…"
    ./gradlew assembleDebug --quiet
    ok "Built: $APK"
}

cmd_install() {
    cmd_emulator
    local serial
    serial=$(emulator_serial)
    log "Installing on $serial…"
    "$ADB" -s "$serial" install -r "$APK"
    ok "Installed $PACKAGE"
}

cmd_launch() {
    local serial
    serial=$(emulator_serial) || err "No emulator running"
    log "Launching $ACTIVITY…"
    "$ADB" -s "$serial" shell am start -n "$ACTIVITY"
}

cmd_run() {
    cmd_build
    cmd_install
    cmd_launch
}

cmd_logcat() {
    local serial
    serial=$(emulator_serial) || err "No emulator running"
    "$ADB" -s "$serial" logcat --pid="$("$ADB" -s "$serial" shell pidof "$PACKAGE" | tr -d '\r')"
}

# ── dispatch ─────────────────────────────────────────────────────────────────

case "${1:-run}" in
    emulator) cmd_emulator ;;
    build)    cmd_build    ;;
    install)  cmd_install  ;;
    launch)   cmd_launch   ;;
    run)      cmd_run      ;;
    logcat)   cmd_logcat   ;;
    *)        err "Usage: $0 [emulator|build|install|launch|run|logcat]" ;;
esac
