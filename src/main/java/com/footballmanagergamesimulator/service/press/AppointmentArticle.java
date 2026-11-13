package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.Analyst;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure rules of the appointment article (design DESIGN_START_CARIERA §6.1, decided 2026-11-12): which kind of
 * fragment fits each section, and filling the placeholders.
 */
public final class AppointmentArticle {

    /** Below this share of the club's reputation the manager is "well below the club's level". */
    public static final double NEAR_SHARE = 0.70;
    /** International pundits comment on clubs at least this famous. */
    public static final int INTERNATIONAL_CLUB_REPUTATION = 8_000;
    /** Rivals = the top clubs of a league by reputation (the derby rule already used for match reactions). */
    public static final int RIVAL_TOP = 3;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z]+)}");

    private AppointmentArticle() {
    }

    public enum Gap { ABOVE, NEAR, BELOW }

    public enum Affinity { NONE, FAVORITE, RIVAL }

    /** His reputation against the club's. */
    public static Gap gap(int managerReputation, int clubReputation) {
        if (managerReputation >= clubReputation) return Gap.ABOVE;
        if (managerReputation >= NEAR_SHARE * clubReputation) return Gap.NEAR;
        return Gap.BELOW;
    }

    /** "The club and the decision". */
    public static String decisionBucket(Gap gap) {
        return switch (gap) {
            case ABOVE -> "NATURAL";
            case NEAR -> "MIXED";
            case BELOW -> "SURPRISE";
        };
    }

    /** Overall tone of the headline. */
    public static String headlineBucket(Gap gap) {
        return switch (gap) {
            case ABOVE -> "POSITIVE";
            case NEAR -> "MIXED";
            case BELOW -> "NEGATIVE";
        };
    }

    /**
     * The club's recent form from its last league finish (1 = best) among {@code teams}: champion, top third
     * (rising), bottom three (crisis), otherwise declining.
     */
    public static String contextBucket(int position, int teams) {
        if (position <= 1) return "CHAMPION";
        if (teams > 0 && position >= teams - 2) return "CRISIS";
        if (teams > 0 && position <= Math.max(2, teams / 3)) return "RISING";
        return "DECLINING";
    }

    /** How long the club's recent managers lasted: under 1.5 seasons is short; fewer than 2 managers, no history. */
    public static String tenureBucket(double averageSeasons, int managers) {
        if (managers < 2) return "NO_HISTORY";
        return averageSeasons < 1.5 ? "SHORT" : "LONG";
    }

    /** The pundit's sympathy for the club he is talking about. */
    public static Affinity affinity(Analyst analyst, long clubId, java.util.Set<Long> rivalsOfClub) {
        if (analyst.getFavoriteTeamId() == 0) return Affinity.NONE;
        if (analyst.getFavoriteTeamId() == clubId) return Affinity.FAVORITE;
        return rivalsOfClub.contains(analyst.getFavoriteTeamId()) ? Affinity.RIVAL : Affinity.NONE;
    }

    public static String analystBucket(Analyst.Temperament temperament, Gap gap, Affinity affinity) {
        return temperament.name() + "_" + gap.name() + "_" + affinity.name();
    }

    /** "1st", "2nd", "3rd", "11th"… */
    public static String ordinal(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) return n + "th";
        return n + switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    /** Fills every known placeholder; an unknown one is left as is (the fragment loader rejects those). */
    public static String fill(String text, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
