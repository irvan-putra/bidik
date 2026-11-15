#!/bin/sh
# This file is invoked as one line by android-emulator-runner. Keep multiline
# shell logic here because the action splits its `script` input on newlines.
set -eu

case "$QUEUE_URL" in
  https://*) ;;
  *) echo "queue_url must be a public https URL, got: $QUEUE_URL"; exit 1 ;;
esac

./gradlew -PciEmulator connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.queue_url="$QUEUE_URL"
mkdir -p captures
adb pull /sdcard/Android/data/com.vanipy.mata/files/captures captures
ls -laR captures
