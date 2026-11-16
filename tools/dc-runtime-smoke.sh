#!/usr/bin/env bash
# Account-free DeltaChat runtime smoke on an emulator. Proves the vendored
# arm64 rpc core (librpcsrv.so) loads and answers an RPC on this Android
# runtime. No account, no network, no keys. Runs INSIDE the
# android-emulator-runner script step while the emulator is alive; keep it
# one file - the action runs each `script:` line as its own sh -c, so
# multi-line shell constructs belong here, never inline.
set -euo pipefail

echo "== wait for device =="
adb wait-for-device
deadline=$((SECONDS + 420))
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  [ $SECONDS -lt $deadline ] || { echo "emulator did not boot in time" >&2; exit 1; }
  sleep 5
done

echo "== ABI evidence (is arm64 advertised on this x86_64 image?) =="
echo "cpu.abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
echo "cpu.abilist=$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
echo "native.bridge=$(adb shell getprop ro.dalvik.vm.native.bridge | tr -d '\r')"

echo "== install (arm64-only APKs) =="
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

echo "== runtime smoke =="
set +e
timeout -k 30 600 adb shell "am instrument -w -e class com.vanipy.bidik.DcRuntimeSmokeTest com.vanipy.bidik.test/androidx.test.runner.AndroidJUnitRunner" > /tmp/smoke.log 2>&1
irc=$?
set -e
sed -E 's#https?://[^ ]*#[url]#g' /tmp/smoke.log
if [ $irc -ne 0 ] || ! grep -q '^OK (' /tmp/smoke.log; then
  echo "runtime smoke failed; last logcat:" >&2
  adb logcat -d -t 300 >&2 || true
  exit 1
fi
