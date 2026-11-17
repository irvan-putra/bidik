# bidik

An Android companion for reviewing web pages on a real phone: open the
app, it picks up a queue of pages to look at, captures the screens, and
sends the captures back to a trusted contact you choose.

**Status: prototype.** The target flow is automatic: open the app, the
queue syncs in, pages render and capture, results send back. The current
stage is earlier than that: the capture pipeline (render, settle,
screenshot) is implemented and unit-tested with an emulator pixel check
wired up. The messaging transport code exists (app side) but is NOT
runtime-verified or enabled yet.
Today the app reads its queue from a local file, runs captures from a
manual button, and writes results locally. Nothing is sent anywhere
automatically, and the automatic flow stays the goal, not a dropped
feature.

Feature status:

| Feature | Status |
| --- | --- |
| WebView capture (viewport + full-page) | implemented, emulator pixel check pending |
| Queue list UI | implemented |
| Local file queue/result transport | implemented (development path) |
| DeltaChat transport (queue in, captures out) | app-side code present, no runtime verification; gated off pending pairing approval |
| Real-Chrome capture mode | not planned for v1 |
| Automatic queue sync + send-back | goal; blocked on transport |
| In-app auto-update from releases | planned follow-up |
| Signed test-APK workflow (manual, exact-commit tests first) | implemented, first release pending |

## Releases

No APK has been published yet. When a release exists it will be a direct
GitHub release asset (`bidik-vX.Y.Z.apk`, no ZIP); see the repository's
Releases page. The first build will be the capture prototype: it reads its
queue from a local file, captures when you press the button and writes
results locally. It does not sync or send anything; the automatic sync and
send-back flow remains the goal. Builds come from the manual `sign test apk`
workflow, which tests the exact commit without any secrets before a separate
minimal job signs the unsigned APK.

## Capture fidelity

The app renders pages with the phone's system WebView at the phone's real
screen size, density and font scale. WebView and Chrome for Android are
built from the same Chromium engine family, and the app applies layout
settings that approximate Chrome's defaults, but rendering is not
guaranteed identical to Chrome: no Chrome cookies or logins, no Sync or
Data Saver, and some web platform APIs behave differently. Every capture
records the WebView package version in its result metadata so the actual
renderer is always known. Fidelity claims wait on the emulator pixel
check and a run on a physical phone.

## Pairing and privacy

- The app is built to talk to exactly one trusted contact over DeltaChat.
  The contact address is entered in the app at setup time: runtime config,
  never in source, and public builds ship unconfigured.
- Account setup is an explicit action from the setup screen, never
  automatic on first launch. The DeltaChat profile lives only in the
  app-private files dir (`android:allowBackup="false"`).
- Sending is manual: a run starts from the app's button. There is no
  background sending in v1.
- No page queues, captures, contact addresses or personal data belong in
  this repo. Tests use public fixture URLs only.
- See SECURITY.md for the URL guardrails and resource caps.

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

## Releases

Manual GitHub Actions release (draft): version stamp, icon badge, tag,
signed APK named `bidik-vX.Y.Z.apk` as a direct release asset. The signing
keystore is created once and stored in GitHub Actions secrets (write-only)
through the repo's release custodian; the secret names are documented in
release.yml. An in-app updater that pulls new releases automatically is a
planned follow-up.

## Research notes

- [Capturing how a web page looks on a phone, from an app](docs/research/webview-vs-mediaprojection.md) - compares app WebView rendering with Android screen capture; no device-specific measurements.
