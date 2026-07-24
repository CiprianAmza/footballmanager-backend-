package com.footballmanagergamesimulator.regent.market.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Stateless domain-separated randomness.  A quote never consumes a shared RNG sequence. */
final class DeterministicMarketRandom {
    private DeterministicMarketRandom() { }

    static double unit(long saveSeed, String instrumentId, long day, String algorithmVersion, String purpose) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(Long.BYTES * 2).putLong(saveSeed).putLong(day).array());
            update(digest, instrumentId);
            update(digest, algorithmVersion);
            update(digest, purpose);
            byte[] hash = digest.digest();
            long bits = ByteBuffer.wrap(hash).getLong() >>> 11;
            return bits * 0x1.0p-53;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
