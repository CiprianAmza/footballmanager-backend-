package com.footballmanagergamesimulator.compartment.effects;

import com.footballmanagergamesimulator.matchplan.KnockoutPlanSplit;
import com.footballmanagergamesimulator.matchplan.MatchScoringDecision;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Domain-separated deterministic seed for canonical match-stat projection. */
public final class CanonicalMatchStatsSeed {
    private static final String DOMAIN = "COMPARTMENT_MATCH_EFFECTS_V1";

    private CanonicalMatchStatsSeed() {}

    public static long derive(CanonicalMatchEffectsInput input) {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        return derive(input.decision(), input.split());
    }

    public static long derive(MatchScoringDecision decision, KnockoutPlanSplit split) {
        if (decision == null || split == null) {
            throw new IllegalArgumentException("decision and split are required");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
        put(digest, DOMAIN);
        put(digest, decision.fixtureKey());
        put(digest, Long.toString(decision.seed()));
        put(digest, decision.scoreEngine().name());
        put(digest, decision.scoreAlgorithmVersion());
        put(digest, decision.configFingerprint());
        put(digest, decision.inputFingerprint());
        put(digest, Integer.toString(split.score90Home()));
        put(digest, Integer.toString(split.score90Away()));
        put(digest, Integer.toString(split.etHome()));
        put(digest, Integer.toString(split.etAway()));
        put(digest, Integer.toString(split.shootoutHome()));
        put(digest, Integer.toString(split.shootoutAway()));
        return ByteBuffer.wrap(digest.digest()).getLong();
    }

    private static void put(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
