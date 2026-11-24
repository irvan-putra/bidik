# bidik

An Android companion for reviewing web pages on a real phone: open the
app, it picks up a queue of pages to look at, captures the screens, and
sends the captures back to a trusted contact you choose.

**Status: prototype.** The target flow is automatic: open the app, the
queue syncs in, pages render and capture, results send back. What exists
today:

- Capture (render, settle, screenshot) with the phone's WebView: implemented
  and checked on an emulator.
- A local-file queue and local results, for development and for use without
  any messaging account.
- An optional, explicit setup path for the full flow. The setup screen has a
  "Create messaging account" button. Only when you tap it, the app creates a
  new DeltaChat account on the relay nine.testrun.org (a network call). Its
  keys live only on the phone and there is no backup or export yet, so
  clearing the app's data loses the account. Next you pair with the
  assistant's invite QR (scan or paste). Once paired, opening the app
  attempts to sync the queue, capture each page and send the results back
  to that one paired contact. That path is implemented, not verified: it is
  an attempt on each open, not a delivery guarantee.
- Not verified yet: account creation against the live relay, pairing, and
  send-back have not been run end to end on a real phone. A fresh,
  unconfigured install creates no account and contacts no relay until you
  tap the setup buttons. After you have set up and paired, later launches
  may try to sync and send again without asking each time.

Feature status:

| Feature | Status |
| --- | --- |
| WebView capture (viewport + full-page) | implemented; fixture-page pixels verified by an emulator CI run; real-page and physical-phone fidelity not verified |
| Queue list UI | implemented |
| Home screen layout (status first, everyday controls, setup and diagnostics in a side menu) | implemented |
| Local file queue/result transport | implemented (works with no account) |
| Messaging account creation (explicit button, nine.testrun.org) | implemented; live provisioning not verified |
| Pairing by invite QR (secure join) | implemented; not verified on a real phone |
| Queue in / captures out over DeltaChat after pairing, attempted on each open | implemented; sync and send-back not verified end to end; activity-owned lifecycle not verified |
| Screen recording (system consent prompt each session, local silent MP4, manual per-file share) | implemented; stop/save/stillness verified on an API 34 emulator, not on a physical phone |
| Recording options (start delay, auto-stop, hide system bars) | implemented; emulator verification pending |
| GIF loop export (auto loop-point detection with manual trim fallback) | implemented; emulator verification pending |
| Account backup/export | not built |
| Real-Chrome capture mode | not planned for v1 |
| In-app auto-update from releases | planned follow-up |
| Signed test-APK workflow (manual, exact-commit tests first) | implemented, first release pending |

## Releases

The signed test APK v0.1.2 is published as a direct [GitHub release asset](https://github.com/irvan-putra/bidik/releases/download/v0.1.2/bidik-v0.1.2.apk) (`bidik-v0.1.2.apk`, no ZIP); see the [release page](https://github.com/irvan-putra/bidik/releases/tag/v0.1.2). It adds opt-in screen recording (per-session system consent, silent local MP4, manual share) and adaptive/themed launcher icons. The recording flow is verified on an API 34 emulator - consent accept and deny, stop and save, playable video, and elapsed time kept through stillness (see [docs/verification/screen-recording-runtime.md](docs/verification/screen-recording-runtime.md)) - but not yet on a physical phone. Known 0.1.2 defect: its recorder shortens the video when the screen is still (a 6s still recording comes out ~1.7s); the corrected pipeline ships in [v0.1.3](https://github.com/irvan-putra/bidik/releases/tag/v0.1.3). A v0.1.4 test release with the recording options and GIF loop export above is in preparation. This remains a prototype and the unverified live path described above is not a verified phone-to-relay service.

Earlier: [v0.1.1](https://github.com/irvan-putra/bidik/releases/tag/v0.1.1), [v0.1.0](https://github.com/irvan-putra/bidik/releases/tag/v0.1.0).

Builds come from the manual `sign test apk` workflow, which tests the exact commit without any secrets before a separate minimal job signs the unsigned APK. An in-app updater that pulls new releases is a planned follow-up.

## Jin link (parked)

A planned route sends capture requests in and results out through the shared
DeltaChat bot host Jin, with no phone number or login. Client code exists but
the app does not use it yet and nothing depends on it. Details and open
items: [docs/jin-link.md](docs/jin-link.md).

## Capture fidelity

The app renders pages with the phone's system WebView at the phone's real
screen size, density and font scale. WebView and Chrome for Android are
built from the same Chromium engine family, and the app applies layout
settings that approximate Chrome's defaults, but rendering is not
guaranteed identical to Chrome: no Chrome cookies or logins, no Sync or
Data Saver, and some web platform APIs behave differently. Every capture
records the WebView package version in its result metadata so the actual
renderer is always known. The emulator capture check has verified the
pixels of a public fixture page in CI. Fidelity on real pages and on a
physical phone is not verified yet, so no such claim is made.

## Pairing and privacy

- The app is built to talk to exactly one trusted contact over DeltaChat.
  The contact is set by the pairing QR at setup time: runtime config,
  never in source, and public builds ship unconfigured.
- Creating the messaging account and pairing are explicit actions from the
  setup screen, never automatic on first launch. The account is created on
  nine.testrun.org only when you tap the button. The DeltaChat profile
  lives only in the app-private files dir (`android:allowBackup="false"`).
- Unpaired, a capture run starts only from the app's button and results stay
  on the phone. Once paired, opening the app attempts to sync, capture and
  send results back to the paired contact without a per-run tap. The first
  setup (account creation, pairing) is the explicit consent step; later
  launches do not ask again.
- There is no dedicated background service for the capture queue (the
  service class is a stub that stops immediately). Screen recording is
  the one real background component: a foreground service with an
  ongoing notification, started only after the per-session system
  consent prompt, stopped from the notification, the app, the system or
  a ten-minute cap. The capture runs are owned by the activity: its
  coroutine scope is cancelled when the activity is destroyed, not when
  it is stopped, so a run may keep going while the app is paused in the
  background. How that behaves on a real phone is not verified; do not
  rely on foreground-only behavior.
- No page queues, captures, contact addresses or personal data belong in
  this repo. Tests use public fixture URLs only.
- See SECURITY.md for the URL guardrails and resource caps.

## Screen recording

A separate, opt-in tool: the Record screen button shows Android's system
consent prompt every session (no silent capture is possible), then a
foreground service records the real screen - home screen and widgets
included - to a silent MP4 in the app-private files dir. There is no
microphone access, no upload, and no queue integration: recordings stay
on the phone until you share one explicitly from the app's list or clear
the app's data. Recording stops from the notification action, the app's
stop button, an optional auto-stop timer, or automatically after ten
minutes; a recording that receives no frames deletes its partial file.
An optional start delay gives time to reach the screen you want before
capture begins, and an optional hide-bars setting crops the status and
navigation bars out of the video entirely (the video itself is shorter,
no black bands). Recordings can also be exported as looping GIFs: the
exporter looks for the loop point where the screen returns to a state
it showed earlier, requires real motion inside the candidate so a
static screen cannot pass as a loop, ignores the cropped bar rows while
matching, and falls back to a manual start/end trim when no confident
loop is found. The original MP4 is never modified. See
[the MediaProjection research note](docs/research/screen-recording-mediaprojection.md).

## Queue / result protocol (v1, draft)

The app's open interface with its paired contact. Queue message: JSON
`{"type":"capture-queue","v":1,"pages":[{"id","url","mode","full_page","wait_ms","notes"}]}`.
Result message per page: JSON `{"type":"capture-result","v":1,"page_id",
"url_final","captured_at","engine","webview_version","device","load_ms",
"settle_ms","captures","status","error"}` with the PNG attachments,
followed by a run summary. See `QueueProtocol.kt` for the exact shape.

## Verification

- `QueueProtocolTest`, `UrlGuardTest`: protocol and URL guard unit tests.
- `MainActivityRenderTest`: renders the queue UI with real Skia at phone
  width; the PNG is uploaded as a CI artifact for pixel inspection.
- `capture-check` workflow (manual): runs the full capture pipeline on an
  Android emulator against a public fixture page and uploads the captured
  PNGs. No real-page fidelity claim is made before those pixels, and
  ultimately a run on a physical phone, are inspected.

## Research notes

- [Capturing how a web page looks on a phone, from an app](docs/research/webview-vs-mediaprojection.md) - compares app WebView rendering with Android screen capture; no device-specific measurements.
- [Recording the screen from an app: MediaProjection](docs/research/screen-recording-mediaprojection.md) - consent, foreground-service and encoding requirements behind the screen recorder; no device-specific measurements.

---

Built with AI assistance (Instinct and others). Not everything has been reviewed by Irvan.
