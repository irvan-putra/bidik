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
# Crash forensics: the filtered post-failure logcat has never contained
# the FIRST crash across 4 system-server deaths. Dump the system, crash
# and kernel buffers the moment a gate/install step detects the dead
# system, while the buffers still hold the boot window.
crash_forensics() {
  echo "== crash forensics: system/crash/kernel buffers (URL-stripped) ==" >&2
  adb shell 'logcat -d -b system -b crash -b kernel -t 400' 2>&1 | sed -E 's#https?://[^ ]*#[url]#g' | tail -120 >&2 || true
}

# Readiness gate, reusable: a dying system server (43690400798, zygote
# fatal during install) restarts the runtime, so install retries re-enter
# this wait instead of failing the run outright.
wait_ready() {
  secs="$1"
  deadline=$(( $(date +%s) + secs ))
  while :; do
    boot=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    pmok=$(adb shell pm path android 2>/dev/null | head -1)
    if [ "$boot" = "1" ] && [ -n "$pmok" ]; then
      echo "device ready (boot_completed=1, package service up)"
      return 0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo "device not ready within ${secs}s (boot_completed='$boot', pm='$pmok')" >&2
      crash_forensics
      return 1
    fi
    # 43687078864: the transport can sit in 'offline' for the whole boot
    # window (adb shell fails with 'device offline'); nudge it back.
    if adb devices | awk 'NR>1 && $2=="offline" {found=1} END{exit !found}'; then
      echo "device transport offline; reconnecting" >&2
      if ! adb reconnect offline >/dev/null 2>&1; then
        adb kill-server >/dev/null 2>&1 || true
        adb start-server >/dev/null 2>&1 || true
      fi
    fi
    sleep 5
  done
}

# 43690400798: the system server died mid-install ('Failure calling
# service package: Broken pipe'). 43691269603: the retry recovered the
# install, but the app APK had landed BEFORE the crash and the revived
# package manager could not resolve the app's activity for the test
# runner. Install app+test as a pair: any failure re-waits for system
# health and redoes BOTH, then waits until pm actually resolves the app
# package before instrumenting.
install_pair_with_retry() {
  app_apk=app/build/outputs/apk/debug/app-debug.apk
  test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  for attempt in 1 2 3; do
    if adb install -r "$app_apk" && adb install -r "$test_apk"; then
      break
    fi
    echo "install pair failed (attempt $attempt/3); re-waiting for system health" >&2
    [ "$attempt" = 3 ] && return 1
    crash_forensics
    wait_ready 180 || return 1
    sleep 10
  done
  pm_deadline=$(( $(date +%s) + 120 ))
  while ! adb shell pm path com.vanipy.bidik 2>/dev/null | grep -q '^package:'; do
    if [ "$(date +%s)" -ge "$pm_deadline" ]; then
      echo "app package not resolvable 120s after install" >&2
      crash_forensics
      return 1
    fi
    sleep 5
  done
  return 0
}

echo "== wait for device readiness =="
adb wait-for-device
wait_ready 420

# Best-effort pixel evidence even on failure: the gate used to exit before
# the pull, so failed runs (e.g. 43698674804's blank-capture assertion)
# never uploaded the pixels that explain the failure.
mkdir -p captures
# Trailing /. pulls the directory's CONTENTS (plain `pull dir dest` nests
# dest/captures/ when dest exists - run 43702446360's gate missed that).
trap 'adb pull /sdcard/Android/data/com.vanipy.bidik/files/captures/. captures >/dev/null 2>&1 || true' EXIT

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
  install_pair_with_retry || return 1
  set +e
  timeout -k 30 "$secs" adb shell "am instrument -w -e class $cls $* com.vanipy.bidik.test/androidx.test.runner.AndroidJUnitRunner" > "$log" 2>&1
  irc=$?
  set -e
  sed -E 's#https?://[^ ]*#[url]#g' "$log" >&2 || true
  if [ $irc -ne 0 ] || ! grep -q '^OK (' "$log"; then
    return 1
  fi
  return 0
}

echo "== capture gate: loopback fixture =="
if ! run_instrumented com.vanipy.bidik.CaptureFixtureTest /tmp/fixture-instrument.log 900; then
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
  adb shell 'pm path com.vanipy.bidik' 2>&1 | sed -E 's#https?://[^ ]*#[url]#g' >&2 || true
  adb shell 'dumpsys package com.vanipy.bidik | grep -iE "versionName|DEBUGGABLE|pkgFlags"' 2>&1 | sed -E 's#https?://[^ ]*#[url]#g' >&2 || true
  adb shell 'dumpsys webviewupdate' 2>&1 | grep -iE 'package|version|valid' | head -5 | sed -E 's#https?://[^ ]*#[url]#g' >&2 || true
  echo "== merged manifest network config (host build output) ==" >&2
  find app/build/intermediates -name AndroidManifest.xml -path '*debug*' 2>/dev/null | while read -r m; do grep -l networkSecurityConfig "$m" 2>/dev/null; done | head -3 >&2 || true
  echo "== fixture server evidence ==" >&2
  adb shell 'cat /sdcard/Android/data/com.vanipy.bidik/files/captures/fixture-server.log' 2>&1 | sed -E 's#https?://[^ ]*#[url]#g' | tail -20 >&2 || true
  # Internal fallback (test writes here when external storage is broken,
  # e.g. FUSE lost to a system-server crash).
  adb shell 'run-as com.vanipy.bidik cat files/captures/fixture-server.log' 2>&1 | sed -E 's#https?://[^ ]*#[url]#g' | tail -20 >&2 || true
  echo "== exported on-device diagnostics (no run-as) ==" >&2
  adb shell 'cat /sdcard/Android/data/com.vanipy.bidik/files/captures/diag-events-prev.log /sdcard/Android/data/com.vanipy.bidik/files/captures/diag-events.log' 2>&1 | sed -E 's#https?://[^ ]*#[url]#g' | tail -80 >&2 || true
  adb shell 'run-as com.vanipy.bidik echo diag-dump-marker' 2>&1 | sed -E 's#https?://[^ ]*#[url]#g' >&2 || true
  adb shell 'run-as com.vanipy.bidik ls -la files/logs/' 2>&1 | sed -E 's#https?://[^ ]*#[url]#g' >&2 || true
  adb shell 'run-as com.vanipy.bidik cat files/logs/events-prev.log files/logs/events.log' 2>&1 | sed -E 's#https?://[^ ]*#[url]#g' | tail -80 >&2 || true
  exit 1
fi

# Live-site integration coverage: exercised every run and reported, but
# deliberately NOT the gate while emulator DNS to public hosts is
# unreliable. A failure here means the external-network path from the CI
# emulator remains unproven; it says nothing about the capture pipeline.
echo "== live-site integration attempt (diagnostics only, not gating) =="
if ! run_instrumented com.vanipy.bidik.CapturePipelineTest /tmp/live-instrument.log 600 -e queue_url "$QUEUE_URL"; then
  echo "live-site integration attempt failed: external network from the CI emulator remains unproven; not gating"
fi

mkdir -p captures
adb pull /sdcard/Android/data/com.vanipy.bidik/files/captures/. captures
if ! find captures -type f -name '*.png' -print -quit | grep -q .; then
  echo "ERROR: emulator capture directory was empty or contained no PNG screenshots" >&2
  exit 1
fi
if [ ! -s captures/ci-fixture-pipeline-viewport.png ]; then
  echo "ERROR: expected CI fixture capture captures/ci-fixture-pipeline-viewport.png was missing or empty" >&2
  exit 1
fi
ls -laR captures
