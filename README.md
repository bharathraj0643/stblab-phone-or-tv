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
5. The web server starts automatically when StbLab opens and after the TV boots. Open its URL on the
   Airtel browser. The picker opens directly without a PIN gate. Tune channels and edit the guide
   from the browser.

The pairing screen also has **Find Airtel Box**. It searches for `_androidtvremote2._tcp` services
over mDNS and probes port 6466 on the local IPv4 subnet. Select a discovered device to fill its
address, then pair as usual. Manual IP entry remains available if multicast or subnet scanning is
blocked by the router.

## Build

Open this directory in Android Studio and select **Build → Assemble Module ‘StbLabAndroid.app’**. The
debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The host runs as a low-importance foreground service so Android can keep it alive in the background.
It starts at boot and when the app opens; Android may show its silent, ongoing service notification.

To rebuild the picker UI, run `npm ci` and `npm run build:android` in `web/`; that exports the Next.js
page and copies its static assets into the Android app before packaging.

## API and current limits

The app serves `/api/v1/remote/*`, `/api/v1/genres`, and `/api/v1/channels/*` using the StbLab route
shapes. The picker API is available directly to devices on the local network. Only short key presses
are implemented; key hold/release directions are not.

The bundled picker is built from the `web/` Next.js source as a static export. Channel data is
seeded on first launch from `app/src/main/assets/default-channels.json` and persisted as app-private
JSON. The **JSON DATA** button opens a separate workflow to load the current config into an editor,
download it, paste/edit JSON, or choose a `.json` file and apply it. Applying JSON replaces the
current genre/channel configuration; the existing **EDIT CHANNELS** workflow is unchanged.

JSON format version 1 is `{ "version": 1, "genres": [...] }`, where each genre has `id`, `name`,
`sortOrder`, and `channels`; each channel has `id`, `genreId`, `name`, `dummyNumber`,
`redirectNumber`, `sortOrder`, `isFavorite`, and `favoriteOrder`. Import validates IDs, references,
names, and number ranges before saving. Existing app data is preserved on upgrades; the bundled
defaults are copied only when no user configuration exists yet.

Verify pairing, LAN access, standby, and reconnect behavior on the BPL/Airtel firmware. HTTP traffic
between browser and BPL host is not encrypted.

The protocol sources adapted from `xstream-remote` are under `app/src/main/java/com/xstream/remote/`.
The corresponding MIT license notice is packaged at `app/src/main/assets/licenses/xstream-remote-MIT.txt`.
