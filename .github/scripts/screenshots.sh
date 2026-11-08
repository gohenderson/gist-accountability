#!/usr/bin/env bash
# Takes the release screenshots on a running emulator.
#
# Usage: screenshots.sh <debug.apk> <output dir>
#
# Seeds demo data straight into the app's preferences (debug builds allow `run-as`),
# opens each screen with `am start` (debug builds export these activities), and saves
# PNGs. The emulator's logcat is saved to build-logs/screenshots/ for debugging, and on
# any error the current screen and activity stack are captured too.

set -Eeuo pipefail

APK="$1"
OUT="$2"
PKG="dev.gist.checkin"
LOGS="build-logs/screenshots"
SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT" "$LOGS"

on_error() {
  local line=$1 cmd=$2 code=$3
  echo "::error title=Screenshot script failed::line ${line}: ${cmd} (exit ${code})"
  adb exec-out screencap -p > "$LOGS/failure-screen.png" 2>/dev/null || true
  adb shell dumpsys activity activities > "$LOGS/failure-activities.txt" 2>/dev/null || true
  {
    echo "### ❌ Screenshots failed"
    echo "Line ${line}: \`${cmd}\` (exit ${code})."
    echo "The **screenshots-logs** artifact has logcat, the screen at the time of failure, and the activity stack."
  } >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
}
trap 'on_error "${LINENO}" "${BASH_COMMAND}" "$?"' ERR

echo "::group::Wait for boot"
adb wait-for-device
for _ in $(seq 1 90); do
  [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break
  sleep 2
done
[[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == "1" ]]
adb shell getprop ro.build.version.release
adb shell wm size
echo "::endgroup::"

# Capture the device log for the whole session.
adb logcat -c || true
adb logcat -v time > "$LOGS/logcat.txt" 2>&1 &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" 2>/dev/null || true' EXIT

echo "::group::Install and grant permissions"
adb install -r -g "$APK"   # -g grants runtime permissions (notifications, camera)
adb shell appops set "$PKG" USE_FULL_SCREEN_INTENT allow || echo "::warning::appops USE_FULL_SCREEN_INTENT failed"
adb shell dumpsys deviceidle whitelist "+$PKG" || echo "::warning::battery whitelist failed"
echo "::endgroup::"

echo "::group::Clean status bar"
adb shell settings put global sysui_demo_allowed 1 || true
adb shell am broadcast -a com.android.systemui.demo -e command enter > /dev/null || true
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0700 > /dev/null || true
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false > /dev/null || true
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 > /dev/null || true
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false > /dev/null || true
echo "::endgroup::"

seed() {
  local pending=$1
  python3 "$SCRIPTS/seed_prefs.py" --pending "$pending" > "$LOGS/gist-$pending.xml"
  adb shell am force-stop "$PKG"
  adb push "$LOGS/gist-$pending.xml" /data/local/tmp/gist.xml > /dev/null
  adb shell chmod 644 /data/local/tmp/gist.xml
  adb shell "run-as $PKG sh -c 'mkdir -p /data/data/$PKG/shared_prefs && cp /data/local/tmp/gist.xml /data/data/$PKG/shared_prefs/gist.xml'"
  echo "Seeded demo data (pending: $pending)"
}

launch() {
  local activity=$1; shift
  adb shell am start -W -n "$PKG/$activity" "$@"
  sleep 3
}

shot() {
  local name=$1
  adb exec-out screencap -p > "$OUT/$name.png"
  local size
  size=$(stat -c %s "$OUT/$name.png")
  if (( size < 10000 )); then
    echo "::error title=Bad screenshot::$name.png is only $size bytes"
    return 1
  fi
  echo "Captured $name.png ($size bytes)"
}

echo "::group::Start page"
seed none
launch .MainActivity
shot 01-start
echo "::endgroup::"

echo "::group::History"
for _ in 1 2 3; do adb shell input swipe 540 1900 540 500 300; sleep 1; done
sleep 1
shot 02-history
echo "::endgroup::"

echo "::group::Edit page"
launch .CheckinEditActivity --es checkinId start
shot 03-edit
echo "::endgroup::"

echo "::group::Alarm: math"
seed start
launch .CheckinActivity
shot 04-alarm-math
echo "::endgroup::"

echo "::group::Alarm: questions"
seed evening
launch .CheckinActivity
shot 05-alarm-questions
echo "::endgroup::"

adb shell am force-stop "$PKG" || true
ls -la "$OUT"
{
  echo "### 📸 Screenshots"
  for f in "$OUT"/*.png; do echo "- $(basename "$f")"; done
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
