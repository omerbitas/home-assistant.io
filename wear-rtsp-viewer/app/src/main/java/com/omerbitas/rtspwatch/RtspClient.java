package com.omerbitas.rtspwatch;

import android.net.Uri;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal RTSP client: pulls the first H.264 or H.265 video track over RTP interleaved on the
 * RTSP TCP connection (works through NAT and doesn't need UDP ports), supports Basic and
 * Digest authentication, and hands complete access units to a {@link Listener}.
 */
final class RtspClient implements Runnable {

    interface Listener {
        void onStatus(String status);

        /** Called once the codec of the video track is known. */
        void onTrack(String mime, byte[][] parameterSets);

        /** A complete access unit in Annex-B format (start-code prefixed NAL units). */
        void onFrame(byte[] data, int length, long timestampUs, boolean keyFrame);

        void onError(Exception e);
    }

    static final String MIME_H264 = "video/avc";
    static final String MIME_H265 = "video/hevc";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int KEEPALIVE_MS = 20000;
    private static final byte[] START_CODE = {0, 0, 0, 1};

    private final Uri uri;
    private final String requestUrl;
    private final String username;
    private final String password;
    private final Listener listener;

    private volatile boolean running = true;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int cseq = 1;
    private String session;
    private String authHeader; // "Basic" or parsed Digest challenge
    private Map<String, String> digest;
    private boolean useGetParameter = false;
    private int nonceCount;

    // Depacketizer state.
    private String mime;
    private final ByteArrayOutputStream frame = new ByteArrayOutputStream(256 * 1024);
    private final ByteArrayOutputStream fragment = new ByteArrayOutputStream(256 * 1024);
    private boolean fragmentValid;
    private boolean frameKey;
    private boolean frameValid = true;
    private long frameTimestamp = -1;
    private int lastSeq = -1;
    private long firstRtpTs = -1;
    private long rtpTsBase; // unwrapped 32-bit timestamp
    private long lastRawTs = -1;

    RtspClient(String url, Listener listener) {
        this.uri = Uri.parse(url);
        this.listener = listener;
        String userInfo = uri.getEncodedUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            username = decode(colon >= 0 ? userInfo.substring(0, colon) : userInfo);
            password = colon >= 0 ? decode(userInfo.substring(colon + 1)) : "";
            requestUrl = url.replaceFirst("(?i)(rtsps?://)[^@/]*@", "$1");
        } else {
            username = null;
            password = null;
            requestUrl = url;
        }
    }

    void stop() {
        running = false;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void run() {
        try {
            connect();
            String sdp = request("DESCRIBE", requestUrl, "Accept: application/sdp\r\n").body;
            String baseUrl = lastContentBase != null ? lastContentBase : requestUrl;
            String control = parseVideoTrack(sdp, baseUrl);

            Response setup = request("SETUP", control,
                    "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
            String sessionHeader = setup.headers.get("session");
            if (sessionHeader == null) throw new IOException("Camera did not return a session");
            session = sessionHeader.split(";")[0].trim();

            request("PLAY", baseUrl, "Range: npt=0.000-\r\n");
            listener.onStatus(null);
            readLoop();
        } catch (Exception e) {
            if (running) listener.onError(e);
        } finally {
            stop();
        }
    }

    private void connect() throws IOException {
        boolean tls = "rtsps".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() != -1 ? uri.getPort() : (tls ? 322 : 554);
        Socket s = tls ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        socket = s;
        s.setTcpNoDelay(true);
        s.setReceiveBufferSize(1024 * 1024);
        s.connect(new InetSocketAddress(uri.getHost(), port), CONNECT_TIMEOUT_MS);
        s.setSoTimeout(READ_TIMEOUT_MS);
        in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));
        out = s.getOutputStream();
    }

    // ---------------------------------------------------------------------------------------
    // RTSP requests

    private static final class Response {
        int status;
        final Map<String, String> headers = new HashMap<>();
        String body = "";
    }

    private String lastContentBase;

    private Response request(String method, String url, String extraHeaders) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            sendRequest(method, url, extraHeaders);
            Response r = readResponse();
            if (r.status == 401 && attempt == 0 && username != null) {
                String challenge = r.headers.get("www-authenticate");
                if (challenge == null) break;
                parseChallenge(challenge);
                continue;
            }
            if (r.status == 401) {
                throw new IOException(username == null
                        ? "Camera needs a login: use rtsp://user:password@host/..."
                        : "Authentication failed (check username/password)");
            }
            if (r.status != 200) throw new IOException(method + " failed: RTSP " + r.status);
            if ("DESCRIBE".equals(method)) {
                String base = r.headers.get("content-base");
                if (base == null) base = r.headers.get("content-location");
                lastContentBase = base;
            }
            if ("OPTIONS".equals(method)) {
                String pub = r.headers.get("public");
                useGetParameter = pub != null && pub.toUpperCase(Locale.US).contains("GET_PARAMETER");
            }
            return r;
        }
        throw new IOException("Camera rejected the login");
    }

    private void sendRequest(String method, String url, String extraHeaders) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(url).append(" RTSP/1.0\r\n");
        sb.append("CSeq: ").append(cseq++).append("\r\n");
        sb.append("User-Agent: RtspWatch/1.0\r\n");
        if (session != null) sb.append("Session: ").append(session).append("\r\n");
        String auth = authorization(method, url);
        if (auth != null) sb.append("Authorization: ").append(auth).append("\r\n");
        sb.append(extraHeaders).append("\r\n");
        synchronized (this) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private Response readResponse() throws IOException {
        Response r = new Response();
        String statusLine = readLine();
        while (statusLine.isEmpty()) statusLine = readLine();
        String[] parts = statusLine.split(" ");
        if (parts.length < 2 || !parts[0].startsWith("RTSP/")) {
            throw new IOException("Unexpected response: " + statusLine);
        }
        r.status = Integer.parseInt(parts[1]);
        readHeadersAndBody(r);
        return r;
    }

    private void readHeadersAndBody(Response r) throws IOException {
        String line;
        while (!(line = readLine()).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            // Prefer Digest when a camera offers several WWW-Authenticate headers.
            if (key.equals("www-authenticate") && r.headers.containsKey(key)
                    && !value.toLowerCase(Locale.US).startsWith("digest")) {
                continue;
            }
            r.headers.put(key, value);
        }
        String length = r.headers.get("content-length");
        if (length != null) {
            byte[] body = new byte[Integer.parseInt(length.trim())];
            in.readFully(body);
            r.body = new String(body, StandardCharsets.UTF_8);
        }
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') line.write(b);
            if (line.size() > 8192) throw new IOException("Header line too long");
        }
        if (b == -1 && line.size() == 0) throw new EOFException("Connection closed by camera");
        return new String(line.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------------------------
    // Authentication

    private void parseChallenge(String challenge) {
        if (challenge.toLowerCase(Locale.US).startsWith("digest")) {
            digest = new HashMap<>();
            Matcher m = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]*))").matcher(challenge);
            while (m.find()) {
                digest.put(m.group(1).toLowerCase(Locale.US), m.group(2) != null ? m.group(2) : m.group(3));
            }
            authHeader = "Digest";
            nonceCount = 0;
        } else {
            authHeader = "Basic";
        }
    }

    private String authorization(String method, String url) {
        if (authHeader == null || username == null) return null;
        if (authHeader.equals("Basic")) {
            String token = Base64.encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            return "Basic " + token;
        }
        String realm = digest.get("realm");
        String nonce = digest.get("nonce");
        String qop = digest.get("qop");
        String ha1 = md5(username + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + url);
        StringBuilder sb = new StringBuilder("Digest ");
        sb.append("username=\"").append(username).append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce).append("\", uri=\"").append(url).append('"');
        if (qop != null && qop.contains("auth")) {
            String nc = String.format(Locale.US, "%08x", ++nonceCount);
            String cnonce = Long.toHexString(System.nanoTime());
            String response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
            sb.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce)
                    .append("\", response=\"").append(response).append('"');
        } else {
            sb.append(", response=\"").append(md5(ha1 + ":" + nonce + ":" + ha2)).append('"');
        }
        String opaque = digest.get("opaque");
        if (opaque != null) sb.append(", opaque=\"").append(opaque).append('"');
        return sb.toString();
    }

    private static String md5(String s) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format(Locale.US, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ---------------------------------------------------------------------------------------
    // SDP

    /** Finds the first H.264/H.265 video track, reports it, and returns its control URL. */
    private String parseVideoTrack(String sdp, String baseUrl) throws IOException {
        String[] lines = sdp.split("\r?\n");
        boolean inVideo = false;
        String control = null;
        String fmtp = null;
        for (String line : lines) {
            if (line.startsWith("m=")) {
                if (inVideo && mime != null) break; // finished the video section we want
                inVideo = line.startsWith("m=video");
                continue;
            }
            if (!inVideo) continue;
            if (line.startsWith("a=rtpmap:")) {
                String upper = line.toUpperCase(Locale.US);
                if (upper.contains("H264")) mime = MIME_H264;
                else if (upper.contains("H265") || upper.contains("HEVC")) mime = MIME_H265;
            } else if (line.startsWith("a=control:")) {
                control = line.substring("a=control:".length()).trim();
            } else if (line.startsWith("a=fmtp:")) {
                fmtp = line;
            }
        }
        if (mime == null) {
            throw new IOException("No H.264/H.265 video in stream (try the camera's H.264 sub-stream)");
        }
        listener.onTrack(mime, parameterSetsFromFmtp(fmtp));

        if (control == null || control.equals("*")) return baseUrl;
        if (control.toLowerCase(Locale.US).startsWith("rtsp")) return control;
        return baseUrl.endsWith("/") ? baseUrl + control : baseUrl + "/" + control;
    }

    private byte[][] parameterSetsFromFmtp(String fmtp) {
        if (fmtp == null) return new byte[0][];
        Map<String, String> params = new HashMap<>();
        String body = fmtp.substring(fmtp.indexOf(' ') + 1);
        for (String part : body.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) params.put(part.substring(0, eq).trim().toLowerCase(Locale.US), part.substring(eq + 1).trim());
        }
        StringBuilder all = new StringBuilder();
        if (MIME_H264.equals(mime)) {
            if (params.containsKey("sprop-parameter-sets")) all.append(params.get("sprop-parameter-sets"));
        } else {
            for (String key : new String[]{"sprop-vps", "sprop-sps", "sprop-pps"}) {
                if (params.containsKey(key)) all.append(params.get(key)).append(',');
            }
        }
        java.util.List<byte[]> sets = new java.util.ArrayList<>();
        for (String b64 : all.toString().split(",")) {
            if (b64.isEmpty()) continue;
            try {
                sets.add(Base64.decode(b64, Base64.DEFAULT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return sets.toArray(new byte[0][]);
    }

    // ---------------------------------------------------------------------------------------
    // Streaming

    private void readLoop() throws IOException {
        long lastKeepAlive = System.currentTimeMillis();
        byte[] packet = new byte[65536];
        while (running) {
            if (System.currentTimeMillis() - lastKeepAlive > KEEPALIVE_MS) {
                sendRequest(useGetParameter ? "GET_PARAMETER" : "OPTIONS", requestUrl, "");
                lastKeepAlive = System.currentTimeMillis();
            }
            int b = in.read();
            if (b == -1) throw new EOFException("Stream ended");
            if (b == '$') {
                int channel = in.readUnsignedByte();
                int length = in.readUnsignedShort();
                in.readFully(packet, 0, length);
                if (channel == 0) handleRtp(packet, length);
            } else if (b == 'R') {
                // Interleaved RTSP response, e.g. to a keep-alive. Consume and ignore.
                readLine();
                readHeadersAndBody(new Response());
            }
        }
    }

    private void handleRtp(byte[] p, int length) {
        if (length < 12 || (p[0] & 0xC0) != 0x80) return;
        boolean padding = (p[0] & 0x20) != 0;
        boolean extension = (p[0] & 0x10) != 0;
        int csrcCount = p[0] & 0x0F;
        boolean marker = (p[1] & 0x80) != 0;
        int seq = ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
        long ts = ((long) (p[4] & 0xFF) << 24) | ((p[5] & 0xFF) << 16) | ((p[6] & 0xFF) << 8) | (p[7] & 0xFF);

        int offset = 12 + 4 * csrcCount;
        if (extension) {
            if (offset + 4 > length) return;
            int extLen = ((p[offset + 2] & 0xFF) << 8) | (p[offset + 3] & 0xFF);
            offset += 4 + 4 * extLen;
        }
        int end = length;
        if (padding) end -= p[length - 1] & 0xFF;
        if (offset >= end) return;

        if (lastSeq != -1 && seq != ((lastSeq + 1) & 0xFFFF)) {
            // Packet loss: drop the partially assembled frame and wait for the next one.
            frameValid = false;
            fragmentValid = false;
        }
        lastSeq = seq;

        if (frameTimestamp != -1 && ts != frameTimestamp) emitFrame();
        frameTimestamp = ts;

        if (MIME_H264.equals(mime)) depacketizeH264(p, offset, end);
        else depacketizeH265(p, offset, end);

        if (marker) emitFrame();
    }

    private void depacketizeH264(byte[] p, int offset, int end) {
        int type = p[offset] & 0x1F;
        if (type >= 1 && type <= 23) {
            appendNal(p, offset, end - offset, type == 5);
        } else if (type == 24) { // STAP-A
            int i = offset + 1;
            while (i + 2 <= end) {
                int size = ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
                i += 2;
                if (size == 0 || i + size > end) break;
                appendNal(p, i, size, (p[i] & 0x1F) == 5);
                i += size;
            }
        } else if (type == 28 && end - offset > 2) { // FU-A
            int header = p[offset + 1] & 0xFF;
            boolean start = (header & 0x80) != 0;
            boolean last = (header & 0x40) != 0;
            int nalType = header & 0x1F;
            if (start) {
                fragment.reset();
                fragment.write((p[offset] & 0xE0) | nalType);
                fragmentValid = true;
            }
            if (!fragmentValid) return;
            fragment.write(p, offset + 2, end - offset - 2);
            if (last) {
                byte[] nal = fragment.toByteArray();
                appendNal(nal, 0, nal.length, nalType == 5);
                fragmentValid = false;
            }
        }
    }

    private void depacketizeH265(byte[] p, int offset, int end) {
        if (end - offset < 3) return;
        int type = (p[offset] >> 1) & 0x3F;
        if (type == 48) { // Aggregation packet
            int i = offset + 2;
            while (i + 2 <= end) {
                int size = ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
                i += 2;
                if (size == 0 || i + size > end) break;
                appendNal(p, i, size, isH265Key((p[i] >> 1) & 0x3F));
                i += size;
            }
        } else if (type == 49) { // Fragmentation unit
            int header = p[offset + 2] & 0xFF;
            boolean start = (header & 0x80) != 0;
            boolean last = (header & 0x40) != 0;
            int nalType = header & 0x3F;
            if (start) {
                fragment.reset();
                fragment.write((p[offset] & 0x81) | (nalType << 1));
                fragment.write(p[offset + 1]);
                fragmentValid = true;
            }
            if (!fragmentValid) return;
            fragment.write(p, offset + 3, end - offset - 3);
            if (last) {
                byte[] nal = fragment.toByteArray();
                appendNal(nal, 0, nal.length, isH265Key(nalType));
                fragmentValid = false;
            }
        } else {
            appendNal(p, offset, end - offset, isH265Key(type));
        }
    }

    private static boolean isH265Key(int type) {
        return type >= 16 && type <= 21;
    }

    private void appendNal(byte[] data, int offset, int length, boolean key) {
        frame.write(START_CODE, 0, START_CODE.length);
        frame.write(data, offset, length);
        frameKey |= key;
    }

    private void emitFrame() {
        if (frame.size() > 0 && frameValid) {
            listener.onFrame(frame.toByteArray(), frame.size(), toUs(frameTimestamp), frameKey);
        }
        frame.reset();
        frameKey = false;
        frameValid = true;
        frameTimestamp = -1;
    }

    /** Converts a 90 kHz RTP timestamp to microseconds since the first frame, handling wrap. */
    private long toUs(long rawTs) {
        if (firstRtpTs == -1) {
            firstRtpTs = rawTs;
            lastRawTs = rawTs;
        }
        long delta = (rawTs - lastRawTs) & 0xFFFFFFFFL;
        if (delta > 0x7FFFFFFFL) delta -= 0x100000000L; // reordered / slightly older frame
        rtpTsBase += delta;
        lastRawTs = rawTs;
        return Math.max(0, rtpTsBase) * 1000000L / 90000L;
    }
}
