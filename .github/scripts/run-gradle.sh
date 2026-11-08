#!/usr/bin/env bash
# Runs Gradle with full logging for CI.
#
# - Saves the complete output to build-logs/<tasks>.log (uploaded as an artifact).
# - On failure: turns Kotlin/Java/lint errors into GitHub annotations (shown on the
#   run page and on the offending lines in the diff), and writes a job summary with
#   Gradle's "What went wrong" section and the tail of the log.
# - Any unexpected error in this script itself is reported with its line number.
#
# Usage: .github/scripts/run-gradle.sh <gradle args...>

set -Eeuo pipefail
on_crash() { echo "::error title=run-gradle.sh crashed::line $1: $2 (exit $3)"; }
trap 'on_crash "${LINENO}" "${BASH_COMMAND}" "$?"' ERR

LOG_DIR="${LOG_DIR:-build-logs}"
mkdir -p "$LOG_DIR"
name="$(printf '%s' "$*" | tr -c 'A-Za-z0-9._-' '_' | cut -c1-80)"
LOG="$LOG_DIR/gradle-${name}.log"
SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/null}"

echo "Gradle: gradle $*"
echo "Full log: $LOG"

# Gradle failing is expected to be handled below, not reported as a script crash.
trap - ERR
set +e
gradle "$@" \
  --stacktrace \
  --console=plain \
  --warning-mode=summary \
  2>&1 | tee "$LOG"
status=${PIPESTATUS[0]}
set -e
trap 'on_crash "${LINENO}" "${BASH_COMMAND}" "$?"' ERR

if [[ $status -eq 0 ]]; then
  echo "### ✅ \`gradle $*\` succeeded" >> "$SUMMARY"
  exit 0
fi

ws="${GITHUB_WORKSPACE:-$PWD}"

# --- Annotations ---------------------------------------------------------------
# Kotlin:  e: file:///home/runner/work/x/x/app/src/.../Foo.kt:12:34 Unresolved reference 'bar'.
# Java:    /home/runner/work/x/x/app/src/.../Foo.java:12: error: cannot find symbol
# AAPT/manifest/lint lines are reported as-is.
errors_file="$LOG_DIR/errors-${name}.txt"
: > "$errors_file"

grep -E '^e: ' "$LOG" | while IFS= read -r line; do
  msg="${line#e: }"
  if [[ "$msg" =~ ^(file://)?([^:]+):([0-9]+):([0-9]+)[[:space:]](.*)$ ]]; then
    file="${BASH_REMATCH[2]#"$ws"/}"
    echo "::error file=${file},line=${BASH_REMATCH[3]},col=${BASH_REMATCH[4]},title=Kotlin compile error::${BASH_REMATCH[5]}"
    echo "${file}:${BASH_REMATCH[3]}:${BASH_REMATCH[4]} ${BASH_REMATCH[5]}" >> "$errors_file"
  else
    echo "::error title=Kotlin error::${msg}"
    echo "$msg" >> "$errors_file"
  fi
done || true

grep -E '^/.*:[0-9]+: error: ' "$LOG" | while IFS= read -r line; do
  if [[ "$line" =~ ^([^:]+):([0-9]+):[[:space:]]error:[[:space:]](.*)$ ]]; then
    file="${BASH_REMATCH[1]#"$ws"/}"
    echo "::error file=${file},line=${BASH_REMATCH[2]},title=Compile error::${BASH_REMATCH[3]}"
    echo "${file}:${BASH_REMATCH[2]} ${BASH_REMATCH[3]}" >> "$errors_file"
  fi
done || true

grep -E 'AAPT: error:|Manifest merger failed|ERROR:.*(xml|Manifest)|Lint found .* errors' "$LOG" \
  | sort -u | while IFS= read -r line; do
    echo "::error title=Build error::${line}"
    echo "$line" >> "$errors_file"
  done || true

# --- Job summary -----------------------------------------------------------------
{
  echo "### ❌ \`gradle $*\` failed (exit $status)"
  echo
  if [[ -s "$errors_file" ]]; then
    echo "**Errors**"
    echo '```'
    head -n 60 "$errors_file"
    echo '```'
  fi
  went_wrong="$(sed -n '/^\* What went wrong:/,/^\* Try:/p' "$LOG" | grep -v '^\* Try:' | head -n 40 || true)"
  if [[ -n "$went_wrong" ]]; then
    echo "**What went wrong**"
    echo '```'
    echo "$went_wrong"
    echo '```'
  fi
  echo "<details><summary>Last 120 lines of the log</summary>"
  echo
  echo '```'
  tail -n 120 "$LOG"
  echo '```'
  echo "</details>"
  echo
  echo "Full logs and reports are in the run's **build-logs** artifact."
} >> "$SUMMARY"

echo "::error title=Gradle failed::gradle $* exited with $status. See the job summary and the build-logs artifact."
exit "$status"
