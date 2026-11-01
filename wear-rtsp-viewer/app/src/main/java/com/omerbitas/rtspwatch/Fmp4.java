package com.omerbitas.rtspwatch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal fragmented-MP4 reader for Home Assistant's HLS stream: reads the tracks from init.mp4
 * (H.264/H.265 video, AAC audio) and the samples from each segment or part (moof + mdat pairs).
 * Video samples are returned in Annex-B form (start codes), ready for {@link VideoDecoder}.
 */
final class Fmp4 {

    static final class Track {
        int id;
        boolean video;
        String mime; // RtspClient.MIME_H264 / MIME_H265, or null for audio
        long timescale;
        int nalLengthSize = 4;
        final List<byte[]> parameterSets = new ArrayList<>();
        byte[] aacConfig;
        int sampleRate;
        int channels;
        long defaultDuration;
        int defaultSize;
        int defaultFlags;
    }

    static final class Sample {
        final Track track;
        final byte[] data;
        /** Decode time: samples must be fed to the decoder in this order. */
        final long decodeUs;
        /** Presentation time (differs from decode time with B-frames). */
        final long timeUs;
        final boolean keyFrame;

        Sample(Track track, byte[] data, long decodeUs, long timeUs, boolean keyFrame) {
            this.track = track;
            this.data = data;
            this.decodeUs = decodeUs;
            this.timeUs = timeUs;
            this.keyFrame = keyFrame;
        }
    }

    final Map<Integer, Track> tracks = new HashMap<>();

    Track video() {
        for (Track t : tracks.values()) if (t.video) return t;
        return null;
    }

    Track audio() {
        for (Track t : tracks.values()) if (!t.video && t.aacConfig != null) return t;
        return null;
    }

    /** Parses init.mp4. */
    Fmp4(byte[] init) throws IOException {
        int moov = find(init, 0, init.length, "moov");
        if (moov < 0) throw new IOException("init.mp4 has no moov box");
        int moovEnd = moov + size(init, moov);
        for (int trak = find(init, moov + 8, moovEnd, "trak"); trak >= 0;
                trak = find(init, trak + size(init, trak), moovEnd, "trak")) {
            Track t = parseTrak(init, trak);
            if (t != null) tracks.put(t.id, t);
        }
        int mvex = find(init, moov + 8, moovEnd, "mvex");
        if (mvex >= 0) {
            int mvexEnd = mvex + size(init, mvex);
            for (int trex = find(init, mvex + 8, mvexEnd, "trex"); trex >= 0;
                    trex = find(init, trex + size(init, trex), mvexEnd, "trex")) {
                Track t = tracks.get(int32(init, trex + 12));
                if (t == null) continue;
                t.defaultDuration = uint32(init, trex + 20);
                t.defaultSize = int32(init, trex + 24);
                t.defaultFlags = int32(init, trex + 28);
            }
        }
        if (video() == null) throw new IOException("No H.264/H.265 video in Home Assistant stream");
    }

    private Track parseTrak(byte[] b, int trak) {
        int end = trak + size(b, trak);
        Track t = new Track();
        int tkhd = find(b, trak + 8, end, "tkhd");
        if (tkhd < 0) return null;
        t.id = int32(b, tkhd + (b[tkhd + 8] == 1 ? 28 : 20));
        int mdia = find(b, trak + 8, end, "mdia");
        if (mdia < 0) return null;
        int mdiaEnd = mdia + size(b, mdia);
        int mdhd = find(b, mdia + 8, mdiaEnd, "mdhd");
        if (mdhd < 0) return null;
        t.timescale = uint32(b, mdhd + (b[mdhd + 8] == 1 ? 28 : 20));
        int minf = find(b, mdia + 8, mdiaEnd, "minf");
        int stbl = minf < 0 ? -1 : find(b, minf + 8, minf + size(b, minf), "stbl");
        int stsd = stbl < 0 ? -1 : find(b, stbl + 8, stbl + size(b, stbl), "stsd");
        if (stsd < 0) return null;
        int entry = stsd + 16; // header + version/flags + entry count
        String type = type(b, entry);
        int entryEnd = entry + size(b, entry);
        if (type.equals("avc1") || type.equals("avc3")) {
            t.video = true;
            t.mime = RtspClient.MIME_H264;
            int avcC = find(b, entry + 86, entryEnd, "avcC");
            if (avcC < 0) return null;
            int p = avcC + 8;
            t.nalLengthSize = (b[p + 4] & 3) + 1;
            int numSps = b[p + 5] & 0x1F;
            p += 6;
            for (int i = 0; i < numSps; i++) p = readParameterSet(b, p, t);
            int numPps = b[p++] & 0xFF;
            for (int i = 0; i < numPps; i++) p = readParameterSet(b, p, t);
        } else if (type.equals("hvc1") || type.equals("hev1")) {
            t.video = true;
            t.mime = RtspClient.MIME_H265;
            int hvcC = find(b, entry + 86, entryEnd, "hvcC");
            if (hvcC < 0) return null;
            int p = hvcC + 8;
            t.nalLengthSize = (b[p + 21] & 3) + 1;
            int arrays = b[p + 22] & 0xFF;
            p += 23;
            for (int i = 0; i < arrays; i++) {
                int count = uint16(b, p + 1);
                p += 3;
                for (int j = 0; j < count; j++) p = readParameterSet(b, p, t);
            }
        } else if (type.equals("mp4a")) {
            t.channels = uint16(b, entry + 24);
            t.sampleRate = uint16(b, entry + 32); // 16.16 fixed point, integer part
            int esds = find(b, entry + 36, entryEnd, "esds");
            if (esds >= 0) t.aacConfig = decoderSpecificInfo(b, esds + 12, esds + size(b, esds));
        } else {
            return null; // unsupported codec, e.g. MP3 audio
        }
        return t;
    }

    private static int readParameterSet(byte[] b, int p, Track t) {
        int len = uint16(b, p);
        byte[] set = new byte[len];
        System.arraycopy(b, p + 2, set, 0, len);
        t.parameterSets.add(set);
        return p + 2 + len;
    }

    /** Walks the esds descriptors down to DecoderSpecificInfo (tag 5), the AAC config. */
    private static byte[] decoderSpecificInfo(byte[] b, int p, int end) {
        while (p < end) {
            int tag = b[p++] & 0xFF;
            int len = 0;
            for (int i = 0; i < 4; i++) {
                int c = b[p++] & 0xFF;
                len = (len << 7) | (c & 0x7F);
                if ((c & 0x80) == 0) break;
            }
            if (tag == 3) {
                int flags = b[p + 2] & 0xFF;
                p += 3;
                if ((flags & 0x80) != 0) p += 2;
                if ((flags & 0x40) != 0) p += 1 + (b[p] & 0xFF);
                if ((flags & 0x20) != 0) p += 2;
            } else if (tag == 4) {
                p += 13;
            } else if (tag == 5) {
                byte[] config = new byte[len];
                System.arraycopy(b, p, config, 0, len);
                return config;
            } else {
                p += len;
            }
        }
        return null;
    }

    /** Reads every sample in a segment or part (one or more moof + mdat pairs). */
    List<Sample> samples(byte[] b) {
        List<Sample> out = new ArrayList<>();
        for (int moof = find(b, 0, b.length, "moof"); moof >= 0;
                moof = find(b, moof + size(b, moof), b.length, "moof")) {
            int moofEnd = moof + size(b, moof);
            for (int traf = find(b, moof + 8, moofEnd, "traf"); traf >= 0;
                    traf = find(b, traf + size(b, traf), moofEnd, "traf")) {
                readTraf(b, moof, traf, out);
            }
        }
        return out;
    }

    private void readTraf(byte[] b, int moof, int traf, List<Sample> out) {
        int end = traf + size(b, traf);
        int tfhd = find(b, traf + 8, end, "tfhd");
        if (tfhd < 0) return;
        int flags = int32(b, tfhd + 8) & 0xFFFFFF;
        Track t = tracks.get(int32(b, tfhd + 12));
        if (t == null) return;
        int p = tfhd + 16;
        long base = moof; // default-base-is-moof (and the default without a base offset)
        if ((flags & 0x01) != 0) {
            base = int64(b, p);
            p += 8;
        }
        if ((flags & 0x02) != 0) p += 4;
        long defaultDuration = t.defaultDuration;
        int defaultSize = t.defaultSize;
        int defaultFlags = t.defaultFlags;
        if ((flags & 0x08) != 0) {
            defaultDuration = uint32(b, p);
            p += 4;
        }
        if ((flags & 0x10) != 0) {
            defaultSize = int32(b, p);
            p += 4;
        }
        if ((flags & 0x20) != 0) defaultFlags = int32(b, p);

        long decodeTime = 0;
        int tfdt = find(b, traf + 8, end, "tfdt");
        if (tfdt >= 0) decodeTime = b[tfdt + 8] == 1 ? int64(b, tfdt + 12) : uint32(b, tfdt + 12);

        for (int trun = find(b, traf + 8, end, "trun"); trun >= 0;
                trun = find(b, trun + size(b, trun), end, "trun")) {
            int version = b[trun + 8] & 0xFF;
            int tf = int32(b, trun + 8) & 0xFFFFFF;
            int count = int32(b, trun + 12);
            int q = trun + 16;
            long dataPos = base;
            if ((tf & 0x01) != 0) {
                dataPos = base + int32(b, q);
                q += 4;
            }
            int firstFlags = -1;
            if ((tf & 0x04) != 0) {
                firstFlags = int32(b, q);
                q += 4;
            }
            for (int i = 0; i < count; i++) {
                long duration = defaultDuration;
                int sampleSize = defaultSize;
                int sampleFlags = i == 0 && firstFlags != -1 ? firstFlags : defaultFlags;
                long cts = 0;
                if ((tf & 0x100) != 0) {
                    duration = uint32(b, q);
                    q += 4;
                }
                if ((tf & 0x200) != 0) {
                    sampleSize = int32(b, q);
                    q += 4;
                }
                if ((tf & 0x400) != 0) {
                    sampleFlags = int32(b, q);
                    q += 4;
                }
                if ((tf & 0x800) != 0) {
                    cts = version == 0 ? uint32(b, q) : int32(b, q);
                    q += 4;
                }
                if (dataPos < 0 || dataPos + sampleSize > b.length) return;
                long decodeUs = decodeTime * 1000000L / t.timescale;
                long timeUs = (decodeTime + cts) * 1000000L / t.timescale;
                boolean sync = (sampleFlags & 0x10000) == 0; // sample_is_non_sync_sample
                byte[] data = t.video
                        ? toAnnexB(b, (int) dataPos, sampleSize, t.nalLengthSize)
                        : copy(b, (int) dataPos, sampleSize);
                out.add(new Sample(t, data, decodeUs, timeUs, t.video && sync));
                dataPos += sampleSize;
                decodeTime += duration;
            }
        }
    }

    /** Replaces the NAL length prefixes with start codes. */
    private static byte[] toAnnexB(byte[] b, int pos, int size, int lengthSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(size + 16);
        int end = pos + size;
        while (pos + lengthSize <= end) {
            int len = 0;
            for (int i = 0; i < lengthSize; i++) len = (len << 8) | (b[pos + i] & 0xFF);
            pos += lengthSize;
            if (len <= 0 || pos + len > end) break;
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(1);
            out.write(b, pos, len);
            pos += len;
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------------------------------
    // Box helpers

    /** Offset of the first box of the given type in [from, to), or -1. */
    private static int find(byte[] b, int from, int to, String type) {
        int p = from;
        while (p + 8 <= to) {
            int size = size(b, p);
            if (size < 8) return -1;
            if (type(b, p).equals(type)) return p;
            p += size;
        }
        return -1;
    }

    private static int size(byte[] b, int p) {
        return int32(b, p);
    }

    private static String type(byte[] b, int p) {
        return new String(b, p + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static int uint16(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private static int int32(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    private static long uint32(byte[] b, int p) {
        return int32(b, p) & 0xFFFFFFFFL;
    }

    private static long int64(byte[] b, int p) {
        return (uint32(b, p) << 32) | uint32(b, p + 4);
    }

    private static byte[] copy(byte[] b, int pos, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, pos, out, 0, len);
        return out;
    }
}
