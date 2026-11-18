# Screen recording: runtime verification record

Durable facts from the emulator verification of the screen recorder.
Verification environment: GitHub Actions `record-check.yml` on the
repo's API 34 x86_64 KVM emulator profile (android-emulator-runner,
swiftshader), driven by `tools/record-emulator.sh` and
`app/src/androidTest/.../ScreenRecordTest.kt`, which answers the real
system consent dialog through UiAutomator. This is a synthetic
environment; nothing here claims correctness on the user's Galaxy A24
home screen - that check is the user's, on his device.

## Verified behavior (run 44376936012, commit e7510fc, 2026-11-18)

All three instrumented tests passed (`OK (3 tests)`):

- `consentDeniedRecordsNothing`: the system consent dialog appears; the
  deny control ("Don't allow"/"Cancel") leaves no recording and no
  active marker.
- `animatedRecordingPreservesElapsedTime`: ~6s recorded while the UI
  changed (status ticks). Saved MP4: h264, 320x640, 6.980s, 191 frames
  (`bidik-20261118-191028.mp4`, run artifact `record-pixels`). Frame at
  5.0s shows "recording tick 22", consistent with wall-clock pacing.
- `staticRecordingPreservesElapsedTime`: ~6s recorded with no UI change
  at all. Saved MP4: h264, 320x640, 6.613s, 180 frames
  (`bidik-20261118-191009.mp4`). A decodable, non-blank tail frame
  exists at 5.0s: the still tail is in the file.

Also asserted on every saved recording: ISO BMFF `ftyp` container, real
dimensions, non-uniform (non-blank) mid frames, active marker cleared
after stop, and stop from the app's own button. Consent dialog labels
that work on API 34: "Entire screen", accept "Share screen"/"Start now",
deny "Don't allow"/"Cancel" (matched case-insensitively; Android
renders button labels in all caps).

## The 0.1.2 defect and its correction

Run 44371384389 (commit 811eb74, the old MediaRecorder path) saved a
1.763s video from a 6s static window: feeding MediaRecorder directly
from the AUTO_MIRROR virtual display gives the encoder no samples while
the compositor is idle, so elapsed time compresses and a still tail at
stop is truncated. Published 0.1.2 contains this defect. Animated
content records correctly even on 0.1.2; stillness does not.

Commit cec6dfe replaced the capture core: the virtual display renders
into a SurfaceTexture, and a render loop draws each frame - or the
previous frame again while the screen is still - onto a MediaCodec
input surface with wall-clock timestamps via
`eglPresentationTimeANDROID`. Stop draws one final frame at the stop
moment, drains to end-of-stream, then stops the muxer. Same commit
moved the in-progress file out of the listed recordings dir (the
encoder now writes a cache-dir partial; only a cleanly stopped
recording is moved in), so the app list and its share action only ever
see finished files - previously an in-progress recording listed at
0 MB with a share action.

## Harness lessons encoded in the tests

- Fresh emulator boots sit on the keyguard: wake and
  `wm dismiss-keyguard` in setUp; launch via ActivityScenario (raw
  `startActivity` raced the launcher, run 44368283083).
- Button labels render in all caps: match case-insensitively
  (run 44370099208).
- Evidence is cleared once per run (BeforeClass), not per test: a later
  test wiped earlier evidence when clearing lived in setUp
  (run 44375141949, tests green but no evidence pulled).
