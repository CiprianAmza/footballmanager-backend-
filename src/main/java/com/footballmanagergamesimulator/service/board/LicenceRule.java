package com.footballmanagergamesimulator.service.board;

import com.footballmanagergamesimulator.user.CoachingLicence;

/**
 * The coaching licence rule (design DESIGN_START_CARIERA §3, decided 2026-11-13): a top flight needs a Pro licence,
 * a second tier an A, lower leagues a B. Without it the manager is registered as assistant (a goalkeeping licence:
 * as goalkeeping coach) while a front coach is head coach on paper; famous clubs (reputation above
 * {@value #REFUSING_REPUTATION}) refuse him outright.
 */
public final class LicenceRule {

    public static final int REFUSING_REPUTATION = 7_000;

    public enum Role { HEAD_COACH, ASSISTANT, GOALKEEPING_COACH }

    private LicenceRule() {
    }

    public static CoachingLicence required(int leagueTier) {
        if (leagueTier <= 1) return CoachingLicence.PRO;
        if (leagueTier == 2) return CoachingLicence.A;
        return CoachingLicence.B;
    }

    /** C < B < A < Pro; no licence and a goalkeeping licence never qualify. */
    public static boolean qualifies(CoachingLicence held, CoachingLicence required) {
        return rank(held) >= rank(required);
    }

    public static Role role(CoachingLicence held, CoachingLicence required) {
        if (qualifies(held, required)) return Role.HEAD_COACH;
        return held == CoachingLicence.GOALKEEPING ? Role.GOALKEEPING_COACH : Role.ASSISTANT;
    }

    public static boolean refuses(int clubReputation, CoachingLicence held, CoachingLicence required) {
        return clubReputation > REFUSING_REPUTATION && !qualifies(held, required);
    }

    private static int rank(CoachingLicence licence) {
        return switch (licence) {
            case C -> 1;
            case B -> 2;
            case A -> 3;
            case PRO -> 4;
            default -> 0;
        };
    }
}
