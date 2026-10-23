package com.omerbitas.rtspwatch;

/** Reads the picture size from an H.264 sequence parameter set (NAL header included). */
final class H264Sps {

    final int width;
    final int height;

    private H264Sps(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /** Returns null if the SPS can't be parsed. */
    static H264Sps parse(byte[] nal) {
        try {
            Bits b = new Bits(unescape(nal));
            b.skip(8); // NAL header
            int profile = b.read(8);
            b.skip(16); // constraint flags, level
            b.ue(); // seq_parameter_set_id
            int chromaFormat = 1;
            if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44
                    || profile == 83 || profile == 86 || profile == 118 || profile == 128
                    || profile == 138 || profile == 139 || profile == 134 || profile == 135) {
                chromaFormat = b.ue();
                if (chromaFormat == 3) b.skip(1);
                b.ue(); // bit_depth_luma
                b.ue(); // bit_depth_chroma
                b.skip(1);
                if (b.read(1) == 1) { // seq_scaling_matrix_present
                    for (int i = 0; i < (chromaFormat != 3 ? 8 : 12); i++) {
                        if (b.read(1) == 1) skipScalingList(b, i < 6 ? 16 : 64);
                    }
                }
            }
            b.ue(); // log2_max_frame_num
            int pocType = b.ue();
            if (pocType == 0) {
                b.ue();
            } else if (pocType == 1) {
                b.skip(1);
                b.se();
                b.se();
                int n = b.ue();
                for (int i = 0; i < n; i++) b.se();
            }
            b.ue(); // max_num_ref_frames
            b.skip(1);
            int widthMbs = b.ue() + 1;
            int heightMapUnits = b.ue() + 1;
            int frameMbsOnly = b.read(1);
            if (frameMbsOnly == 0) b.skip(1);
            b.skip(1); // direct_8x8_inference
            int width = widthMbs * 16;
            int height = (2 - frameMbsOnly) * heightMapUnits * 16;
            if (b.read(1) == 1) { // frame_cropping
                int cropUnitX = chromaFormat == 0 || chromaFormat == 3 ? 1 : 2;
                int cropUnitY = (chromaFormat == 1 ? 2 : 1) * (2 - frameMbsOnly);
                width -= (b.ue() + b.ue()) * cropUnitX;
                height -= (b.ue() + b.ue()) * cropUnitY;
            }
            if (width <= 0 || height <= 0 || width > 8192 || height > 8192) return null;
            return new H264Sps(width, height);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void skipScalingList(Bits b, int size) {
        int last = 8;
        int next = 8;
        for (int i = 0; i < size; i++) {
            if (next != 0) next = (last + b.se() + 256) % 256;
            last = next == 0 ? last : next;
        }
    }

    /** Removes emulation prevention bytes (00 00 03 -> 00 00). */
    private static byte[] unescape(byte[] in) {
        byte[] out = new byte[in.length];
        int n = 0;
        int zeros = 0;
        for (byte value : in) {
            if (zeros >= 2 && value == 3) {
                zeros = 0;
                continue;
            }
            out[n++] = value;
            zeros = value == 0 ? zeros + 1 : 0;
        }
        byte[] result = new byte[n];
        System.arraycopy(out, 0, result, 0, n);
        return result;
    }

    private static final class Bits {
        private final byte[] data;
        private int pos;

        Bits(byte[] data) {
            this.data = data;
        }

        int read(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                if (pos >= data.length * 8) throw new IndexOutOfBoundsException();
                value = (value << 1) | ((data[pos >> 3] >> (7 - (pos & 7))) & 1);
                pos++;
            }
            return value;
        }

        void skip(int count) {
            pos += count;
        }

        int ue() {
            int zeros = 0;
            while (read(1) == 0) {
                if (++zeros > 31) throw new IllegalStateException("bad exp-golomb");
            }
            return (int) ((1L << zeros) - 1 + (zeros == 0 ? 0 : read(zeros)));
        }

        int se() {
            int v = ue();
            return (v & 1) == 1 ? (v + 1) / 2 : -(v / 2);
        }
    }
}
