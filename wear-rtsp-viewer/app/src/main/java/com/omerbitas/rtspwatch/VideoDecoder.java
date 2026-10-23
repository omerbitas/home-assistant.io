package com.omerbitas.rtspwatch;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Hardware-decodes Annex-B H.264/H.265 access units straight onto a {@link Surface}, rendering
 * each frame as soon as it is decoded for the lowest possible latency.
 */
final class VideoDecoder {

    interface Listener {
        void onVideoSize(int width, int height);

        void onFirstFrameRendered();
    }

    private static final long INPUT_TIMEOUT_US = 20000;

    private final Surface surface;
    private final Listener listener;
    private String mime;
    private byte[] vps;
    private byte[] sps;
    private byte[] pps;
    private MediaCodec codec;
    private Thread outputThread;
    private volatile boolean running;
    private volatile int queuedFrames;
    private volatile int renderedFrames;
    private volatile String codecName;

    int queuedFrames() {
        return queuedFrames;
    }

    int renderedFrames() {
        return renderedFrames;
    }

    /** Name of the decoder in use, or null before the first key frame. */
    String codecName() {
        return codecName;
    }

    VideoDecoder(Surface surface, Listener listener) {
        this.surface = surface;
        this.listener = listener;
    }

    /** Sets the codec and any parameter sets announced in the SDP. */
    void setTrack(String mime, byte[][] parameterSets) {
        this.mime = mime;
        for (byte[] nal : parameterSets) rememberParameterSet(nal, 0, nal.length);
    }

    /** Must be called from a single thread (the RTSP thread). */
    void feed(byte[] data, int length, long timestampUs, boolean keyFrame) throws IOException {
        scanParameterSets(data, length);
        if (codec == null) {
            if (!keyFrame) return; // decoding has to start at a key frame
            start();
        }
        int index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US);
        if (index < 0) return; // decoder is behind; drop the frame rather than add latency
        ByteBuffer buffer = codec.getInputBuffer(index);
        if (buffer == null || buffer.capacity() < length) {
            codec.queueInputBuffer(index, 0, 0, timestampUs, 0);
            return;
        }
        buffer.clear();
        buffer.put(data, 0, length);
        codec.queueInputBuffer(index, 0, length, timestampUs, keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        queuedFrames++;
    }

    private void start() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(mime, 1920, 1080);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        if (RtspClient.MIME_H264.equals(mime)) {
            if (sps != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)));
            if (pps != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps)));
        } else if (vps != null && sps != null && pps != null) {
            ByteArrayOutputStream csd = new ByteArrayOutputStream();
            byte[][] sets = {vps, sps, pps};
            for (byte[] set : sets) {
                byte[] b = withStartCode(set);
                csd.write(b, 0, b.length);
            }
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd.toByteArray()));
        }

        codec = MediaCodec.createDecoderByType(mime);
        codecName = codec.getName();
        codec.configure(format, surface, null, 0);
        codec.start();
        running = true;
        final MediaCodec c = codec;
        outputThread = new Thread(new Runnable() {
            @Override
            public void run() {
                drain(c);
            }
        }, "decoder-output");
        outputThread.start();
    }

    private void drain(MediaCodec c) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (running) {
                int index = c.dequeueOutputBuffer(info, 10000);
                if (index >= 0) {
                    c.releaseOutputBuffer(index, true);
                    if (renderedFrames++ == 0) listener.onFirstFrameRendered();
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = c.getOutputFormat();
                    int width = f.getInteger(MediaFormat.KEY_WIDTH);
                    int height = f.getInteger(MediaFormat.KEY_HEIGHT);
                    if (f.containsKey("crop-right") && f.containsKey("crop-left")) {
                        width = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
                    }
                    if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) {
                        height = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
                    }
                    listener.onVideoSize(width, height);
                }
            }
        } catch (IllegalStateException ignored) {
            // Codec released while draining.
        }
    }

    void release() {
        running = false;
        if (outputThread != null) {
            try {
                outputThread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            outputThread = null;
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (IllegalStateException ignored) {
            }
            codec.release();
            codec = null;
        }
    }

    // ---------------------------------------------------------------------------------------

    private void scanParameterSets(byte[] data, int length) {
        // Access units are built by RtspClient with 4-byte start codes.
        int i = 0;
        while (i + 4 < length) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                int start = i + 4;
                int next = start;
                while (next + 3 < length
                        && !(data[next] == 0 && data[next + 1] == 0 && data[next + 2] == 0 && data[next + 3] == 1)) {
                    next++;
                }
                if (next + 3 >= length) next = length;
                rememberParameterSet(data, start, next - start);
                i = next;
            } else {
                i++;
            }
        }
    }

    private void rememberParameterSet(byte[] data, int offset, int length) {
        if (length <= 0) return;
        if (RtspClient.MIME_H265.equals(mime)) {
            int type = (data[offset] >> 1) & 0x3F;
            if (type == 32) vps = copy(data, offset, length);
            else if (type == 33) sps = copy(data, offset, length);
            else if (type == 34) pps = copy(data, offset, length);
        } else {
            int type = data[offset] & 0x1F;
            if (type == 7) sps = copy(data, offset, length);
            else if (type == 8) pps = copy(data, offset, length);
        }
    }

    private static byte[] copy(byte[] data, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(data, offset, out, 0, length);
        return out;
    }

    private static byte[] withStartCode(byte[] nal) {
        byte[] out = new byte[nal.length + 4];
        out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }
}
