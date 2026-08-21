#!/usr/bin/env bash
# V0 device-parity helper. Two modes:
#
#   ./push_fixtures_and_compare.sh push      # copy the real XDF/bin onto the device
#   ./push_fixtures_and_compare.sh compare   # pull the device report, diff vs host golden
#
# Run `push` once (before the test), then run the instrumentation test, then run
# `compare`. Nothing here flashes anything.
#
# None of the four fixtures live in this repo: two come from the simoscal
# checkout and two are supplied locally (see BOOST_FIXTURE_DIR below). `push`
# either places every one of them or fails, and deliberately does *not* fall
# back to a partial push: the
# payload records an absent boost fixture as SKIPPED rather than erroring, and a
# host and device that both skip agree at a self-consistent digest and print
# `PARITY: MATCH` — a green run that never exercised the boost leg. The loud
# failure here is what keeps that from being reachable by accident.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIXTURES="${BOOST_FIXTURE_DIR:-$REPO/parity/fixtures}"
DEVICE_DIR="/data/local/tmp/v0"

# Two of the four fixtures (the stock XDF and the stock bin) live in the
# simoscal library repo, not here — this app was split out of it. Point
# SIMOSCAL_DIR at that checkout; the default assumes it sits alongside.
#
# The other two (the switch-patch XDF and the patched bin) are not redistributed
# by either repo — one is third-party, one is a specific car's calibration. Put
# them anywhere and point BOOST_FIXTURE_DIR at it; without them `push` fails
# loudly rather than pushing three of four.
SIMOSCAL="${SIMOSCAL_DIR:-$REPO/../simoscal}"
if [ ! -d "$SIMOSCAL" ]; then
  echo "error: simoscal checkout not found at $SIMOSCAL." >&2
  echo "       Set SIMOSCAL_DIR=/path/to/simoscal." >&2
  exit 2
fi
SIMOSCAL="$(cd "$SIMOSCAL" && pwd)"

PY313="${PY313:-$SIMOSCAL/.venv313/bin/python}"

# adb, in order of preference: an explicit override, the SDK pointed at by
# ANDROID_HOME/ANDROID_SDK_ROOT, then whatever is on PATH. The previous
# hard-coded Homebrew path resolved on exactly one machine.
if [ -n "${ADB:-}" ]; then
  :
elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
  ADB="$ANDROID_HOME/platform-tools/adb"
elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]; then
  ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
elif command -v adb >/dev/null 2>&1; then
  ADB="$(command -v adb)"
else
  echo "error: adb not found. Set ADB=/path/to/adb or ANDROID_HOME." >&2
  exit 2
fi

# The exact filenames V0ParityTest looks for in $DEVICE_DIR. Do not rename.
FIXTURE_PATHS=(
  "$SIMOSCAL/xdf/SC8S50.V1.0.xdf"
  "$SIMOSCAL/bin/5G0906259L__0002.bin"
  "$FIXTURES/S50 Switch Patch.29.33.V2.xdf"
  "$FIXTURES/CB_HSL_SP2933_5G0906259L_0002_BasicsGuide_R04.bin"
)

case "${1:-}" in
  push)
    missing=0
    for f in "${FIXTURE_PATHS[@]}"; do
      [ -f "$f" ] || { echo "missing fixture: $f" >&2; missing=1; }
    done
    if [ "$missing" -ne 0 ]; then
      echo "error: refusing to push a partial fixture set — the boost leg would" >&2
      echo "       record SKIPPED and the run would still look like a match." >&2
      exit 2
    fi
    "$ADB" shell mkdir -p "$DEVICE_DIR"
    for f in "${FIXTURE_PATHS[@]}"; do
      "$ADB" push "$f" "$DEVICE_DIR/"
    done
    echo "OK: all ${#FIXTURE_PATHS[@]} fixtures pushed to $DEVICE_DIR"
    ;;
  compare)
    OUT="${OUT:-$REPO/parity/v0_device_report.json}"
    # The test writes to targetContext's external files dir. On an application
    # module that is the app package; on a self-instrumenting library it is the
    # .test package. Try both so this works regardless.
    "$ADB" pull /sdcard/Android/data/com.simoscal.engine/files/v0_device_report.json "$OUT" 2>/dev/null \
      || "$ADB" pull /sdcard/Android/data/com.simoscal.engine.test/files/v0_device_report.json "$OUT"
    echo "pulled: $OUT"
    # Fail loudly if the device half skipped a leg — `compare` alone would report
    # a match when the host skipped the same one.
    if grep -q '"skipped"' "$OUT"; then
      echo "error: device report contains a SKIPPED leg — not a V0 PASS." >&2
      grep -n '"skipped"' "$OUT" >&2
      exit 1
    fi
    "$PY313" "$REPO/parity/run_host_parity.py" --compare "$OUT"
    ;;
  *)
    echo "usage: $0 {push|compare}" >&2
    exit 2
    ;;
esac
