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
It starts at boot and when the app opens, reports live/offline state in the app, and retries server
startup failures with backoff. **STOP SERVER & EXIT** stops the server, removes the app screen from
Recents, and terminates the app process. Android may show a silent, ongoing service notification
while the server is running.

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
# tvQuickActions Gamepad compatibility

The Android Remote v2 client leaves remote text-input (IME) negotiation disabled by default,
which keeps the target TV's third-party keyboard services from competing for the IME. Remote
key, power, volume, and app-link controls remain available. To enable this app's text injection,
turn on **REMOTE TEXT INPUT** in the Android TV app; changing the setting reconnects the session.

## Choose a setup

| Folder | Setup | How it works | Download |
| --- | --- | --- | --- |
| [`method-1_laptop`](https://github.com/bharathraj0643/stblab) | Laptop and browser | Runs the backend on a laptop. Open the web page from a phone or TV browser on the same network. | [releases](https://github.com/bharathraj0643/stblab/releases) |
| [`method-2_phone-or-tv`](https://github.com/bharathraj0643/stblab-phone-or-tv) | Android host | Runs the backend on android host and remote client on any browser tv or phone. | [releases](https://github.com/bharathraj0643/stblab-phone-or-tv/releases) |
| [`method-3_gui-recommended-method`](https://github.com/bharathraj0643/stblab-gui) | Native Android TV app | Runs the picker and remote controls directly as a native Android app, without a LAN web server or browser. intended to be used on android tv only, although it runs on android phone too, but ui controls are designed for a tv remote. | [releases](https://github.com/bharathraj0643/stblab-gui/releases) |

## Credits

The project builds on ideas and protocol work from [StbLab](https://github.com/Harsh3341/stblab) and [xstream-remote](https://github.com/Vijayapardhu/xstream-remote). The relevant method documentation includes additional attribution and license details.

 [Fully Kiosk Browser](https://play.google.com/store/apps/details?id=de.ozerov.fully) : best browser for tv remote navigation for this purpose of changing channels, use this browser on Android tv.

  [tvQuickActions](https://play.google.com/store/apps/details?id=dev.vodik7.tvquickactions.free&pcampaignid=web_share) : use this to map advanced keycodes for Favorites button, recall previous channel button, quick launch this website from the option key button all from the physical Airtel Remote.

  [atvtools on phone](https://play.google.com/store/apps/details?id=dev.vodik7.atvtools&pcampaignid=web_share) : it can be used for installing android apk on tv (since airtel box is locked), via tv adb option.