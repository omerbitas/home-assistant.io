# RTSP Watch

A small, standalone Wear OS app for watching a live RTSP camera feed on a Samsung Galaxy Watch Ultra
(or any Wear OS 3+ watch).

- Plays H.264 and H.265 (HEVC) video with the watch's hardware decoder, tuned for low latency
- Pulls RTP over the RTSP TCP connection, so it doesn't need UDP ports and works through NAT
- Supports Basic and Digest camera logins (`rtsp://user:password@host:554/path`) and `rtsps://`
- Connects the watch directly to Wi‑Fi while a stream is open (not through the phone's Bluetooth link)
- Reconnects automatically if the stream drops
- Plays the camera's sound (AAC or G.711). Sound starts off: tap to turn it on, swipe up/down for volume
- Includes a **Camera** tile: swipe from the watch face and tap the button to open the live view
- Double-tap to switch between fit and fill, long-press for connection statistics, swipe right to go back

A prebuilt APK is at [`release/RtspWatch.apk`](release/RtspWatch.apk).

## Install on the Galaxy Watch Ultra

1. On the watch, open **Settings → About watch → Software information** and tap **Software version**
   five times to turn on Developer options.
2. In **Settings → Developer options**, turn on **ADB debugging** and **Wireless debugging**.
   Connect the watch to the same Wi‑Fi as your computer.
3. In **Wireless debugging**, tap **Pair new device** and pair from your computer
   ([platform-tools](https://developer.android.com/tools/releases/platform-tools)):

   ```sh
   adb pair <watch-ip>:<pairing-port>      # enter the pairing code shown on the watch
   adb connect <watch-ip>:<port>           # IP and port shown on the Wireless debugging screen
   adb install release/RtspWatch.apk
   ```

## Adding streams

Open **RTSP Watch** on the watch and type the URL into the field. Typing a URL on a watch keyboard is
slow, so you can also send it from your computer while connected with adb:

```sh
adb shell am start -n com.omerbitas.rtspwatch/.MainActivity --es add_url "rtsp://user:pass@192.168.1.20:554/stream2"
```

Tap a stream to watch it. Long-press a stream to delete it. To add the tile, long-press the watch face's tile area
(or swipe to the end of your tiles), tap **+** and choose **Camera**. Special characters in the password must
be URL-encoded (for example, `@` → `%40`).

Typical URLs (use the camera's **sub-stream**: it is lower resolution, so it decodes quickly and uses
less battery):

| Camera | Sub-stream URL |
| --- | --- |
| Hikvision | `rtsp://user:pass@IP:554/Streaming/Channels/102` |
| Dahua / Amcrest | `rtsp://user:pass@IP:554/cam/realmonitor?channel=1&subtype=1` |
| Reolink | `rtsp://user:pass@IP:554/h264Preview_01_sub` |
| Tapo | `rtsp://user:pass@IP:554/stream2` |
| Home Assistant go2rtc / Frigate | `rtsp://HA-IP:8554/<camera_name>` |

## Notes

- The watch must be able to reach the camera. That means the same Wi‑Fi network, or a VPN when you are
  away. If Wi‑Fi isn't available within 10 seconds, the app falls back to the watch's default network
  (LTE or the phone link).
- The screen stays on while a stream is open, which uses battery quickly. Swipe away when you're done.

## Building

Without Gradle (this is how `release/RtspWatch.apk` was built). You need a JDK, the Debian/Ubuntu
`aapt`, `apksigner`, `dalvik-exchange` and `zipalign` packages, and an API 34 `android.jar`:

```sh
ANDROID_JAR=/path/to/android-34/android.jar ./build.sh
# -> build/manual/RtspWatch.apk
```

With Gradle, open this folder in Android Studio (or run `gradle assembleDebug`). The Gradle build
hasn't been tested yet because Google's Maven repository couldn't be reached when this was written.
The app has no third-party dependencies.
