package com.footballmanagergamesimulator.service.press;

import java.util.Locale;
import java.util.Random;

/**
 * Pure rules of the signed transfer rumour (design DESIGN_START_CARIERA §6.1.1, decided 2026-11-13). The offer
 * behind a rumour is real; what varies with the reporter is how much of it he gets right: the credibility shown
 * and the fee he quotes both follow his reliability.
 */
public final class TransferRumourArticle {

    public static final String HEADLINE = "HEADLINE";
    public static final String BODY = "BODY";
    /** Talks are past the first contact. */
    public static final String STRONG = "STRONG";
    /** A first offer, nothing close. */
    public static final String EARLY = "EARLY";

    static final int ADVANCED_BONUS = 10;
    static final int JITTER = 5;
    static final int MIN_CREDIBILITY = 5;
    static final int MAX_CREDIBILITY = 95;

    private TransferRumourArticle() {
    }

    /** Talks count as advanced once the selling club has answered. */
    public static boolean advanced(String offerStatus) {
        return "counter".equalsIgnoreCase(offerStatus) || "negotiating".equalsIgnoreCase(offerStatus);
    }

    public static String bucket(boolean advanced) {
        return advanced ? STRONG : EARLY;
    }

    /**
     * The credibility printed on the story: his reliability, {@value #ADVANCED_BONUS} higher when talks are
     * advanced, give or take {@value #JITTER}; never certain, never worthless.
     */
    public static int credibility(int reliability, boolean advanced, Random rng) {
        int value = reliability + (advanced ? ADVANCED_BONUS : 0) + rng.nextInt(2 * JITTER + 1) - JITTER;
        return Math.max(MIN_CREDIBILITY, Math.min(MAX_CREDIBILITY, value));
    }

    public static String label(int credibility) {
        if (credibility >= 80) return "rarely wrong";
        if (credibility >= 60) return "usually well informed";
        if (credibility >= 40) return "mixed record";
        return "often wide of the mark";
    }

    /**
     * The fee he quotes: the real one, off by up to half of what his reliability lacks (reliability 90: within
     * 5%; 50: within 25%; 20: within 40%), then rounded the way fees are quoted.
     */
    public static long reportedFee(long realFee, int reliability, Random rng) {
        if (realFee <= 0) return 0;
        double maxError = Math.max(0, 100 - reliability) / 200.0;
        double factor = 1 + (rng.nextDouble() * 2 - 1) * maxError;
        return rounded(Math.round(realFee * factor));
    }

    /** To three leading digits, the third a 0 or a 5: 12,437,000 → 12,500,000. */
    public static long rounded(long amount) {
        if (amount < 1_000) return amount;
        long step = 5;
        for (long scale = amount; scale >= 1_000; scale /= 10) step *= 10;
        return Math.round((double) amount / step) * step;
    }

    public static String money(long amount) {
        return String.format(Locale.UK, "€%,d", Math.max(0L, amount));
    }
}
