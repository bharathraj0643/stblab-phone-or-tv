# StbLab Android host

This Android TV app is the first host/API milestone for the BPL TV. It binds an embedded HTTP server
to `0.0.0.0:8080`, serves a packaged same-origin picker shell, and stores channel data in the app's
private files directory. Mutating API routes require the 6-digit PIN shown on the TV; eight failed
PIN attempts trigger a three-minute lockout.

## Build and run

Open this directory in Android Studio with Android SDK 35 installed, or build with Gradle:

```sh
gradle :app:assembleDebug
```

Launch **StbLab**, choose **Start Server**, then open the displayed URL in the Airtel box browser.
The server is foreground-service owned and stops when **Stop Server** is selected.

## API

The app serves StbLab-compatible `/api/v1/genres`, `/channels`, and `/remote` route shapes. Channel
and genre data are persisted locally. Requests that mutate data or control the remote require the
`X-StbLab-Token` header; exchange the displayed PIN at `POST /api/auth` to obtain a token.

## Current integration state

The packaged page is a lightweight same-origin shell, not the complete Next.js StbLab picker. The
Remote v2 API currently reports that pairing is unavailable and returns `503` for remote actions.
The Android TV Remote v2 pairing/session code, full picker static export, target BPL firmware
validation, and channel database migration still need implementation before this app can meet the
full deployment acceptance criteria. HTTP is plain LAN traffic; keep the server on a trusted local
network and do not port-forward it.
