#!/usr/bin/env bash
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
APK="app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="com.tid.nowplaying"
ACTIVITY="$PACKAGE/.MainActivity"

# ── device target ────────────────────────────────────────────────────────────

# If the first argument looks like host:port, use it as the network ADB target
DEVICE_ADDR=""
if [[ "${1:-}" =~ ^[0-9a-zA-Z._-]+:[0-9]+$ ]]; then
    DEVICE_ADDR="$1"
    shift
fi

# ── helpers ──────────────────────────────────────────────────────────────────

log()  { printf '\e[1;34m▶\e[0m %s\n' "$*"; }
ok()   { printf '\e[1;32m✔\e[0m %s\n' "$*"; }
err()  { printf '\e[1;31m✖\e[0m %s\n' "$*" >&2; exit 1; }

device_serial() {
    if [[ -n "$DEVICE_ADDR" ]]; then
        echo "$DEVICE_ADDR"
        return
    fi
    "$ADB" devices | awk '/\tdevice$/ && !/emulator/{print $1; exit}'
}

require_device() {
    if [[ -n "$DEVICE_ADDR" ]]; then
        log "Connecting to $DEVICE_ADDR…" >&2
        "$ADB" connect "$DEVICE_ADDR" | grep -q "connected" \
            || err "Could not connect to $DEVICE_ADDR"
        ok "Connected to $DEVICE_ADDR" >&2
        echo "$DEVICE_ADDR"
        return
    fi
    local serial
    serial=$(device_serial)
    [[ -n "$serial" ]] || err "No physical device connected (run: adb devices)"
    echo "$serial"
}

# ── commands ─────────────────────────────────────────────────────────────────

cmd_pair() {
    local addr="${1:-}"
    [[ -n "$addr" ]] || err "Usage: $0 pair <host:port>"
    printf 'Pairing code: '
    local code
    read -r code
    [[ -n "$code" ]] || err "No pairing code entered"
    "$ADB" pair "$addr" "$code" || err "Pairing failed"
    ok "Paired with $addr — you can now connect with: $0 <connect-host:port>"
}

cmd_build() {
    log "Building debug APK…"
    ./gradlew assembleDebug --quiet
    ok "Built: $APK"
}

cmd_install() {
    local serial
    serial=$(require_device)
    log "Installing on $serial…"
    "$ADB" -s "$serial" install -r "$APK"
    ok "Installed $PACKAGE"
}

cmd_launch() {
    local serial
    serial=$(require_device)
    log "Launching $ACTIVITY on $serial…"
    "$ADB" -s "$serial" shell am start -n "$ACTIVITY"
}

cmd_run() {
    cmd_build
    cmd_install
    cmd_launch
}

cmd_logcat() {
    local serial
    serial=$(require_device)
    "$ADB" -s "$serial" logcat --pid="$("$ADB" -s "$serial" shell pidof "$PACKAGE" | tr -d '\r')"
}

cmd_watch() {
    local debounce=${WATCH_DEBOUNCE:-30}
    command -v inotifywait >/dev/null 2>&1 || err "inotifywait not found — install inotify-tools"
    require_device >/dev/null
    log "Watching for changes (debounce ${debounce}s)…"
    local timer_pid=""
    while read -r _dir _event _file < <(
        inotifywait -q -m -r \
            --include '\.(kt|java|xml|gradle|kts)$' \
            -e close_write -e moved_to \
            app/src app/build.gradle.kts build.gradle.kts 2>/dev/null
    ); do
        if [[ -n "$timer_pid" ]] && kill -0 "$timer_pid" 2>/dev/null; then
            kill "$timer_pid" 2>/dev/null
        fi
        ( sleep "$debounce" && log "Change detected — rebuilding…" && cmd_build && cmd_install && cmd_launch ) &
        timer_pid=$!
    done
}

# ── dispatch ─────────────────────────────────────────────────────────────────

case "${1:-run}" in
    pair)    cmd_pair "${2:-}"  ;;
    build)   cmd_build          ;;
    install) cmd_install        ;;
    launch)  cmd_launch         ;;
    run)     cmd_run            ;;
    logcat)  cmd_logcat         ;;
    watch)   cmd_watch          ;;
    *)       err "Usage: $0 pair <host:port> | [host:port] [build|install|launch|run|logcat|watch]" ;;
esac
