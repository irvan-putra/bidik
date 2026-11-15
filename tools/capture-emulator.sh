#!/bin/sh
# This file is invoked as one line by android-emulator-runner. Keep multiline
# shell logic here because the action splits its `script` input on newlines.
set -eu

case "$QUEUE_URL" in
  https://*) ;;
  *) echo "queue_url must be a public https URL, got: $QUEUE_URL"; exit 1 ;;
esac

# Wait for full device readiness: adb answers before system_server has
# every service up on this unaccelerated runner, and installing the test
# APK then fails with "Can't find service: package" (observed run
# 43677737595). Gate on boot_completed AND the package manager serving.
echo "== wait for device readiness =="
adb wait-for-device
ready_deadline=$(( $(date +%s) + 420 ))
while :; do
  boot=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  pmok=$(adb shell pm path android 2>/dev/null | head -1)
  if [ "$boot" = "1" ] && [ -n "$pmok" ]; then
    echo "device ready (boot_completed=1, package service up)"
    break
  fi
  if [ "$(date +%s)" -ge "$ready_deadline" ]; then
    echo "device not ready within 420s (boot_completed='$boot', pm='$pmok')" >&2
    exit 1
  fi
  sleep 5
done

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
# Instrumentation driver: Gradle's connectedDebugAndroidTest UNINSTALLS
# the app when the task ends (run 43684421683: post-task pm path empty,
# run-as "unknown package", and the sdcard app-data dir carrying the
# test evidence wiped), destroying all post-task evidence. Build the
# APKs on the host, install explicitly, and drive am instrument
# directly so the app - and its evidence - stays present for retrieval.
echo "== build app + test APKs (host) =="
./gradlew -PciEmulator assembleDebug assembleDebugAndroidTest --no-daemon

# run_instrumented <test-class> <log-file> <timeout-s> [extra -e args...]
# am instrument exits 0 even when tests fail, so require the explicit
# "OK (N tests)" summary in the output.
run_instrumented() {
  cls="$1"; log="$2"; secs="$3"; shift 3
  adb install -r app/build/outputs/apk/debug/app-debug.apk || return 1
  adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || return 1
  set +e
  timeout -k 30 "$secs" adb shell "am instrument -w -e class $cls $* com.vanipy.mata.test/androidx.test.runner.AndroidJUnitRunner" > "$log" 2>&1
  irc=$?
  set -e
  sed -E 's#https?://[^ ]*#[url]#g' "$log" >&2 || true
  if [ $irc -ne 0 ] || ! grep -q '^OK (' "$log"; then
    return 1
  fi
  return 0
}

echo "== capture gate: loopback fixture =="
if ! run_instrumented com.vanipy.mata.CaptureFixtureTest /tmp/fixture-instrument.log 900; then
  echo "fixture capture run failed or exceeded its 900s bound; last logcat:" >&2
  adb logcat -d -t 300 >&2 || true
  # Platform network errors (e.g. cleartext denial) name the real cause;
  # the generic test failure alone does not. Bounded to the last matches.
  echo "== filtered logcat: platform network/webview errors ==" >&2
  adb logcat -d -b all | grep -iE 'cleartext|ERR_|net::|NetworkSecurity' | sed -E 's#https?://[^ ]*#[url]#g' | tail -50 >&2 || true
  # The app's own diagnostics log is self-sanitized by DiagLog (bounded
  # categories/counters only) and says exactly which WebView callback
  # fired: page_started, page_error (with bounded code), page_timeout.
  echo "== on-device diagnostics log ==" >&2
  # Quoting matters: the remote command must be one local argument, or
  # sh -c collapses to a bare 'cat' and silently prints nothing.
  # Self-diagnosing: an empty section must distinguish "no log written"
  # from "run-as itself failed", so run-as errors are shown (URLs
  # stripped), not suppressed.
  echo "== device/app/webview identity ==" >&2
  adb shell 'pm path com.vanipy.mata' 2>&1 | sed -E 's#https?://[^ ]*#[url]#g' >&2 || true
  adb shell 'dumpsys package com.vanipy.mata | grep -iE "versionName|DEBUGGABLE|pkgFlags"' 2>&1 | sed -E 's#https?://[^ ]*#[url]#g' >&2 || true
  adb shell 'dumpsys webviewupdate' 2>&1 | grep -iE 'package|version|valid' | head -5 | sed -E 's#https?://[^ ]*#[url]#g' >&2 || true
  echo "== merged manifest network config (host build output) ==" >&2
  find app/build/intermediates -name AndroidManifest.xml -path '*debug*' 2>/dev/null | while read -r m; do grep -l networkSecurityConfig "$m" 2>/dev/null; done | head -3 >&2 || true
  echo "== fixture server evidence ==" >&2
  adb shell 'cat /sdcard/Android/data/com.vanipy.mata/files/captures/fixture-server.log' 2>&1 | sed -E 's#https?://[^ ]*#[url]#g' | tail -20 >&2 || true
  echo "== exported on-device diagnostics (no run-as) ==" >&2
  adb shell 'cat /sdcard/Android/data/com.vanipy.mata/files/captures/diag-events-prev.log /sdcard/Android/data/com.vanipy.mata/files/captures/diag-events.log' 2>&1 | sed -E 's#https?://[^ ]*#[url]#g' | tail -80 >&2 || true
  adb shell 'run-as com.vanipy.mata echo diag-dump-marker' 2>&1 | sed -E 's#https?://[^ ]*#[url]#g' >&2 || true
  adb shell 'run-as com.vanipy.mata ls -la files/logs/' 2>&1 | sed -E 's#https?://[^ ]*#[url]#g' >&2 || true
  adb shell 'run-as com.vanipy.mata cat files/logs/events-prev.log files/logs/events.log' 2>&1 | sed -E 's#https?://[^ ]*#[url]#g' | tail -80 >&2 || true
  exit 1
fi

# Live-site integration coverage: exercised every run and reported, but
# deliberately NOT the gate while emulator DNS to public hosts is
# unreliable. A failure here means the external-network path from the CI
# emulator remains unproven; it says nothing about the capture pipeline.
echo "== live-site integration attempt (diagnostics only, not gating) =="
if ! run_instrumented com.vanipy.mata.CapturePipelineTest /tmp/live-instrument.log 600 -e queue_url "$QUEUE_URL"; then
  echo "live-site integration attempt failed: external network from the CI emulator remains unproven; not gating"
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
