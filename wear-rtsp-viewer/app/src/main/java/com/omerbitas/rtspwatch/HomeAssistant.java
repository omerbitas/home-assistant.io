package com.omerbitas.rtspwatch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Viewing away from home: the camera's LAN address can't be reached over LTE, so pictures come
 * through Home Assistant's HTTPS API instead, authenticated with a long-lived access token.
 * Home Assistant serves the camera as MJPEG (a stream of JPEG pictures), without sound.
 */
final class HomeAssistant {

    private static final int TIMEOUT_MS = 10000;

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
        try (InputStream in = c.getInputStream()) {
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap == null) throw new IOException("Home Assistant sent no picture");
            return bitmap;
        } finally {
            c.disconnect();
        }
    }

    interface FrameListener {
        void onFrame(Bitmap frame);

        void onError(Exception e);
    }

    /** Plays Home Assistant's MJPEG stream of the camera until {@link #stop()}. */
    final class Stream implements Runnable {
        private final FrameListener listener;
        private final int maxSize;
        private volatile boolean running = true;
        private volatile HttpURLConnection connection;
        private volatile int frames;

        Stream(int maxSize, FrameListener listener) {
            this.maxSize = maxSize;
            this.listener = listener;
        }

        int frames() {
            return frames;
        }

        void stop() {
            running = false;
            final HttpURLConnection c = connection;
            if (c == null) return;
            // disconnect() may touch the network, so keep it off the caller's (main) thread.
            new Thread(new Runnable() {
                @Override
                public void run() {
                    c.disconnect();
                }
            }, "ha-disconnect").start();
        }

        @Override
        public void run() {
            try {
                // The picture rate is the camera's frame rate setting in Home Assistant (often 2/s).
                HttpURLConnection c = open("/api/camera_proxy_stream/" + camera);
                connection = c;
                if (!running) {
                    stop();
                    return;
                }
                InputStream in = new BufferedInputStream(c.getInputStream(), 64 * 1024);
                while (running) {
                    byte[] jpeg = readPart(in);
                    if (jpeg == null) throw new IOException("Home Assistant stream ended");
                    Bitmap frame = decode(jpeg, maxSize);
                    if (frame != null) {
                        frames++;
                        listener.onFrame(frame);
                    }
                }
            } catch (Exception e) {
                if (running) listener.onError(e);
            } finally {
                stop();
            }
        }
    }

    /**
     * Reads the next part of a multipart/x-mixed-replace stream: headers, then the JPEG. Uses
     * Content-Length when given, otherwise reads up to the JPEG end marker (FF D9).
     */
    static byte[] readPart(InputStream in) throws IOException {
        int length = -1;
        String line;
        boolean inHeaders = false;
        while ((line = readLine(in)) != null) {
            if (line.isEmpty()) {
                if (inHeaders) break; // blank line ends the part headers
                continue;
            }
            if (line.startsWith("--")) {
                inHeaders = true;
                continue;
            }
            inHeaders = true;
            String lower = line.toLowerCase(Locale.US);
            if (lower.startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(15).trim());
            }
        }
        if (line == null) return null;
        if (length >= 0) {
            byte[] data = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(data, read, length - read);
                if (n < 0) return null;
                read += n;
            }
            return data;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        int previous = -1;
        int b;
        while ((b = in.read()) != -1) {
            out.write(b);
            if (previous == 0xFF && b == 0xD9) return out.toByteArray();
            previous = b;
        }
        return null;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') line.write(b);
            if (line.size() > 4096) throw new IOException("Bad stream from Home Assistant");
        }
        if (b == -1 && line.size() == 0) return null;
        return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    /** Decodes a JPEG, subsampled so neither side is much larger than maxSize. */
    private static Bitmap decode(byte[] jpeg, int maxSize) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= maxSize
                && bounds.outHeight / (options.inSampleSize * 2) >= maxSize / 2) {
            options.inSampleSize *= 2;
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
    }
}
