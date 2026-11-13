package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.Analyst;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pure rules of the signed post-match article (design DESIGN_START_CARIERA §6.1.1, decided 2026-11-13): which kind
 * of fragment fits each section, the reporter's tone towards the club, and what his style adds to the article.
 */
public final class MatchReactionArticle {

    public static final String HEADLINE = "HEADLINE";
    public static final String KICKER = "KICKER";
    public static final String VERDICT = "VERDICT";
    public static final String STATS = "STATS";
    public static final String TALKING_POINT = "TALKING_POINT";

    /** A win or defeat by this many goals is a big one. */
    public static final int BIG_MARGIN = 3;
    /** A draw with this many goals is a thriller. */
    public static final int HIGH_DRAW_GOALS = 4;

    private MatchReactionArticle() {
    }

    /** The match as the article sees it, from {@code team}'s side. */
    public record Facts(long teamId, String teamName, long opponentTeamId, String opponentName,
                        int teamScore, int opponentScore, long competitionId, String competitionName,
                        int season, int round, long homeTeamId, long awayTeamId) {
    }

    /** The match numbers a reporter may quote, from the club's side. */
    public record Numbers(int possession, int shots, int shotsOnTarget, int opponentShots, int opponentShotsOnTarget) {
    }

    public enum Result {
        BIG_WIN("WIN"), WIN("WIN"), HIGH_DRAW("DRAW"), DRAW("DRAW"), LOSS("LOSS"), BIG_LOSS("LOSS");

        private final String outcome;

        Result(String outcome) {
            this.outcome = outcome;
        }

        /** WIN, DRAW or LOSS, whatever the margin. */
        public String outcome() {
            return outcome;
        }
    }

    public static Result result(int teamScore, int opponentScore) {
        int margin = teamScore - opponentScore;
        if (margin >= BIG_MARGIN) return Result.BIG_WIN;
        if (margin > 0) return Result.WIN;
        if (margin == 0) return teamScore + opponentScore >= HIGH_DRAW_GOALS ? Result.HIGH_DRAW : Result.DRAW;
        return margin <= -BIG_MARGIN ? Result.BIG_LOSS : Result.LOSS;
    }

    /** The score as it is spoken, the higher number first: "lost 4-0". */
    public static String score(int teamScore, int opponentScore) {
        return Math.max(teamScore, opponentScore) + "-" + Math.min(teamScore, opponentScore);
    }

    /**
     * The reporter's tone in this article: his own, moved by what he remembers of the manager, then one tier
     * kinder at the club he supports and one tier harsher at a rival of it.
     */
    public static Analyst.Temperament tone(Analyst reporter, int relationWithManager, AppointmentArticle.Affinity affinity) {
        Analyst.Temperament tone = AnalystStance.towards(reporter, relationWithManager);
        return switch (affinity) {
            case FAVORITE -> AnalystStance.shifted(tone, 1);
            case RIVAL -> AnalystStance.shifted(tone, -1);
            case NONE -> tone;
        };
    }

    /** The closing paragraph depends on the outcome and on his tone, not on the margin. */
    public static String talkingPointBucket(Result result, Analyst.Temperament tone) {
        return result.outcome() + "_" + tone.name();
    }

    /**
     * Who had the better of the numbers: more shots on target; level there, more shots; level there too, at
     * least half of the ball.
     */
    public static boolean onTop(Numbers numbers) {
        if (numbers.shotsOnTarget() != numbers.opponentShotsOnTarget()) {
            return numbers.shotsOnTarget() > numbers.opponentShotsOnTarget();
        }
        if (numbers.shots() != numbers.opponentShots()) return numbers.shots() > numbers.opponentShots();
        return numbers.possession() >= 50;
    }

    public static String statsBucket(Result result, Numbers numbers) {
        return result.outcome() + (onTop(numbers) ? "_ON_TOP" : "_SECOND_BEST");
    }

    /** True with the given 0–100 chance. */
    public static boolean happens(int chance, Random rng) {
        return rng.nextInt(100) < chance;
    }

    /**
     * The article's paragraphs, in order: verbosity 1 is the verdict alone, 2 adds the talking point, 3 a second
     * verdict as well. The numbers paragraph, when he quotes them, comes before the talking point.
     */
    public static List<String> sections(int verbosity, boolean numbers) {
        List<String> sections = new ArrayList<>();
        sections.add(VERDICT);
        if (verbosity >= 3) sections.add(VERDICT);
        if (numbers) sections.add(STATS);
        if (verbosity >= 2) sections.add(TALKING_POINT);
        return sections;
    }

    /** "MELTDOWN: Orbit FC torn apart" with a kicker, the plain headline without. */
    public static String headline(String kicker, String headline) {
        return kicker == null || kicker.isBlank() ? headline : kicker + ": " + headline;
    }
}
