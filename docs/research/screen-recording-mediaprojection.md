# Recording the screen from an app: MediaProjection

Status: design note from documentation read on 2026-11-19. The
implementation in `ScreenRecordService.kt` follows it. Behavior on a
particular phone still has to be verified on that phone.

## Consent and sessions
- Screen capture goes through the MediaProjection API. The app launches
  the system consent prompt with
  `MediaProjectionManager.createScreenCaptureIntent()` and the user
  approves each session. Android 14 adds choosing a single app window
  instead of the whole screen.
  https://developer.android.com/media/grow/media-projection
- One consent token backs exactly one `createVirtualDisplay()` call, so
  each recording is its own consented session. From Android 14 the app
  must register a `MediaProjection.Callback`; the same callback reports
  the system ending the session (revoked consent, screen off).
  https://developer.android.com/reference/android/media/projection/MediaProjection

## Foreground service requirements
- A media-projection capture runs in a foreground service declared with
  `foregroundServiceType="mediaProjection"` plus the
  `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission. For apps targeting
  Android 14, the required order is: collect consent, call
  `startForeground` with the mediaProjection type, then
  `getMediaProjection`.
  https://developer.android.com/develop/background-work/services/fgs/service-types#media-projection

## Encoding
- A `MediaRecorder` (H.264 video in an MP4 container) consumes a
  `Surface` backed by a `VirtualDisplay` created with
  `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`, so the real screen content -
  home screen, widgets, other apps - is encoded at the display's real
  size and density. No audio source is set, so no microphone permission
  is involved and recordings are silent. `MediaRecorder.stop()` throws
  when no frame was produced; the partial file is then unusable and is
  deleted.
  https://developer.android.com/reference/android/media/MediaRecorder
  https://developer.android.com/reference/android/hardware/display/VirtualDisplay

## Why this exists here
Samsung ships no built-in screen recorder on the Galaxy A24, and the
queue/WebView capture path records only the app's own WebView, not the
home screen. MediaProjection is the documented, permission-gated route
that covers both. Recordings stay local; sharing is a manual per-file
action.

## Follow-up findings (2026-11-19, 0.1.4 work)

- On Android 14 (targetSdk 34) the system consent sheet itself offers
  capturing a single app's window instead of the whole screen, via
  `MediaProjectionConfig.createConfigForUserChoice()`, which is the
  default behavior when the app passes no config. App-mode capture
  excludes the status bar, navigation bar and system UI. No app code is
  required beyond targeting API 34.
  https://developer.android.com/media/grow/media-projection
  https://developer.android.com/reference/android/media/projection/MediaProjectionConfig
- An overlay view (for example a floating stop button) marked
  `FLAG_SECURE` is excluded from MediaProjection capture, but the
  captured video shows a black rectangle where the overlay sat - the
  pixels are withheld, not composited over. That rules out an overlay
  as a way to keep recorder controls out of the footage; the 0.1.4
  start-delay option covers the same need without touching the capture.
  https://stackoverflow.com/questions/40255237/hide-a-secure-view-completely-in-mediaprojection-android
  Emulator enforcement of secure-surface exclusion is inconsistent, so
  this behavior cannot be reliably tested on the emulator either.
- Cropping fixed bands (status bar, navigation bar) out of the capture
  is done by remapping the texture coordinates of the virtual-display
  frame before encoding, so the output video is genuinely shorter in
  height rather than black-banded. Bar heights come from
  `WindowMetrics` system-window insets on API 30+, with a resource
  fallback. Whether the inset values on a particular phone match its
  real bars has to be verified on that phone.
