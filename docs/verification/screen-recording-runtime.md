# Screen recording: runtime verification record

Durable facts from the emulator verification of the screen recorder.
Verification environment: GitHub Actions `record-check.yml` on the
repo's API 34 x86_64 KVM emulator profile (android-emulator-runner,
swiftshader), driven by `tools/record-emulator.sh` and
`app/src/androidTest/.../ScreenRecordTest.kt`, which answers the real
system consent dialog through UiAutomator. This is a synthetic
environment; nothing here claims correctness on the user's Galaxy A24
home screen - that check is the user's, on his device.

## Verified behavior for 0.1.4 (run 44443222912, commit aef1ca6, 2026-11-19)

Record job green on the same API 34 emulator profile; pixel evidence in
the run's `record-pixels` artifact. Host unit tests: 46/46 green,
including the loop-detection hardening cases.

- `delayedStartTrimsClipStart`: 5s start delay. The status sequence runs
  starting -> recording -> saved, and the saved clip holds only the
  post-delay span (2.2s-6s for ~3s recorded after the delay;
  `delayed-frame.png`).
- `autoStopSavesWithoutInteraction`: 15s auto-stop saves with no further
  interaction; the MP4 is playable with elapsed 13s-25s
  (`autostop-frame.png`).
- `hiddenBarsCropTheVideo`: with system bars hidden, the saved video is
  exactly full width by (full height - status bar - nav bar): 320x616 vs
  320x640 on this profile. The cropped frame matches the middle band of
  an un-cropped frame (mean abs diff < 25), so the crop removes the bars
  instead of scaling or double-cropping content
  (`cropped-bars-frame.png`).
- `loopFoundAndGifExported`: a periodically changing screen yields a
  loop candidate with seam >= 0.95 over the ~800ms cycle; export writes
  a GIF89a with the NETSCAPE2.0 infinite-loop marker, 8 frames at 100ms,
  320x640, decodable on Android (`loop-test.gif`). The full-resolution
  source frames at the loop endpoints are pixel-identical in this run
  (`loop-seam-start.png` / `loop-seam-end.png`, mean abs diff 0), so the
  wrap does not visibly jump. The host-side GIF encoder output was also
  cross-verified against Pillow's decoder (0 mismatched pixels, loop
  marker present).
- `noLoopInNonRepeatingRecording`: six grays stepping in one direction
  (real motion that never returns to an earlier state) produce no
  confident loop candidate, and the best-effort guess stays below the
  0.90 bar. A still screen cannot fake a loop either: candidates require
  real interior motion.

The pre-existing consent and elapsed-time tests are unchanged and still
pass: denial records nothing; animated and static recordings preserve
wall-clock elapsed time with a decodable still tail.

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
