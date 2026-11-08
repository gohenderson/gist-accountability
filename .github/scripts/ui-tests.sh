#!/usr/bin/env bash
# Runs the UI tests on the running emulator and collects evidence for any failure.
#
# - Full emulator logcat → build-logs/ui-tests/logcat.txt
# - Screenshots the tests take when they fail → build-logs/ui-tests/gist-test-failures/
# - App crashes found in logcat are printed into the job log.
# Exit code is Gradle's.

set -Eeuo pipefail
on_crash() { echo "::error title=ui-tests.sh crashed::line $1: $2 (exit $3)"; }
trap 'on_crash "${LINENO}" "${BASH_COMMAND}" "$?"' ERR

SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
LOGS="build-logs/ui-tests"
mkdir -p "$LOGS"

adb wait-for-device
for _ in $(seq 1 90); do
  [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break
  sleep 2
done
echo "Android $(adb shell getprop ro.build.version.release | tr -d '\r'), $(adb shell wm size | tr -d '\r')"

# A freshly booted emulator sometimes shows a system dialog (e.g. "System UI isn't
# responding") or sits on the lock screen. Either one keeps test screens from getting
# focus, so clear them first and log what has focus.
echo "::group::Prepare device"
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null || true
adb shell input keyevent KEYCODE_HOME || true
sleep 2
adb shell settings put system screen_off_timeout 1800000 || true
echo "Focus before tests: $(adb shell dumpsys window | grep -m1 mCurrentFocus | tr -d '\r')"
echo "::endgroup::"

adb shell rm -rf /data/local/tmp/gist-test-failures || true
adb logcat -c || true
adb logcat -v time > "$LOGS/logcat.txt" 2>&1 &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" 2>/dev/null || true' EXIT

trap - ERR
set +e
bash "$SCRIPTS/run-gradle.sh" connectedDebugAndroidTest
status=$?
set -e
trap 'on_crash "${LINENO}" "${BASH_COMMAND}" "$?"' ERR

echo "Focus after tests: $(adb shell dumpsys window | grep -m1 mCurrentFocus | tr -d '\r')"
grep -E "ANR in|isn't responding" "$LOGS/logcat.txt" | head -n 5 || true

adb pull /data/local/tmp/gist-test-failures "$LOGS/" > /dev/null 2>&1 || true
if ls "$LOGS"/gist-test-failures/*.png > /dev/null 2>&1; then
  echo "Failure screenshots:"; ls "$LOGS"/gist-test-failures/
fi

sleep 1
if grep -q "FATAL EXCEPTION" "$LOGS/logcat.txt"; then
  echo "::warning title=App crash during UI tests::A crash was logged; excerpt below (full log in the ui-test-logs artifact)."
  grep -A 30 "FATAL EXCEPTION" "$LOGS/logcat.txt" | head -n 80
fi

exit "$status"
