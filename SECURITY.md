# Security notes

Threat model and guardrails for mata. The app receives a page queue over a
messaging channel and sends screenshots back to one paired contact. The
main risks are a hostile or malformed queue and accidental leakage of
private content into captures.

## What the app does about it

- **URL allowlist.** Only public `http`/`https` URLs load. Loopback,
  private, link-local, CGNAT, multicast and reserved IP literals, plus
  `localhost`/`.local`/`.internal` hostnames, are refused at queue parse
  time (`UrlGuard`). Before loading, the engine also resolves the host
  and refuses private resolved addresses (loopback, site-local,
  link-local, multicast). Main-frame redirects are re-checked against
  the literal-IP guard on every navigation (`shouldOverrideUrlLoading`).
- **No JS bridges.** The app never calls `addJavascriptInterface`; page
  JavaScript gets no access to app code or files.
- **Resource caps.** A queue is limited to 20 pages, per-page load has a
  30s timeout, settle wait is clamped to 15s, and full-page captures are
  capped at 16000px height to bound memory and runtime.
- **Explicit setup, automatic runs once paired.** Creating the
  messaging account (a new account on relay nine.testrun.org) and
  pairing are explicit button actions, and pairing completes the
  standard secure-join handshake. Once paired, opening the app syncs
  the queue, captures each page and sends results back automatically,
  with no per-run tap. Unpaired, a run starts only from the app's
  button. Results go only to the single paired contact.
- **Diagnostics without secrets.** The app keeps an on-device log
  (`filesDir/logs/events.log`, one rotated previous generation) of stage
  names, timings, counters and bounded error categories, so a failing run says
  which step failed. The log stores bounded error categories, page
  ordinals, timings and counters only; arbitrary URLs, hostnames, page
  identifiers, exception text, keys, pairing QR payloads, message
  contents and captured page data are omitted. The log leaves the
  phone only via the explicit Share action in the Diagnostics view.
- **Local capture archive.** Every run writes its screenshots to its own
  `captures/run-*` directory in the app-private files dir. Archives are
  kept, never auto-deleted; they leave the phone only via the paired
  transport and are gone only when the app's data is cleared. Account
  keys live only on the phone (backup/export is not built yet), so
  clearing app data also loses the messaging account.
- **Private profile.** The messaging profile (when transport is enabled)
  lives in the app-private files dir with `android:allowBackup="false"`.
- **Repo hygiene.** No queues, captures, contact addresses, credentials or
  personal data in source, tests or docs. The signing key exists only in
  GitHub Actions secrets.

## Known limits

- Subresource requests inside an allowed page (images, scripts, XHR) are
  not individually guarded; only main-frame navigations are. A page that
  embeds a private-network subresource could pull it into view.
- DNS is checked before the load, but a hostname that changes answers
  between the check and the actual connect (rebinding) is not defended
  in v1.
- The WebView runs page JavaScript from queued pages. Only queue pages you
  are comfortable rendering on the device.
- WebView rendering is the same Chromium engine family as Chrome but not
  guaranteed identical; treat captures as review evidence, not proof of
  what every Chrome user sees.
