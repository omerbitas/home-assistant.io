package com.omerbitas.rtspwatch;

import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;

/** A still frame from the camera, as little-endian RGB 565 pixels (the tile's inline image format). */
final class Snapshot {

    /** Frames decoded after the key frame; a few P-frames make decoders more willing to output. */
    private static final int FRAMES_TO_DECODE = 4;

    final byte[] rgb565;
    final int width;
    final int height;
    final long timeMillis;

    private Snapshot(byte[] rgb565, int width, int height, long timeMillis) {
        this.rgb565 = rgb565;
        this.width = width;
        this.height = height;
        this.timeMillis = timeMillis;
    }

    // ---------------------------------------------------------------------------------------
    // Grabbing

    /** Connects to the camera, decodes one frame and scales it to at most maxWidth pixels wide. */
    static Snapshot grab(String url, SocketFactory network, int maxWidth, long timeoutMs) throws Exception {
        final List<byte[]> frames = new ArrayList<>();
        final String[] mime = new String[1];
        final byte[][][] parameterSets = new byte[1][][];
        final Exception[] error = new Exception[1];
        final CountDownLatch done = new CountDownLatch(1);
        final RtspClient[] client = new RtspClient[1];

        client[0] = new RtspClient(url, new RtspClient.Listener() {
            @Override
            public void onStatus(String status) {
            }

            @Override
            public void onTrack(String m, byte[][] sets) {
                mime[0] = m;
                parameterSets[0] = sets;
            }

            @Override
            public void onFrame(byte[] data, int length, long timestampUs, boolean keyFrame) {
                synchronized (frames) {
                    if (frames.isEmpty() && !keyFrame) return;
                    byte[] copy = new byte[length];
                    System.arraycopy(data, 0, copy, 0, length);
                    frames.add(copy);
                    if (frames.size() >= FRAMES_TO_DECODE) {
                        client[0].stop();
                        done.countDown();
                    }
                }
            }

            @Override
            public void onAudioTrack(RtspClient.AudioInfo info) {
            }

            @Override
            public void onAudioFrame(byte[] data, int offset, int length) {
            }

            @Override
            public void onError(Exception e) {
                error[0] = e;
                done.countDown();
            }
        }, false, network);

        Thread thread = new Thread(client[0], "snapshot");
        thread.start();
        boolean finished = done.await(timeoutMs, TimeUnit.MILLISECONDS);
        client[0].stop();
        thread.join(1000);

        List<byte[]> got;
        synchronized (frames) {
            got = new ArrayList<>(frames);
        }
        if (got.isEmpty()) {
            if (error[0] != null) throw error[0];
            throw new IOException(finished ? "No video" : "Timed out");
        }
        return decode(mime[0], parameterSets[0], got, maxWidth);
    }

    private static Snapshot decode(String mime, byte[][] sdpSets, List<byte[]> frames, int maxWidth)
            throws IOException {
        Exception last = null;
        // Hardware decoder first, then the platform's software decoder as a fallback.
        String software = RtspClient.MIME_H265.equals(mime) ? "c2.android.hevc.decoder" : "c2.android.avc.decoder";
        for (String name : new String[]{null, software}) {
            MediaCodec codec = null;
            try {
                codec = name == null ? MediaCodec.createDecoderByType(mime) : MediaCodec.createByCodecName(name);
                Snapshot s = decodeWith(codec, mime, sdpSets, frames, maxWidth);
                if (s != null) return s;
            } catch (Exception e) {
                last = e;
            } finally {
                if (codec != null) codec.release();
            }
        }
        throw new IOException("Could not decode snapshot" + (last != null ? ": " + last.getMessage() : ""));
    }

    private static Snapshot decodeWith(MediaCodec codec, String mime, byte[][] sdpSets, List<byte[]> frames,
            int maxWidth) {
        MediaFormat format = MediaFormat.createVideoFormat(mime, 1920, 1080);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024);
        if (sdpSets != null && sdpSets.length > 0) {
            // The key frame normally carries its own parameter sets; SDP ones are a fallback.
            // H.264 wants SPS in csd-0 and PPS in csd-1; H.265 wants VPS+SPS+PPS in csd-0.
            boolean avc = RtspClient.MIME_H264.equals(mime);
            ByteArrayOutputStream csd0 = new ByteArrayOutputStream();
            ByteArrayOutputStream csd1 = new ByteArrayOutputStream();
            for (byte[] set : sdpSets) {
                if (set.length == 0) continue;
                ByteArrayOutputStream target = avc && (set[0] & 0x1F) == 8 ? csd1 : csd0;
                target.write(0);
                target.write(0);
                target.write(0);
                target.write(1);
                target.write(set, 0, set.length);
            }
            if (csd0.size() > 0) format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0.toByteArray()));
            if (csd1.size() > 0) format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1.toByteArray()));
        }
        codec.configure(format, null, null, 0);
        codec.start();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        Snapshot result = null;
        int next = 0;
        boolean eosQueued = false;
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (!eosQueued) {
                int in = codec.dequeueInputBuffer(10000);
                if (in >= 0) {
                    ByteBuffer buffer = codec.getInputBuffer(in);
                    if (next < frames.size() && buffer != null && buffer.capacity() >= frames.get(next).length) {
                        byte[] frame = frames.get(next);
                        buffer.clear();
                        buffer.put(frame);
                        codec.queueInputBuffer(in, 0, frame.length, next * 66000L,
                                next == 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                        next++;
                    } else {
                        codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eosQueued = true;
                    }
                }
            }
            int out = codec.dequeueOutputBuffer(info, 10000);
            if (out >= 0) {
                if (info.size > 0) {
                    Image image = codec.getOutputImage(out);
                    if (image != null) {
                        result = fromImage(image, maxWidth); // keep the latest decoded frame
                        image.close();
                    }
                }
                codec.releaseOutputBuffer(out, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
        codec.stop();
        return result;
    }

    /** Scales a YUV 4:2:0 image down to RGB 565 (nearest neighbour is plenty for a tile). */
    private static Snapshot fromImage(Image image, int maxWidth) {
        Rect crop = image.getCropRect();
        int cw = crop.width();
        int ch = crop.height();
        int w = Math.min(maxWidth, cw);
        int h = Math.max(1, Math.round((float) ch * w / cw));
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRow = planes[0].getRowStride();
        int yPix = planes[0].getPixelStride();
        int uvRow = planes[1].getRowStride();
        int uvPix = planes[1].getPixelStride();

        byte[] out = new byte[w * h * 2];
        int o = 0;
        for (int y = 0; y < h; y++) {
            int sy = crop.top + y * ch / h;
            for (int x = 0; x < w; x++) {
                int sx = crop.left + x * cw / w;
                int yy = (yBuf.get(sy * yRow + sx * yPix) & 0xFF) - 16;
                int uvIndex = (sy / 2) * uvRow + (sx / 2) * uvPix;
                int u = (uBuf.get(uvIndex) & 0xFF) - 128;
                int v = (vBuf.get(uvIndex) & 0xFF) - 128;
                int c = 298 * Math.max(0, yy);
                int r = clamp((c + 409 * v + 128) >> 8);
                int g = clamp((c - 100 * u - 208 * v + 128) >> 8);
                int b = clamp((c + 516 * u + 128) >> 8);
                int pixel = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
                out[o++] = (byte) pixel;
                out[o++] = (byte) (pixel >> 8);
            }
        }
        return new Snapshot(out, w, h, System.currentTimeMillis());
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : value > 255 ? 255 : value;
    }

    // ---------------------------------------------------------------------------------------
    // Persistence, so the tile has a picture right after the watch restarts the app.

    void save(File file) {
        File tmp = new File(file.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(tmp))) {
            out.writeInt(width);
            out.writeInt(height);
            out.writeLong(timeMillis);
            out.write(rgb565);
        } catch (IOException e) {
            return;
        }
        tmp.renameTo(file);
    }

    static Snapshot load(File file) {
        if (!file.exists()) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            int w = in.readInt();
            int h = in.readInt();
            long time = in.readLong();
            if (w <= 0 || h <= 0 || w > 2048 || h > 2048) return null;
            byte[] pixels = new byte[w * h * 2];
            in.readFully(pixels);
            return new Snapshot(pixels, w, h, time);
        } catch (IOException e) {
            return null;
        }
    }
}
