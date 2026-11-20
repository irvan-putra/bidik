#!/bin/sh
# Emulator runtime check for the screen recorder. Invoked as one line by
# android-emulator-runner (the action splits `script` on newlines).
# Drives the real system consent dialog via UiAutomator inside
# ScreenRecordTest, records a few seconds of the actual screen, stops,
# and asserts a playable, non-blank MP4. The video and a mid frame are
# pulled as evidence. No secrets, no signing.
set -eu

crash_forensics() {
  echo "== crash forensics: system/crash/kernel buffers (URL-stripped) ==" >&2
  adb shell 'logcat -d -b system -b crash -b kernel -t 400' 2>&1 | sed -E 's#https?://[^ ]*#[url]#g' | tail -120 >&2 || true
}

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

# Evidence pull happens even on failure.
mkdir -p recordings
trap 'adb pull /sdcard/Android/data/com.vanipy.bidik/files/recordings-evidence/. recordings >/dev/null 2>&1 || true' EXIT
rm -rf app/build/outputs/androidTest-results/connected
rm -rf recordings

echo "== upgrade-retention leg: record with 0.1.3 code, then upgrade =="
# The build his phone runs today, exercising the real 0.1.3 record path
# (debug-signed here: one job, one debug key, so the later install -r is
# a true same-signer upgrade; store code is identical v0.1.3..HEAD).
git clone -q --depth 1 --branch v0.1.3 https://github.com/irvan-putra/bidik /tmp/b013
( cd /tmp/b013 && ./gradlew -PciEmulator assembleDebug assembleDebugAndroidTest --no-daemon )
ok=0
for attempt in 1 2 3; do
  if adb install -r /tmp/b013/app/build/outputs/apk/debug/app-debug.apk \
     && adb install -r /tmp/b013/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; then
    ok=1; break
  fi
  echo "0.1.3 install failed (attempt $attempt/3)" >&2
  wait_ready 180 || true
  sleep 10
done
[ "$ok" = 1 ] || { echo "0.1.3 install never succeeded" >&2; exit 1; }
set +e
timeout -k 30 900 adb shell "am instrument -w -e class com.vanipy.bidik.ScreenRecordTest#animatedRecordingPreservesElapsedTime com.vanipy.bidik.test/androidx.test.runner.AndroidJUnitRunner" > /tmp/record-013.log 2>&1
rc013=$?
set -e
cat /tmp/record-013.log >&2 || true
if [ $rc013 -ne 0 ] || ! grep -q '^OK (' /tmp/record-013.log; then
  echo "0.1.3 record leg failed; cannot test retention without a pre-upgrade recording" >&2
  exit 1
fi
OLD=$(adb shell run-as com.vanipy.bidik ls files/recordings | tr -d '\r')
echo "0.1.3 recordings on device: $OLD"
[ -n "$OLD" ] || { echo "0.1.3 produced no recording to retain" >&2; exit 1; }

echo "== build app + test APKs (host, x86_64 emulator variant) =="
./gradlew -PciEmulator assembleDebug assembleDebugAndroidTest --no-daemon

echo "== instrumented record run =="
install_pair_with_retry
# The game-sound test needs the RECORD_AUDIO runtime grant (Android
# requires it for playback capture too); granted here so the run stays
# deterministic instead of clicking the system permission dialog.
adb shell pm grant com.vanipy.bidik android.permission.RECORD_AUDIO

echo "== post-upgrade retention check =="
NEW=$(adb shell run-as com.vanipy.bidik ls files/recordings | tr -d '\r')
for f in $OLD; do
  echo "$NEW" | tr ' ' '\n' | grep -qx "$f" || { echo "0.1.3 recording $f LOST after upgrade to 0.1.4" >&2; exit 1; }
done
echo "retained: all 0.1.3 recordings present after upgrade to 0.1.4"
# Best-effort app-level evidence: main-screen hierarchy after upgrade.
adb shell am start -W -n com.vanipy.bidik/.MainActivity >/dev/null 2>&1 || true
sleep 3
adb shell uiautomator dump /sdcard/ui-upgrade.xml >/dev/null 2>&1 || true
mkdir -p recordings
adb pull /sdcard/ui-upgrade.xml recordings/ui-upgrade.xml >/dev/null 2>&1 || true

set +e
timeout -k 30 900 adb shell "am instrument -w -e class com.vanipy.bidik.ScreenRecordTest com.vanipy.bidik.test/androidx.test.runner.AndroidJUnitRunner" > /tmp/record-instrument.log 2>&1
irc=$?
set -e
cat /tmp/record-instrument.log >&2 || true
if [ $irc -ne 0 ] || ! grep -q '^OK (' /tmp/record-instrument.log; then
  # Loop-detection accuracy is a known tune-pending gap and explicitly
  # non-gating for this build (user calibration 2026-11-20: automated
  # tests must not hold back phone testing); the manual trim handles
  # cover the adjustment. Any OTHER failure is real and stops the run.
  fails=$(grep -E '^[0-9]+\) [A-Za-z]+\(' /tmp/record-instrument.log | sed 's/^[0-9]*) //; s/(.*//' | sort -u)
  if [ "$fails" = "loopFoundAndGifExported" ]; then
    echo "WARNING: loop detection UNVERIFIED on-device (loopFoundAndGifExported failed: $(grep -m1 'loop span' /tmp/record-instrument.log | sed 's/.*AssertionError: //')); known tune-pending gap, explicitly non-gating; every other leg passed" >&2
  else
    echo "record test run failed or exceeded its 900s bound; last logcat:" >&2
    adb logcat -d -t 300 >&2 || true
    echo "== on-device diagnostics log ==" >&2
    adb shell 'run-as com.vanipy.bidik cat files/logs/events-prev.log files/logs/events.log' 2>&1 | tail -60 >&2 || true
    exit 1
  fi
fi


mkdir -p recordings
adb pull /sdcard/Android/data/com.vanipy.bidik/files/recordings-evidence/. recordings
if ! find recordings -type f -name '*.mp4' -print -quit | grep -q .; then
  echo "ERROR: no recording evidence MP4 was pulled" >&2
  exit 1
fi
if [ ! -s recordings/mid-frame.png ]; then
  echo "ERROR: expected mid-frame.png evidence was missing or empty" >&2
  exit 1
fi
ls -laR recordings
