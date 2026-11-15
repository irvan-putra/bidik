#!/bin/sh
# This file is invoked as one line by android-emulator-runner. Keep multiline
# shell logic here because the action splits its `script` input on newlines.
set -eu

case "$QUEUE_URL" in
  https://*) ;;
  *) echo "queue_url must be a public https URL, got: $QUEUE_URL"; exit 1 ;;
esac

# Remove only CI-generated result/output folders so a previous run cannot
# make a zero-test run appear healthy.
rm -rf app/build/outputs/androidTest-results/connected
rm -rf captures
# External-network preflight: this is the live-network evidence for the
# run, kept distinct from the capture tests themselves. Diagnostics only
# - the instrumented tests remain the pass/fail gate.
echo "== network preflight =="
host=$(echo "$QUEUE_URL" | sed -E 's#https://([^/]+).*#\1#')
adb shell getprop net.dns1; adb shell getprop net.dns2 || true
echo "== ping preflight: resolver IP (ICMP reachability only) =="
adb shell ping -c 1 -W 3 8.8.8.8 && echo "resolver-IP ping succeeded" || echo "resolver-IP ping failed (see ping output)"
echo "== ping preflight: target host (DNS + ICMP reachability only) =="
adb shell ping -c 1 -W 3 "$host" && echo "target-host ping succeeded: $host" || echo "target-host ping failed (DNS, route, or ICMP may be blocked; see ping output)"

# Bound the instrumented run: a blocked test must fail in minutes with
# diagnostic evidence, not hang until the job timeout (a previous run sat
# 28 minutes on "Starting 2 tests" with no instrumentation output).
#
# GATE: the deterministic loopback fixture proves the capture pipeline
# (real WebView render, pixels, queue -> run -> result wiring) with no
# external network dependency. The live-site test below stays as
# integration coverage but cannot gate: emulator DNS to public hosts is
# currently unreliable on this runner.
echo "== capture gate: loopback fixture =="
set +e
timeout -k 30 900 ./gradlew -PciEmulator connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vanipy.mata.CaptureFixtureTest
rc=$?
set -e
if [ $rc -ne 0 ]; then
  echo "fixture capture run failed or exceeded its 900s bound (rc=$rc); last logcat:" >&2
  adb logcat -d -t 300 >&2 || true
  exit 1
fi

# Gradle may report BUILD SUCCESSFUL even if no instrumentation tests were
# discovered. Require a fresh XML report with at least one executed test.
python3 - <<'PYTESTS'
import glob
import sys
import xml.etree.ElementTree as ET

reports = glob.glob("app/build/outputs/androidTest-results/connected/**/*.xml", recursive=True)
if not reports:
    sys.exit("ERROR: no connected instrumentation test XML reports were produced")
tests = failures = errors = 0
for path in reports:
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        sys.exit(f"ERROR: invalid instrumentation test report {path}: {exc}")
    suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
    tests += sum(int(s.get("tests", "0")) for s in suites)
    failures += sum(int(s.get("failures", "0")) for s in suites)
    errors += sum(int(s.get("errors", "0")) for s in suites)
print(f"Instrumentation results: {tests} tests, {failures} failures, {errors} errors; reports={len(reports)}")
if tests < 1:
    sys.exit("ERROR: zero connected instrumentation tests executed")
if failures or errors:
    sys.exit("ERROR: connected instrumentation test failures/errors in XML reports")
PYTESTS

# Live-site integration coverage: exercised every run and reported, but
# deliberately NOT the gate while emulator DNS to public hosts is
# unreliable. A failure here means the external-network path from the CI
# emulator remains unproven; it says nothing about the capture pipeline.
echo "== live-site integration attempt (diagnostics only, not gating) =="
set +e
timeout -k 30 600 ./gradlew -PciEmulator connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vanipy.mata.CapturePipelineTest \
  -Pandroid.testInstrumentationRunnerArguments.queue_url="$QUEUE_URL"
live_rc=$?
set -e
if [ $live_rc -ne 0 ]; then
  echo "live-site integration attempt failed (rc=$live_rc): external network from the CI emulator remains unproven; not gating"
fi

mkdir -p captures
adb pull /sdcard/Android/data/com.vanipy.mata/files/captures captures
if ! find captures -type f -name '*.png' -print -quit | grep -q .; then
  echo "ERROR: emulator capture directory was empty or contained no PNG screenshots" >&2
  exit 1
fi
if [ ! -s captures/ci-fixture-viewport.png ]; then
  echo "ERROR: expected CI fixture capture captures/ci-fixture-viewport.png was missing or empty" >&2
  exit 1
fi
ls -laR captures
