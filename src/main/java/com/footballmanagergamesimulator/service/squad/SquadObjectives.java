package com.footballmanagergamesimulator.service.squad;

import java.util.List;

/**
 * Objectives agreed at the meeting with the squad (design DESIGN_START_CARIERA §6.4, decided 2026-11-12): for each
 * competition the manager names a target on a ladder, the players have their own, and the gap moves every
 * player's relation with the manager.
 */
public final class SquadObjectives {

    /** Highest ambition first. */
    public static final List<String> LEAGUE = List.of("TITLE", "TOP_3", "TOP_HALF", "AVOID_RELEGATION");
    public static final List<String> CUP = List.of("WIN", "FINAL", "SEMI_FINAL", "QUARTER_FINAL", "TAKE_PART");

    private SquadObjectives() {
    }

    /** Bonus levels: share of one month of the squad's wages, and the squad's morale change at the meeting. */
    public enum Bonus {
        MIN(0.25, -3), MID(0.50, 0), MAX(1.00, 3);

        public final double wageShare;
        public final double morale;

        Bonus(double wageShare, double morale) {
            this.wageShare = wageShare;
            this.morale = morale;
        }
    }

    /** Morale change at the meeting is capped at this, whatever the number of competitions. */
    public static final double MAX_MEETING_MORALE = 10;

    /**
     * Whether the target was reached from the final place: league (1 = champion) among {@code teams}; cup places
     * are shared by stage (winner 1, runner-up 2, semi-finalists 3, quarter-finalists 5).
     */
    public static boolean reached(boolean league, String target, int place, int teams) {
        if (place <= 0) return false;
        if (league) {
            return switch (target) {
                case "TITLE" -> place <= 1;
                case "TOP_3" -> place <= 3;
                case "TOP_HALF" -> place <= Math.max(3, teams / 2);
                case "AVOID_RELEGATION" -> teams <= 3 || place <= teams - 3;
                default -> false;
            };
        }
        return switch (target) {
            case "WIN" -> place <= 1;
            case "FINAL" -> place <= 2;
            case "SEMI_FINAL" -> place <= 4;
            case "QUARTER_FINAL" -> place <= 8;
            case "TAKE_PART" -> true;
            default -> false;
        };
    }

    public static List<String> ladder(boolean league) {
        return league ? LEAGUE : CUP;
    }

    /** What the players expect in a league from the press's predicted finish ({@code position} of {@code teams}). */
    public static String leagueWish(int position, int teams) {
        if (position <= 1) return "TITLE";
        if (position <= 3) return "TOP_3";
        if (position <= Math.max(3, teams / 2)) return "TOP_HALF";
        return "AVOID_RELEGATION";
    }

    /** What the players expect in a cup from the club's reputation rank among the entrants. */
    public static String cupWish(int reputationRank) {
        if (reputationRank <= 1) return "WIN";
        if (reputationRank <= 2) return "FINAL";
        if (reputationRank <= 4) return "SEMI_FINAL";
        if (reputationRank <= 8) return "QUARTER_FINAL";
        return "TAKE_PART";
    }

    /**
     * Relation change for every player: same target +3; one step more ambitious +5; two or more steps more
     * ambitious (unrealistic) −5; less ambitious −5 per step.
     */
    public static int relationDelta(List<String> ladder, String managerTarget, String playersWish) {
        int steps = ladder.indexOf(playersWish) - ladder.indexOf(managerTarget);
        if (steps == 0) return 3;
        if (steps == 1) return 5;
        if (steps >= 2) return -5;
        return 5 * steps;
    }
}
