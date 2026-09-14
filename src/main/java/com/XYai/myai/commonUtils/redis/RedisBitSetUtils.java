package com.XYai.myai.commonUtils.redis;

import java.util.BitSet;

public final class RedisBitSetUtils {

    private static final byte[] REVERSE_BITS = new byte[256];

    static {
        for (int i = 0; i < 256; i++) {
            int v = i;
            int r = 0;
            for (int b = 0; b < 8; b++) {
                r = (r << 1) | (v & 1);
                v >>= 1;
            }
            REVERSE_BITS[i] = (byte) r;
        }
    }

    private RedisBitSetUtils() {
    }

    public static BitSet bitSetFromRedisBytes(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return new BitSet();
        }
        byte[] fixed = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            fixed[i] = REVERSE_BITS[raw[i] & 0xFF];
        }
        return BitSet.valueOf(fixed);
    }

    public static byte[] bitSetToRedisBytes(BitSet bits) {
        if (bits == null) {
            return new byte[0];
        }
        byte[] raw = bits.toByteArray();
        for (int i = 0; i < raw.length; i++) {
            raw[i] = REVERSE_BITS[raw[i] & 0xFF];
        }
        return raw;
    }
}
