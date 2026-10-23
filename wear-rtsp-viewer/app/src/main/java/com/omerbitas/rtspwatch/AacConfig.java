package com.omerbitas.rtspwatch;

/** Builds the AAC AudioSpecificConfig the decoder needs. */
final class AacConfig {

    private static final int[] RATES = {
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

    private AacConfig() {
    }

    /** AudioSpecificConfig for the given object type (2 = AAC-LC), sample rate and channels. */
    static byte[] build(int objectType, int sampleRate, int channels) {
        int index = -1;
        for (int i = 0; i < RATES.length; i++) {
            if (RATES[i] == sampleRate) index = i;
        }
        if (index < 0) {
            // Explicit 24-bit sample rate: 5 + 4 + 24 + 4 bits, padded to 5 bytes.
            long bits = ((long) objectType << 35) | (15L << 31) | ((long) sampleRate << 7) | ((long) channels << 3);
            return new byte[]{
                    (byte) (bits >> 32), (byte) (bits >> 24), (byte) (bits >> 16), (byte) (bits >> 8), (byte) bits};
        }
        int bits = (objectType << 11) | (index << 7) | (channels << 3);
        return new byte[]{(byte) (bits >> 8), (byte) bits};
    }

    /**
     * Extracts the AudioSpecificConfig from an MP4A-LATM StreamMuxConfig (RFC 6416 "config"),
     * simplified to AAC-LC. Returns null for versions this doesn't understand.
     */
    static byte[] fromStreamMuxConfig(byte[] config, int fallbackRate, int fallbackChannels) {
        if (config == null || config.length < 3) return null;
        try {
            int pos = 0;
            if (bits(config, pos, 1) != 0) return null; // audioMuxVersion 1 isn't supported
            pos += 1 + 1 + 6 + 4 + 3; // version, allStreamsSameTimeFraming, numSubFrames, numProgram, numLayer
            int objectType = bits(config, pos, 5);
            pos += 5;
            if (objectType == 31) {
                objectType = 32 + bits(config, pos, 6);
                pos += 6;
            }
            int rateIndex = bits(config, pos, 4);
            pos += 4;
            int rate;
            if (rateIndex == 15) {
                rate = bits(config, pos, 24);
                pos += 24;
            } else {
                rate = rateIndex < RATES.length ? RATES[rateIndex] : fallbackRate;
            }
            int channels = bits(config, pos, 4);
            if (channels == 0) channels = fallbackChannels;
            // HE-AAC and other profiles are decoded as plain AAC-LC at the core rate.
            return build(2, rate, channels);
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    private static int bits(byte[] data, int bitOffset, int count) {
        int value = 0;
        for (int i = 0; i < count; i++) {
            int pos = bitOffset + i;
            value = (value << 1) | ((data[pos >> 3] >> (7 - (pos & 7))) & 1);
        }
        return value;
    }
}
