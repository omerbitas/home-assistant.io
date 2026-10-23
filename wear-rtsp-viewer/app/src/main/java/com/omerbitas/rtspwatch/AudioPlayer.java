package com.omerbitas.rtspwatch;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Plays the camera's audio: AAC through the hardware decoder, G.711 decoded in software. Audio is
 * written without blocking, so a late network burst drops sound instead of building up delay.
 */
final class AudioPlayer {

    /** Wait for this much audio before starting playback, to ride out network jitter. */
    private static final int PREBUFFER_MS = 200;
    /** Upper bound on how far behind live the audio can fall. */
    private static final int BUFFER_MS = 500;

    private final RtspClient.AudioInfo info;
    private MediaCodec codec;
    private Thread outputThread;
    private volatile boolean running = true;
    private volatile boolean muted;

    private AudioTrack track;
    private int trackRate;
    private int trackChannels;
    private int bufferedBytes;
    private volatile int framesIn;
    private volatile int droppedBytes;

    AudioPlayer(RtspClient.AudioInfo info, boolean muted) throws IOException {
        this.info = info;
        this.muted = muted;
        if (info.aacConfig != null) {
            MediaFormat format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, info.sampleRate, info.channels);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(info.aacConfig));
            format.setInteger(MediaFormat.KEY_IS_ADTS, 0);
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(format, null, null, 0);
            codec.start();
            final MediaCodec c = codec;
            outputThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    drain(c);
                }
            }, "audio-output");
            outputThread.start();
        }
    }

    String describe() {
        return info.codec + " " + info.sampleRate + " Hz" + (muted ? ", muted" : "")
                + "\nAudio frames " + framesIn + (droppedBytes > 0 ? " (late " + droppedBytes / 1024 + " KB)" : "");
    }

    void setMuted(boolean muted) {
        this.muted = muted;
        synchronized (this) {
            if (track != null) track.setVolume(muted ? 0f : 1f);
        }
    }

    /** Called from the RTSP thread with one encoded frame. */
    void feed(byte[] data, int offset, int length) {
        if (!running) return;
        framesIn++;
        if (codec == null) {
            playPcm(decodeG711(data, offset, length), info.sampleRate, info.channels);
            return;
        }
        try {
            int index = codec.dequeueInputBuffer(10000);
            if (index < 0) return;
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null || buffer.capacity() < length) {
                codec.queueInputBuffer(index, 0, 0, 0, 0);
                return;
            }
            buffer.clear();
            buffer.put(data, offset, length);
            codec.queueInputBuffer(index, 0, length, System.nanoTime() / 1000, 0);
        } catch (IllegalStateException e) {
            // Released or broken decoder: carry on without sound rather than stop the video.
        }
    }

    private void drain(MediaCodec c) {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int rate = info.sampleRate;
        int channels = info.channels;
        byte[] pcm = new byte[0];
        try {
            while (running) {
                int index = c.dequeueOutputBuffer(bufferInfo, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = c.getOutputFormat();
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (index >= 0) {
                    ByteBuffer out = c.getOutputBuffer(index);
                    if (out != null && bufferInfo.size > 0) {
                        if (pcm.length < bufferInfo.size) pcm = new byte[bufferInfo.size];
                        out.position(bufferInfo.offset);
                        out.get(pcm, 0, bufferInfo.size);
                        playPcm(pcm, rate, channels, bufferInfo.size);
                    }
                    c.releaseOutputBuffer(index, false);
                }
            }
        } catch (IllegalStateException ignored) {
            // Codec released while draining.
        }
    }

    private void playPcm(byte[] pcm, int rate, int channels) {
        playPcm(pcm, rate, channels, pcm.length);
    }

    private synchronized void playPcm(byte[] pcm, int rate, int channels, int length) {
        if (!running) return;
        if (track == null || trackRate != rate || trackChannels != channels) {
            releaseTrack();
            int bytesPerMs = rate * channels * 2 / 1000;
            int channelMask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int minSize = AudioTrack.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(channelMask)
                            .build())
                    .setBufferSizeInBytes(Math.max(minSize, bytesPerMs * BUFFER_MS))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.setVolume(muted ? 0f : 1f);
            trackRate = rate;
            trackChannels = channels;
            bufferedBytes = 0;
        }
        int written = track.write(pcm, 0, length, AudioTrack.WRITE_NON_BLOCKING);
        if (written < length) droppedBytes += length - Math.max(0, written);
        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            bufferedBytes += Math.max(0, written);
            if (bufferedBytes >= rate * channels * 2 * PREBUFFER_MS / 1000) track.play();
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
        synchronized (this) {
            releaseTrack();
        }
    }

    private void releaseTrack() {
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
            }
            track.release();
            track = null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // G.711

    private byte[] decodeG711(byte[] data, int offset, int length) {
        boolean ulaw = RtspClient.AudioInfo.PCMU.equals(info.codec);
        byte[] pcm = new byte[length * 2];
        for (int i = 0; i < length; i++) {
            int sample = ulaw ? ulaw(data[offset + i]) : alaw(data[offset + i]);
            pcm[2 * i] = (byte) sample;
            pcm[2 * i + 1] = (byte) (sample >> 8);
        }
        return pcm;
    }

    private static int ulaw(byte b) {
        int u = ~b & 0xFF;
        int t = ((u & 0x0F) << 3) + 0x84;
        t <<= (u & 0x70) >> 4;
        return (u & 0x80) != 0 ? 0x84 - t : t - 0x84;
    }

    private static int alaw(byte b) {
        int a = (b ^ 0x55) & 0xFF;
        int t = (a & 0x0F) << 4;
        int seg = (a & 0x70) >> 4;
        if (seg == 0) {
            t += 8;
        } else {
            t += 0x108;
            t <<= seg - 1;
        }
        return (a & 0x80) != 0 ? t : -t;
    }
}
