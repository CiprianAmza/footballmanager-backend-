package com.footballmanagergamesimulator.service;

import java.util.Comparator;
import java.util.List;

/**
 * Whether a player (through his agent) is willing to talk to a club about joining it (decided 2026-11-12).
 * Pure rules; {@link PlayerInterestService} gathers the facts.
 */
public final class PlayerInterest {

    /** AMBITION: refuses a club whose best three players average less than this share of his rating. */
    public static final double MIN_TOP_THREE_SHARE = 0.85;
    /** FAME: refuses a club more than this much below his own club's reputation. */
    public static final int MAX_REPUTATION_DROP = 200;
    /** A player who likes the club or the manager (relation at least {@link #BOND}) gives up more to come: */
    public static final int BOND = 70;
    public static final double BOND_TOP_THREE_SHARE = 0.75;
    public static final int BOND_REPUTATION_DROP = 400;
    /** …and asks this much less. */
    public static final double BOND_DISCOUNT = 0.15;
    /** MONEY: asks at least his current wage plus this share… */
    public static final double MONEY_BASE_RAISE = 0.10;
    /** …plus this share per rating point he has above the average of the club's best three. */
    public static final double MONEY_RAISE_PER_POINT = 0.01;

    public enum Reason {
        INTERESTED("My client is interested. We can talk."),
        AGENT_REFUSES("I will not negotiate with you."),
        ONE_CLUB_PLAYER("My client will never leave his club."),
        JUST_SIGNED("My client signed his contract less than six months ago. He is not interested."),
        TEAM_TOO_WEAK("Your squad is not at my client's level. He is not interested."),
        CLUB_NOT_FAMOUS_ENOUGH("Your club is not big enough for my client. He is not interested.");

        private final String agentMessage;

        Reason(String agentMessage) {
            this.agentMessage = agentMessage;
        }

        public String agentMessage() {
            return agentMessage;
        }
    }

    private PlayerInterest() {
    }

    /** What his personality says about this club (relation, one-club and recent signature are checked before). */
    public static Reason byPersonality(com.footballmanagergamesimulator.model.PlayerPersonality personality,
                                       double playerRating, List<Double> squadRatings,
                                       int clubReputation, int ownClubReputation) {
        return byPersonality(personality, playerRating, squadRatings, clubReputation, ownClubReputation, false);
    }

    /** {@code bond}: he likes the club or its manager, so he accepts a weaker or less famous club. */
    public static Reason byPersonality(com.footballmanagergamesimulator.model.PlayerPersonality personality,
                                       double playerRating, List<Double> squadRatings,
                                       int clubReputation, int ownClubReputation, boolean bond) {
        int maxDrop = bond ? BOND_REPUTATION_DROP : MAX_REPUTATION_DROP;
        double minShare = bond ? BOND_TOP_THREE_SHARE : MIN_TOP_THREE_SHARE;
        return switch (personality) {
            case MONEY -> Reason.INTERESTED;
            case FAME -> clubReputation < ownClubReputation - maxDrop
                    ? Reason.CLUB_NOT_FAMOUS_ENOUGH : Reason.INTERESTED;
            case AMBITION -> teamTooWeak(playerRating, squadRatings, minShare) ? Reason.TEAM_TOO_WEAK : Reason.INTERESTED;
        };
    }

    /** What he asks after his bond with the club or manager: 15% less when there is one. */
    public static long withBond(long value, boolean bond) {
        return bond ? Math.round(value * (1 - BOND_DISCOUNT)) : value;
    }

    /**
     * MONEY: the least monthly wage he takes to move — his current wage plus 10%, plus 1% for every rating
     * point he has above the average of the club's best three (none for a club as good as him or better).
     */
    public static long moneyWage(long currentWage, double playerRating, List<Double> squadRatings) {
        double advantage = Math.max(0, playerRating - topThreeAverage(squadRatings));
        return Math.round(currentWage * (1 + MONEY_BASE_RAISE + MONEY_RAISE_PER_POINT * advantage));
    }

    static double topThreeAverage(List<Double> squadRatings) {
        return squadRatings.stream().sorted(Comparator.reverseOrder()).limit(3)
                .mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /** True when the best three ratings of the club average clearly below the player's rating. */
    public static boolean teamTooWeak(double playerRating, List<Double> squadRatings) {
        return teamTooWeak(playerRating, squadRatings, MIN_TOP_THREE_SHARE);
    }

    static boolean teamTooWeak(double playerRating, List<Double> squadRatings, double minShare) {
        List<Double> top = squadRatings.stream().sorted(Comparator.reverseOrder()).limit(3).toList();
        if (top.isEmpty()) return true;
        double average = top.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return average < minShare * playerRating;
    }

}
