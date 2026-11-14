# StbLab Android host

The BPL Android TV runs this app as the LAN web host and Android TV Remote v2 client. Its foreground
service binds the picker/API server to `0.0.0.0:8080`. The packaged web page and API share the same
origin. Channel data and remote pairing credentials remain in app-private storage.

## Pair with Airtel

1. Find the Airtel box IP address in its network settings.
2. On the BPL TV, open StbLab, enter that IP under **Airtel box pairing**, then choose **Pair with Airtel**.
3. The Airtel box displays a six-character pairing code. Enter it on the BPL TV and confirm.
4. Wait for the BPL screen to report **Connected**. The connection uses TLS on port 6466 after pairing
   on port 6467, and stores the Airtel certificate fingerprint for future connections.
5. Start the web server. Open its URL on the Airtel browser and enter the web access PIN shown on BPL.
   Tune a channel or use the remote buttons on the page.

## Build

Open this directory in Android Studio and select **Build → Assemble Module ‘StbLabAndroid.app’**. The
debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## API and current limits

The app serves `/api/v1/remote/*`, `/api/v1/genres`, and `/api/v1/channels/*` using the StbLab route
shapes. Web data mutations and remote commands require the `X-StbLab-Token` header, obtained from
`POST /api/auth` using the PIN shown on the host TV. Eight incorrect PIN attempts trigger a three
minute lockout. Only short key presses are implemented; key hold/release directions are not.

The bundled page is a lightweight picker shell, not the complete Next.js StbLab UI. Channel data is
persisted as app-private JSON rather than imported from the desktop SQLite database. Verify pairing,
LAN access, standby, and reconnect behavior on the BPL/Airtel firmware. Keep the server on a trusted
local network and do not port-forward it; HTTP traffic between browser and BPL host is not encrypted.

The protocol sources adapted from `xstream-remote` are under `app/src/main/java/com/xstream/remote/`.
The corresponding MIT license notice is packaged at `app/src/main/assets/licenses/xstream-remote-MIT.txt`.
