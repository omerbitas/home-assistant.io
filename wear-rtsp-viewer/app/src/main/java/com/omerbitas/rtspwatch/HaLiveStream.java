package com.omerbitas.rtspwatch;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Live video and sound from Home Assistant, the way its own app shows cameras away from home:
 * <ol>
 * <li>log in over the WebSocket API and ask for the camera's stream ({@code camera/stream}),
 * <li>download the resulting HLS stream: init.mp4 plus short fMP4 parts, newest first,
 * <li>unpack the parts ({@link Fmp4}) and hand out the samples at their real pace, since parts
 * arrive in bursts.
 * </ol>
 */
final class HaLiveStream implements Runnable {

    interface Listener {
        void onVideoTrack(String mime, byte[][] parameterSets);

        void onVideoSample(byte[] annexB, long timeUs, boolean keyFrame);

        void onAudioTrack(RtspClient.AudioInfo info);

        void onAudioSample(byte[] data);

        void onError(Exception e);
    }

    private static final int TIMEOUT_MS = 15000;
    /** Home Assistant waits until the stream has started before answering, which can take a while. */
    private static final int START_TIMEOUT_MS = 40000;
    private static final long POLL_MS = 300;
    /** If playback falls this far behind (e.g. a slow network), jump back to real time. */
    private static final long MAX_LATE_MS = 1500;

    private static final Pattern PART = Pattern.compile("segment/(\\d+)\\.(\\d+)\\.m4s");
    private static final Pattern SEGMENT = Pattern.compile("segment/(\\d+)\\.m4s");

    private final String baseUrl;
    private final String token;
    private final String camera;
    private final Listener listener;
    private volatile boolean running = true;
    private volatile int videoSamples;
    private final LinkedBlockingQueue<Fmp4.Sample> queue = new LinkedBlockingQueue<>();
    private Thread pacer;

    HaLiveStream(String baseUrl, String token, String camera, Listener listener) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.camera = camera;
        this.listener = listener;
    }

    int videoSamples() {
        return videoSamples;
    }

    void stop() {
        running = false;
        Thread p = pacer;
        if (p != null) p.interrupt();
    }

    @Override
    public void run() {
        try {
            String path = requestStream();
            String dir = baseUrl + path.substring(0, path.lastIndexOf('/') + 1);
            // The master playlist only answers once the stream is running.
            get(dir + "master_playlist.m3u8", START_TIMEOUT_MS);
            startPacer();
            play(dir);
        } catch (Exception e) {
            if (running) listener.onError(e);
        } finally {
            stop();
        }
    }

    // ---------------------------------------------------------------------------------------
    // WebSocket API: log in and ask for the stream URL

    private String requestStream() throws IOException {
        URL url = new URL(baseUrl);
        boolean tls = "https".equalsIgnoreCase(url.getProtocol());
        String host = url.getHost();
        int port = url.getPort() != -1 ? url.getPort() : (tls ? 443 : 80);
        Socket socket = tls ? SSLSocketFactory.getDefault().createSocket(host, port) : new Socket(host, port);
        try {
            socket.setSoTimeout(START_TIMEOUT_MS);
            if (tls) {
                SSLSocket ssl = (SSLSocket) socket;
                ssl.startHandshake();
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.getSession())) {
                    throw new IOException("Home Assistant certificate doesn't match " + host);
                }
            }
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String handshake = "GET " + url.getPath().replaceAll("/$", "") + "/api/websocket HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString(nonce) + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
            out.write(handshake.getBytes(StandardCharsets.UTF_8));
            out.flush();
            String status = readLine(in);
            if (!status.contains(" 101")) throw new IOException("Home Assistant WebSocket: " + status);
            while (!readLine(in).isEmpty()) {
                // skip response headers
            }
            while (true) {
                JSONObject msg = new JSONObject(readMessage(in));
                String type = msg.optString("type");
                if (type.equals("auth_required")) {
                    sendText(out, new JSONObject().put("type", "auth").put("access_token", token).toString());
                } else if (type.equals("auth_invalid")) {
                    throw new IOException("Home Assistant rejected the access token");
                } else if (type.equals("auth_ok")) {
                    sendText(out, new JSONObject().put("id", 1).put("type", "camera/stream")
                            .put("entity_id", camera).put("format", "hls").toString());
                } else if (type.equals("result") && msg.optInt("id") == 1) {
                    if (msg.optBoolean("success")) return msg.getJSONObject("result").getString("url");
                    JSONObject error = msg.optJSONObject("error");
                    throw new IOException("Home Assistant: "
                            + (error != null ? error.optString("message") : "couldn't start the stream"));
                }
            }
        } catch (JSONException e) {
            throw new IOException("Unexpected reply from Home Assistant", e);
        } finally {
            socket.close();
        }
    }

    private static void sendText(OutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 14);
        frame.write(0x81); // FIN + text
        if (payload.length < 126) {
            frame.write(0x80 | payload.length);
        } else {
            frame.write(0x80 | 126);
            frame.write(payload.length >> 8);
            frame.write(payload.length);
        }
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        frame.write(mask, 0, 4);
        for (int i = 0; i < payload.length; i++) frame.write(payload[i] ^ mask[i & 3]);
        out.write(frame.toByteArray());
        out.flush();
    }

    /** Reads one complete text message (server frames are never masked). */
    private static String readMessage(InputStream in) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        while (true) {
            int b0 = readByte(in);
            int b1 = readByte(in);
            long len = b1 & 0x7F;
            if (len == 126) {
                len = (readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
            }
            if (len > 1 << 20) throw new IOException("WebSocket message too large");
            byte[] payload = new byte[(int) len];
            int read = 0;
            while (read < len) {
                int n = in.read(payload, read, (int) len - read);
                if (n < 0) throw new IOException("Home Assistant closed the connection");
                read += n;
            }
            int opcode = b0 & 0x0F;
            if (opcode == 8) throw new IOException("Home Assistant closed the connection");
            if (opcode == 1 || opcode == 0) {
                message.write(payload, 0, payload.length);
                if ((b0 & 0x80) != 0) return new String(message.toByteArray(), StandardCharsets.UTF_8);
            }
            // ping/pong: ignored for this short exchange
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("Home Assistant closed the connection");
        return b;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = readByte(in)) != '\n') {
            if (b != '\r') line.write(b);
            if (line.size() > 8192) throw new IOException("Bad reply from Home Assistant");
        }
        return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    // ---------------------------------------------------------------------------------------
    // HLS

    /** One downloadable piece of the stream: a part (LL-HLS) or a whole segment. */
    private static final class Item {
        final long sequence;
        final int part; // -1 for a whole segment
        final String uri;
        final boolean independent;

        Item(long sequence, int part, String uri, boolean independent) {
            this.sequence = sequence;
            this.part = part;
            this.uri = uri;
            this.independent = independent;
        }

        boolean after(Item other) {
            return sequence > other.sequence || (sequence == other.sequence && part > other.part);
        }
    }

    private void play(String dir) throws IOException {
        Fmp4 mp4 = null;
        String discontinuity = null;
        Item last = null;
        while (running) {
            String playlist = new String(get(dir + "playlist.m3u8", TIMEOUT_MS), StandardCharsets.UTF_8);
            List<Item> parts = new ArrayList<>();
            List<Item> segments = new ArrayList<>();
            String disc = "0";
            for (String line : playlist.split("\n")) {
                line = line.trim();
                if (line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:")) {
                    disc = line.substring(line.indexOf(':') + 1);
                } else if (line.startsWith("#EXT-X-PART:")) {
                    Matcher m = PART.matcher(line);
                    if (m.find()) {
                        parts.add(new Item(Long.parseLong(m.group(1)), Integer.parseInt(m.group(2)),
                                "segment/" + m.group(1) + "." + m.group(2) + ".m4s",
                                line.contains("INDEPENDENT=YES")));
                    }
                } else if (!line.startsWith("#")) {
                    Matcher m = SEGMENT.matcher(line);
                    if (m.matches() || m.find()) {
                        segments.add(new Item(Long.parseLong(m.group(1)), -1, "segment/" + m.group(1) + ".m4s", true));
                    }
                }
            }
            // Low-latency playlists list parts; otherwise use whole segments.
            List<Item> items = parts.isEmpty() ? segments : parts;

            if (mp4 == null || !disc.equals(discontinuity)) {
                // First time, or the stream restarted (new init.mp4): start again at a key frame.
                mp4 = new Fmp4(get(dir + "init.mp4", TIMEOUT_MS));
                discontinuity = disc;
                last = null;
                announceTracks(mp4);
            }

            List<Item> todo = new ArrayList<>();
            if (last == null || items.isEmpty() || !contains(items, last)) {
                // Start (or catch up) at the newest piece that begins with a key frame.
                for (int i = items.size() - 1; i >= 0; i--) {
                    if (items.get(i).independent) {
                        todo.addAll(items.subList(i, items.size()));
                        break;
                    }
                }
            } else {
                for (Item item : items) if (item.after(last)) todo.add(item);
            }

            for (Item item : todo) {
                if (!running) return;
                List<Fmp4.Sample> samples = mp4.samples(get(dir + item.uri, TIMEOUT_MS));
                Collections.sort(samples, new Comparator<Fmp4.Sample>() {
                    @Override
                    public int compare(Fmp4.Sample a, Fmp4.Sample b) {
                        return Long.compare(a.decodeUs, b.decodeUs); // decode order (stable sort)
                    }
                });
                queue.addAll(samples);
                last = item;
            }
            if (todo.isEmpty()) sleep(POLL_MS);
        }
    }

    private static boolean contains(List<Item> items, Item wanted) {
        for (Item item : items) {
            if (item.sequence == wanted.sequence && item.part == wanted.part) return true;
        }
        return false;
    }

    private void announceTracks(Fmp4 mp4) {
        Fmp4.Track video = mp4.video();
        listener.onVideoTrack(video.mime, video.parameterSets.toArray(new byte[0][]));
        Fmp4.Track audio = mp4.audio();
        if (audio != null) {
            listener.onAudioTrack(new RtspClient.AudioInfo(
                    RtspClient.AudioInfo.AAC, audio.sampleRate, audio.channels, audio.aacConfig));
        }
    }

    /** Hands out samples at the pace of their timestamps (the parts arrive in bursts). */
    private void startPacer() {
        pacer = new Thread(new Runnable() {
            @Override
            public void run() {
                long wallStart = 0;
                long mediaStart = 0;
                boolean started = false;
                try {
                    while (running) {
                        Fmp4.Sample s = queue.take();
                        long now = System.currentTimeMillis();
                        if (!started) {
                            started = true;
                            wallStart = now;
                            mediaStart = s.decodeUs;
                        }
                        long due = wallStart + (s.decodeUs - mediaStart) / 1000;
                        if (now - due > MAX_LATE_MS) {
                            wallStart = now; // fell behind: continue from here in real time
                            mediaStart = s.decodeUs;
                            due = now;
                        }
                        if (due > now) Thread.sleep(due - now);
                        if (!running) return;
                        if (s.track.video) {
                            videoSamples++;
                            listener.onVideoSample(s.data, s.timeUs, s.keyFrame);
                        } else {
                            listener.onAudioSample(s.data);
                        }
                    }
                } catch (InterruptedException ignored) {
                    // stopped
                } catch (Exception e) {
                    if (running) listener.onError(e);
                    HaLiveStream.this.stop();
                }
            }
        }, "ha-pacer");
        pacer.start();
    }

    private byte[] get(String url, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(timeoutMs);
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException(code == 404
                    ? "Home Assistant stream stopped (HTTP 404)" : "Home Assistant stream: HTTP " + code);
        }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
