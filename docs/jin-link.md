# Bidik and Jin: the parked link

Status as of 2026-11-24: **parked**. Nothing depends on it; it was an idea,
not a need. The full design record lives in the Jin repo:
[docs/bidik-link.md](https://github.com/irvan-putra/jin/blob/main/docs/bidik-link.md).

## Intent

Jin is the shared on-phone DeltaChat bot host for the owner's apps. The
intended link, in both directions:

- **In:** an assistant or a chat sends Bidik a capture request over DeltaChat,
  Jin hands it to Bidik, Bidik renders the pages.
- **Out:** Bidik sends its results and screenshots back over DeltaChat through
  Jin.

No phone number and no login: public chatmail relay plus an invitation QR.

## What exists in this repo

- `app/src/main/java/com/vanipy/bidik/jin/`: a client and a mirror of Jin's
  transport contract v1, with host unit tests (`JinClientTest`). Every call can
  fail with NOT_AUTHORIZED until the owner grants Bidik in Jin.
- Manifest visibility for Jin's provider (`com.vanipy.jin.transport`).

## What is unproven or missing

- The UI does not call the Jin client. Bidik's working messaging path is its
  own legacy account (see README, "Pairing and privacy").
- No joint run with Jin on a real phone; OS-lifecycle behavior is unverified.
- Jin's live-relay route runs failed on 2026-11-20 and were not re-run.
