package com.omerbitas.rtspwatch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Viewing away from home: the camera's LAN address can't be reached over LTE, so pictures come
 * through Home Assistant's HTTPS API instead, authenticated with a long-lived access token.
 * The live view uses {@link HaLiveStream}; this class provides snapshots, used by the tile and as
 * the live view's fallback.
 */
final class HomeAssistant {

    private static final int TIMEOUT_MS = 15000;

    final String baseUrl;
    final String token;
    final String camera;

    private HomeAssistant(String baseUrl, String token, String camera) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.camera = camera;
    }

    /** Returns the configured Home Assistant access, or null if there is none. */
    static HomeAssistant fromResources(Context context) {
        String url = context.getString(R.string.ha_url).trim();
        String token = context.getString(R.string.ha_token).trim();
        String camera = context.getString(R.string.ha_camera).trim();
        if (url.isEmpty() || token.isEmpty() || camera.isEmpty()) return null;
        return new HomeAssistant(url, token, camera);
    }

    private HttpURLConnection open(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("Authorization", "Bearer " + token);
        int code = c.getResponseCode();
        if (code == 401 || code == 403) {
            c.disconnect();
            throw new IOException("Home Assistant rejected the access token");
        }
        if (code != 200) {
            c.disconnect();
            throw new IOException("Home Assistant: HTTP " + code);
        }
        return c;
    }

    /** One still picture, resized by Home Assistant to the given width. */
    Bitmap snapshot(int width) throws IOException {
        HttpURLConnection c = open("/api/camera_proxy/" + camera + "?width=" + width);
        // Closing just the stream (no disconnect) lets the next picture reuse the HTTPS connection.
        try (InputStream in = c.getInputStream()) {
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap == null) throw new IOException("Home Assistant sent no picture");
            return bitmap;
        }
    }

    interface FrameListener {
        void onFrame(Bitmap frame);

        void onError(Exception e);
    }

    /**
     * Shows the camera as one snapshot after another until {@link #stop()}. (Home Assistant's
     * MJPEG endpoint isn't used: for a Generic camera without a still image URL it ends at once.)
     */
    final class Stream implements Runnable {
        /** Minimum time between pictures (the round trip through Home Assistant usually takes longer). */
        private static final long MIN_INTERVAL_MS = 300;
        /** Give up after this many failed pictures in a row. */
        private static final int MAX_ERRORS = 5;

        private final FrameListener listener;
        private final int width;
        private volatile boolean running = true;
        private volatile int frames;

        Stream(int width, FrameListener listener) {
            this.width = width;
            this.listener = listener;
        }

        int frames() {
            return frames;
        }

        void stop() {
            running = false;
        }

        @Override
        public void run() {
            int errors = 0;
            while (running) {
                long start = System.currentTimeMillis();
                try {
                    Bitmap frame = snapshot(width);
                    errors = 0;
                    frames++;
                    if (running) listener.onFrame(frame);
                } catch (IOException e) {
                    // A bad token won't fix itself; anything else gets a few more tries.
                    if (String.valueOf(e.getMessage()).contains("access token") || ++errors >= MAX_ERRORS) {
                        if (running) listener.onError(e);
                        return;
                    }
                }
                long wait = errors > 0 ? 1000 : MIN_INTERVAL_MS - (System.currentTimeMillis() - start);
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }
    }
}
