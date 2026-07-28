# Local RTSP Webcam Stream

A tiny, fully local RTSP-to-browser viewer. It re-encodes an RTSP camera feed to
MJPEG and serves it over plain HTTP, so you can watch it from your phone's
browser with no internet connection and no cloud service involved.

It works entirely over your LAN (or a direct camera-to-phone WiFi link, see
below) — nothing leaves your network.

## How it works

```
Camera (RTSP) --> ffmpeg (this app) --> your phone's browser
```

`server.js` spawns `ffmpeg` to pull the RTSP stream and re-mux it as MJPEG,
then streams that over HTTP. Any phone/laptop browser on the same network can
open the page and see (near) live video — no app install, no RTSP support
needed on the client.

## Prerequisites

- [Node.js](https://nodejs.org/) 18+
- `ffmpeg` installed and on your `PATH`
  - Debian/Ubuntu/Raspberry Pi OS: `sudo apt install ffmpeg`
  - macOS: `brew install ffmpeg`
- This app runs on **some machine that can reach the camera** — a laptop, a
  Raspberry Pi, a NAS, or (via Termux) the phone itself. It does not run
  inside the phone's regular browser.

## Setup

```bash
cd local-rtsp-webcam-stream
npm install
cp .env.example .env
```

Edit `.env` and set your camera's RTSP URL, e.g.:

```
RTSP_URL=rtsp://username:password@192.168.1.175/live0
PORT=8080
```

**Never commit your real `.env` file** — it contains your camera's password.
`.gitignore` already excludes it.

## Run

```bash
npm start
```

Then, from your phone's browser (same network as the machine running this),
open:

```
http://<LAN-IP-of-this-machine>:8080
```

Find `<LAN-IP-of-this-machine>` with `ip addr` (Linux), `ifconfig` (macOS), or
`ipconfig` (Windows) — look for the address on the LAN interface (usually
`192.168.x.x`).

## Connecting your phone to the camera with no router

Whether you can skip a router entirely depends on the camera:

- **If your camera supports AP/hotspot mode** (broadcasting its own WiFi
  network — common on cheap battery/PTZ cameras during initial setup), your
  phone can join that network directly, and this app can run on any device
  also on that network (including the phone itself, via Termux). Check the
  camera's app/manual for an "AP mode" or "direct connect" option.
- **If your camera only joins existing WiFi networks (client mode) or is
  Ethernet-only**, it cannot create its own network, so some intermediary is
  required — a router/switch, or your phone's own mobile hotspot with the
  camera joining it. There's no way around this for that type of camera.

Your current URL (`192.168.1.175`) shows the camera already joined a router's
LAN, which doesn't by itself tell you whether it also supports AP mode —
that's a separate firmware feature to check for.

## Notes

- Latency is roughly 0.5–2s (MJPEG re-encode), which is fine for monitoring
  but not for anything timing-sensitive.
- To debug ffmpeg issues, temporarily remove the empty `stderr` handler in
  `server.js` and pipe it to `process.stderr` instead.
- If your RTSP URL's username/password contains special characters, they
  must be percent-encoded (e.g. `@` becomes `%40`).
