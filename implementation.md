# BPL-Hosted StbLab Picker Implementation Plan

## Goal

Run StbLab on the BPL Android TV. The BPL TV hosts the picker UI and API; the Airtel box opens the
BPL TV's LAN URL in its browser. The BPL app then controls the Airtel box using Android TV Remote v2.

Example flow:

1. Install and start StbLab on the BPL TV.
2. Pair the BPL app with the Airtel box.
3. Open `http://<bpl-tv-ip>:8080` in the Airtel box's browser.
4. Use the picker page to select channels and send the existing StbLab commands.

The role split matters: **BPL TV = host and protocol client; Airtel box = browser client and remote
control target.** The Airtel browser must be able to reach the BPL TV over the local network.

## What the repositories provide

### StbLab

- `src/StbLab.Protocol` implements Android TV Remote v2 pairing and control in C#. It targets
  `net10.0` and uses protobuf generation, TLS, sockets, and X.509 certificates.
- `src/StbLab.Api` is an ASP.NET Core API targeting `net10.0`. It owns controllers, the protocol
  session, and channel data access through `Microsoft.Data.Sqlite`.
- `src/StbLab.Cli` provides desktop pairing and command-line operations.
- `web` contains the Next.js picker UI. Its `/api/*` calls are currently rewritten to a separate
  API at `http://127.0.0.1:5080`.

### xstream-remote

`/Users/bharath/gitprojects/xstream-remote` is a native Android app that already speaks the same
Android TV Remote v2 protocol to an Airtel Xstream box. Its Java protocol classes are valuable
implementation references:

- `apk/java/com/xstream/remote/PairingSession.java` implements code-based pairing on port 6467.
- `ClientIdentity.java` generates an RSA identity per install; `TvBridge.java` stores paired
  credentials and host information in app-private storage and coordinates connection/reconnect.
- `AurSession.java` implements the TLS control session on port 6466, protobuf framing, key/text/app
  link commands, state events, and TV certificate fingerprint checks.
- `MainActivity.java` loads packaged HTML in a WebView and connects its JavaScript to native calls.
- `docs/ARCHITECTURE.md` documents the protocol, state model, and component boundaries.

This app is **not itself the desired LAN web host**: its WebView is local to the phone and commands
go directly to the Airtel box. The adaptation is to retain its proven Android protocol/pairing
approach, then add a LAN HTTP server so the Airtel box can load the picker from the BPL TV.

### tv-remote

`/Users/bharath/gitprojects/tv-remote` is the closer reference for hosting: its Android TV APK runs
a Ktor CIO server on the TV, serves a bundled page, exposes JSON endpoints, and reports the LAN URL
from a foreground service. Its implementation is in `tv-remote-apk/app/src/main/kotlin/com/porter/tvremote/`.

Useful server patterns to adapt:

- `HttpServer.kt`: Ktor CIO binding to `0.0.0.0`, static asset routing, JSON endpoints, configurable
  port, occupied-port preflight, and synchronized start/stop lifecycle.
- `RemoteService.kt`: foreground service ownership of the HTTP listener, status publication, and
  asynchronous startup/shutdown.
- `MainActivity.kt`: D-pad-friendly Android TV controls for Start/Stop and port selection, plus a
  visible URL and service state.
- `AndroidManifest.xml`: Leanback launcher metadata and the connected-device foreground service
  declaration/permissions used by that app.

The control mechanism must **not** be copied as-is: tv-remote routes its endpoints to its own TV
through ADB loopback (`AdbController`/`AdbLib`). In our setup BPL is the server host, and Airtel is
the target. Replace those ADB actions with the Android TV Remote v2 client and pairing flow from
xstream-remote (or a verified Android-compatible StbLab protocol implementation). Also replace its
simple bundled `index.html` with StbLab's picker UI and channel API.

## Target architecture

```text
Airtel Xstream box
  Browser opens http://<bpl-tv-ip>:8080/
        |
        | HTTP: picker files + /api/* requests
        v
BPL Android TV: StbLab host app
  foreground service
    embedded HTTP server
      / and static assets -> exported StbLab picker UI
      /api/*              -> StbLab operations
                                |
                                v
                      Android TV Remote v2 client
                                |
                      TLS 6466 / pairing TLS 6467
                                |
                                v
                    Airtel Xstream Remote Service
```

The app running on BPL is both the web server and the Remote v2 client for Airtel. It should not use
ADB for normal remote operations. ADB is only for installing/debugging the app unless a deliberate
diagnostic feature is added.

## Recommended implementation approach

### 1. Use xstream-remote as the Android protocol reference

Start with a small BPL Android TV app and adapt the pure Java protocol pieces from xstream-remote,
or use them as a line-by-line behavioral reference for a Kotlin implementation. This avoids making
the first milestone depend on whether the current C# `net10.0` protocol assembly can run on the
target Android runtime.

Keep protocol work isolated from HTTP and UI code. Use a service layer with operations matching the
current StbLab API (state, health, channel list, tune/redirect, key, text, launch). Reuse StbLab's
existing route and JSON contracts as the specification so the picker needs few changes.

The xstream-remote code is licensed MIT according to its repository; before copying source files,
retain its license/notice and review any third-party notices. Port behavior rather than copying UI
features that StbLab does not need.

### 2. Add LAN HTTP hosting to the BPL app

Adapt the embedded Ktor CIO server pattern from tv-remote, with a foreground service started by an
explicit Start Server action. Provide a TV-friendly status screen showing server state, BPL LAN IP,
port, and pairing/connection health. Handle no Wi-Fi, occupied port, and bind failures clearly.

The server should bind to the LAN interfaces and serve on a configurable port (default `8080`).
Reserve the BPL TV's IP in the router. Keep the service local to the trusted LAN; do not port-forward
it. Since the picker can send remote commands, add a short local access PIN/token or another simple
same-LAN authorization mechanism before exposing mutation endpoints. Do not treat a random URL as
authentication.

Use an Android foreground service for user-visible long-running server operation. tv-remote is a
useful reference for declaring the service type, reporting status, and releasing locks during
shutdown; validate its manifest choices against the BPL firmware and current Android requirements.
Test screen-off,
standby, app process recreation, and reboot on the actual BPL firmware before adding auto-start or a
wake lock. Make auto-start optional and handle Android's background-start restrictions.

### 3. Adapt the picker UI

The current UI is Next.js. Audit it for static-export support and export its browser assets into the
Android app package. The Airtel browser should receive the picker from the BPL HTTP server, with all
API calls sent to relative paths such as `/api/channels` on the same origin.

Replace the current Next rewrite to `127.0.0.1:5080` for this deployment. The browser's localhost is
the Airtel box, not the BPL TV. Use `fetch('/api/...')` or an equivalent same-origin client. Configure
the HTTP server to serve static assets, MIME types, and SPA/Next static-export paths correctly.

If Next static export is blocked by server-only Next.js features, move those interactions into the
HTTP API or replace the affected UI behavior. Do not plan on running `next start` on Android as the
default solution.

### 4. Pair securely on the BPL TV

Use the xstream-remote pairing design: generate a per-install RSA identity, conduct the pairing
handshake with the six-character code shown on the Airtel box, and pin the Airtel box certificate
fingerprint after pairing. Keep private key material in BPL app-private storage; never package it in
the APK, commit it, log it, or put it in shared storage.

The BPL TV app needs a usable way to enter the code displayed on the Airtel box. This can be a
TV-friendly pairing screen operated with the BPL remote or an explicitly designed setup flow. Once
paired, the app connects to the Airtel box on port 6466 and reconnects when the network drops.

### 5. Preserve picker behavior and channel data

Keep StbLab's channel ordering, favorites, and redirect/tune behavior as the source of truth. The
current API uses SQLite. Verify a SQLite implementation that works with the BPL Android ABI, or use
Android Room for the host app while preserving the current schema and migrations. The web UI should
continue to use the existing route semantics and response shapes.

## Phased work

### Phase 1: prove the device-to-device path

- Record the BPL TV Android version, CPU ABI, available storage, and sideloading support.
- Install a minimal Android TV app on BPL that serves a static test page on port 8080.
- Use tv-remote's Ktor server and service as the starting reference for LAN binding, status display,
  port conflicts, and service lifecycle; first verify the checked-out version builds and runs on BPL.
- Open that URL from the Airtel box browser; confirm both devices are on a network that allows
  client-to-client traffic.
- Verify the BPL device can connect to the Airtel Remote v2 service.
- Decide the exact Android language/runtime and embedded server based on this device test.

### Phase 2: protocol and pairing

- Bring up the xstream-remote protocol code in a minimal BPL Android app, preserving its license.
- Keep tv-remote's HTTP server independent from the control implementation behind a small interface;
  replace its ADB controller with the Remote v2 operations needed by StbLab.
- Pair from BPL to Airtel using the on-screen Airtel code.
- Verify key commands, state updates, app launch, and reconnect behavior.
- Keep credentials private and confirm they survive app restarts and upgrades.

### Phase 3: host service and API

- Implement the foreground HTTP server service and status screen.
- Add the StbLab API operations over HTTP, backed by the Android protocol client.
- Add local browser authorization for command endpoints and keep the listener LAN-only.
- Implement channel persistence and import/migration behavior for existing channel data.

### Phase 4: picker UI integration

- Make a static build of the current Next.js picker or adapt its unsupported server-side features.
- Package the generated UI in the Android app and serve it with correct content types and route
  fallback behavior.
- Switch API calls to same-origin relative URLs.
- Test navigation and remote-control actions in the Airtel browser itself.

### Phase 5: install and operational hardening

- Build and install a signed APK on the BPL TV; use ADB for development and upgrades.
- Show a stable URL, reserve the BPL IP, and document opening that URL on Airtel.
- Test service operation through standby, screen-off, network loss, app update, and reboot.
- Add visible errors, local logs, graceful stop, and credential/data-preserving upgrades.

## Acceptance criteria

- StbLab starts on the BPL TV and shows its reachable LAN URL.
- The Airtel box browser loads the picker from the BPL TV without a laptop or other server.
- Picker API calls go to the BPL server on the same origin.
- Pairing and credentials are managed securely by the BPL app.
- Picker actions control the Airtel box through Remote v2 and recover after a network interruption.
- Channel ordering, favorites, and redirect behavior match StbLab's current API/UI behavior.
- Server status and errors in the BPL app reflect whether Airtel can reach the service.

## Risks and checks

- BPL Android version and CPU ABI are unknown and affect app/server/library packaging.
- The xstream-remote implementation is verified on a phone controlling Airtel; it still needs
  validation when running on the BPL TV while the Airtel box simultaneously acts as browser client.
- tv-remote's LAN server and foreground-service patterns are directly reusable, but its ADB control
  calls target the device hosting the app, not the separate Airtel box in this design.
- The BPL TV may not allow network clients to reach a listener, or the home router may isolate Wi-Fi
  clients. This must be tested early.
- Next.js static export, current API JSON contracts, and SQLite support need verification.
- A listener reachable from the LAN can control the Airtel box. Keep access constrained to the home
  network and require authorization for write/control requests.
- Android may stop background work or network access in standby; test on the target device rather
  than assuming the service remains alive indefinitely.

## References

- Local protocol reference: `/Users/bharath/gitprojects/xstream-remote/apk/java/com/xstream/remote/`
  and `/Users/bharath/gitprojects/xstream-remote/docs/ARCHITECTURE.md`.
- Local HTTP-server reference: `/Users/bharath/gitprojects/tv-remote/tv-remote-apk/app/src/main/kotlin/com/porter/tvremote/HttpServer.kt`,
  `RemoteService.kt`, and `MainActivity.kt`.
- Local StbLab implementation: `src/StbLab.Protocol`, `src/StbLab.Api`, `src/StbLab.Cli`, and `web`.
- [Legvan/tv-remote](https://github.com/Legvan/tv-remote): operational reference for an Android app
  hosting an embedded HTTP server and serving a UI to LAN clients.
