package com.footballmanagergamesimulator.animation.v1;

import com.footballmanagergamesimulator.animation.AnimationOutcome;
import com.footballmanagergamesimulator.animation.AnimationPhase;
import com.footballmanagergamesimulator.animation.MatchMomentSpec;
import com.footballmanagergamesimulator.animation.PatternId;
import com.footballmanagergamesimulator.animation.PitchPoint;
import com.footballmanagergamesimulator.animation.PlayPattern;
import com.footballmanagergamesimulator.animation.PlayScript;
import com.footballmanagergamesimulator.animation.PlayerSnapshot;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

abstract class LegacyBasePattern implements PlayPattern {
    private final PatternId id;
    private final AnimationPhase phase;
    private final Set<AnimationOutcome> outcomes;

    LegacyBasePattern(PatternId id, AnimationPhase phase, Set<AnimationOutcome> outcomes) {
        this.id = id;
        this.phase = phase;
        this.outcomes = Set.copyOf(outcomes);
    }

    LegacyBasePattern(PatternId id, AnimationPhase phase) {
        this(id, phase, EnumSet.allOf(AnimationOutcome.class));
    }

    @Override public PatternId id() { return id; }
    @Override public AnimationPhase phase() { return phase; }
    @Override public Set<AnimationOutcome> supportedOutcomes() { return outcomes; }

    @Override
    public boolean supports(MatchMomentSpec spec) {
        return spec.phase() == phase && outcomes.contains(spec.outcome());
    }

    static double between(Random random, double min, double max) {
        return min + random.nextDouble() * (max - min);
    }

    static PitchPoint point(Random random, double minX, double maxX, double minY, double maxY) {
        return new PitchPoint(between(random, minX, maxX), between(random, minY, maxY));
    }

    static PlayScript.Touch touch(PlayerSnapshot player, PitchPoint point, int dwell, double bend) {
        return new PlayScript.Touch(player.playerId(), point, dwell, bend);
    }

    static PlayScript open(PatternId id, List<PlayScript.Touch> touches, double shotBend) {
        return new PlayScript(id, touches, null, 0, shotBend);
    }

    static PlayScript setPiece(PatternId id, List<PlayScript.Touch> touches,
                               PitchPoint spot, int prelude, double shotBend) {
        return new PlayScript(id, touches, spot, prelude, shotBend);
    }

    /** Attackers in raw snapshot input order — the version-1 selection order, frozen. */
    static List<PlayerSnapshot> attackers(MatchMomentSpec spec) {
        List<PlayerSnapshot> attackers = new ArrayList<>();
        for (PlayerSnapshot p : spec.playersOnPitch()) if (p.teamId() == spec.scoringTeamId()) attackers.add(p);
        return attackers;
    }

    static int supportCount(MatchMomentSpec spec) {
        int count = 0;
        for (PlayerSnapshot player : attackers(spec)) {
            if (player.playerId() != spec.scorerId()
                    && (spec.assisterId() == null || player.playerId() != spec.assisterId())) count++;
        }
        return count;
    }

    static PlayerSnapshot support(MatchMomentSpec spec, Random random, Set<Long> used, String... positions) {
        Set<Long> excluded = new HashSet<>(used);
        excluded.add(spec.scorerId());
        if (spec.assisterId() != null) excluded.add(spec.assisterId());
        for (String position : positions) {
            List<PlayerSnapshot> matches = attackers(spec).stream()
                    .filter(player -> !excluded.contains(player.playerId()))
                    .filter(player -> position.equals(player.tacticalPosition())).toList();
            if (!matches.isEmpty()) return matches.get(random.nextInt(matches.size()));
        }
        List<PlayerSnapshot> any = new ArrayList<>();
        for (PlayerSnapshot player : attackers(spec)) {
            if (!excluded.contains(player.playerId()) && !player.goalkeeper()) any.add(player);
        }
        if (any.isEmpty()) {
            for (PlayerSnapshot player : attackers(spec)) if (!excluded.contains(player.playerId())) any.add(player);
        }
        return any.isEmpty() ? null : any.get(random.nextInt(any.size()));
    }

    static PlayerSnapshot support(MatchMomentSpec spec, Random random, String... positions) {
        return support(spec, random, Set.of(), positions);
    }

    static PlayerSnapshot finalPasser(MatchMomentSpec spec, Random random, Set<Long> used, String... positions) {
        return spec.assister() != null ? spec.assister() : support(spec, random, used, positions);
    }
}

final class LegacyThroughBall extends LegacyBasePattern {
    LegacyThroughBall() { super(PatternId.THROUGH_BALL, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && (s.assister() != null || supportCount(s) >= 1); }
    @Override public double weight(MatchMomentSpec s) { return "ST".equals(s.scorer().tacticalPosition()) ? 1.6 : 1.0; }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        List<PlayScript.Touch> route = new ArrayList<>();
        PlayerSnapshot starter = support(s, r, "MC", "DM", "DC");
        if (starter != null) route.add(touch(starter, point(r, 44, 53, 35, 65), 7, 0));
        if (s.assister() != null) route.add(touch(s.assister(), point(r, 62, 69, 35, 65), 5, 2));
        route.add(touch(s.scorer(), point(r, 84, 89, 43, 57), 5, 1));
        return open(id(), route, between(r, -2, 2));
    }
}

final class LegacyOneTwo extends LegacyBasePattern {
    LegacyOneTwo() { super(PatternId.ONE_TWO, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && s.assister() != null; }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        List<PlayScript.Touch> route = new ArrayList<>();
        PlayerSnapshot starter = support(s, r, "MC", "DM");
        if (starter != null) route.add(touch(starter, point(r, 54, 61, 38, 62), 6, 0));
        double y = between(r, 42, 58);
        route.add(touch(s.scorer(), new PitchPoint(between(r, 73, 77), y), 4, 1));
        route.add(touch(s.assister(), new PitchPoint(between(r, 79, 83), y + (y > 50 ? -8 : 8)), 4, 2));
        route.add(touch(s.scorer(), point(r, 87, 90, 44, 56), 4, 2));
        return open(id(), route, between(r, -2, 2));
    }
}

final class LegacyShortPassingSequence extends LegacyBasePattern {
    LegacyShortPassingSequence() { super(PatternId.SHORT_PASSING_SEQUENCE, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && supportCount(s) >= 2; }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        Set<Long> used = new LinkedHashSet<>();
        List<PlayScript.Touch> route = new ArrayList<>();
        PlayerSnapshot first = support(s, r, used, "DC", "DM", "DL", "DR"); used.add(first.playerId());
        PlayerSnapshot second = support(s, r, used, "MC", "DM", "ML", "MR"); used.add(second.playerId());
        route.add(touch(first, point(r, 27, 34, 36, 64), 6, 0));
        route.add(touch(second, point(r, 45, 53, 32, 68), 5, 1));
        PlayerSnapshot passer = finalPasser(s, r, used, "AMC", "MC", "AML", "AMR");
        if (passer != null && !used.contains(passer.playerId())) route.add(touch(passer, point(r, 65, 73, 33, 67), 5, 2));
        route.add(touch(s.scorer(), point(r, 84, 89, 43, 57), 5, 1));
        return open(id(), route, between(r, -2, 2));
    }
}

final class LegacySwitchOfPlay extends LegacyBasePattern {
    LegacySwitchOfPlay() { super(PatternId.SWITCH_OF_PLAY, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && supportCount(s) >= (s.assister() == null ? 2 : 1); }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        boolean left = r.nextBoolean(); Set<Long> used = new LinkedHashSet<>(); List<PlayScript.Touch> route = new ArrayList<>();
        PlayerSnapshot first = support(s, r, used, "MC", "DM", "DC"); used.add(first.playerId());
        route.add(touch(first, new PitchPoint(between(r, 43, 50), left ? between(r, 17, 28) : between(r, 72, 83)), 7, 0));
        if (s.assister() != null) route.add(touch(s.assister(), new PitchPoint(between(r, 70, 79), left ? between(r, 68, 84) : between(r, 16, 32)), 5, left ? 8 : -8));
        else { PlayerSnapshot wide = support(s, r, used, left ? "MR" : "ML", left ? "AMR" : "AML", "MC"); route.add(touch(wide, new PitchPoint(between(r, 68, 76), left ? between(r, 80, 91) : between(r, 9, 20)), 5, left ? 8 : -8)); }
        route.add(touch(s.scorer(), point(r, 85, 90, 43, 57), 5, 2));
        return open(id(), route, between(r, -2, 2));
    }
}

final class LegacyCounterAttack extends LegacyBasePattern {
    LegacyCounterAttack() { super(PatternId.COUNTER_ATTACK, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && supportCount(s) >= (s.assister() == null ? 2 : 1); }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        Set<Long> used = new LinkedHashSet<>(); List<PlayScript.Touch> route = new ArrayList<>();
        PlayerSnapshot winner = support(s, r, used, "DC", "DM", "DL", "DR"); used.add(winner.playerId());
        route.add(touch(winner, point(r, 17, 25, 36, 64), 3, 0));
        PlayerSnapshot runner = s.assister() != null ? s.assister() : support(s, r, used, "MC", "AMC", "ML", "MR");
        route.add(touch(runner, point(r, 54, 64, 28, 72), 3, 3));
        route.add(touch(s.scorer(), point(r, 85, 90, 42, 58), 4, 2));
        return open(id(), route, between(r, -2, 2));
    }
}

final class LegacyLongBall extends LegacyBasePattern {
    LegacyLongBall() { super(PatternId.LONG_BALL, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && supportCount(s) >= 1; }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        List<PlayScript.Touch> route = new ArrayList<>(); PlayerSnapshot launcher = support(s, r, "DC", "GK", "DL", "DR", "DM");
        route.add(touch(launcher, point(r, 12, 23, 35, 65), 7, 0));
        if (s.assister() != null) route.add(touch(s.assister(), point(r, 58, 66, 35, 65), 4, 5));
        route.add(touch(s.scorer(), point(r, 84, 89, 42, 58), 4, 4));
        return open(id(), route, between(r, -2, 2));
    }
}

final class LegacyOverlapAndCross extends LegacyBasePattern {
    LegacyOverlapAndCross() { super(PatternId.OVERLAP_AND_CROSS, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && supportCount(s) >= (s.assister() == null ? 2 : 1); }
    @Override public double weight(MatchMomentSpec s) { return "ST".equals(s.scorer().tacticalPosition()) ? 1.4 : 1.0; }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        boolean left = r.nextBoolean(); Set<Long> used = new LinkedHashSet<>(); List<PlayScript.Touch> route = new ArrayList<>();
        PlayerSnapshot midfielder = support(s, r, used, "MC", "DM", "AMC"); used.add(midfielder.playerId());
        route.add(touch(midfielder, point(r, 49, 57, 34, 66), 5, 0));
        PlayerSnapshot crosser = s.assister() != null ? s.assister() : support(s, r, used, left ? "DL" : "DR", left ? "ML" : "MR", left ? "AML" : "AMR");
        route.add(touch(crosser, new PitchPoint(between(r, 82, 88), left ? between(r, 8, 16) : between(r, 84, 92)), 4, left ? 4 : -4));
        route.add(touch(s.scorer(), point(r, 88, 92, 45, 55), 4, left ? 7 : -7));
        return open(id(), route, between(r, -2, 2));
    }
}

final class LegacyLowCrossCutback extends LegacyBasePattern {
    LegacyLowCrossCutback() { super(PatternId.LOW_CROSS_CUTBACK, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && supportCount(s) >= (s.assister() == null ? 2 : 1); }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        boolean left = r.nextBoolean(); Set<Long> used = new LinkedHashSet<>(); List<PlayScript.Touch> route = new ArrayList<>();
        PlayerSnapshot first = support(s, r, used, "MC", "AMC", "DM"); used.add(first.playerId());
        route.add(touch(first, point(r, 55, 63, 34, 66), 5, 0));
        PlayerSnapshot crosser = s.assister() != null ? s.assister() : support(s, r, used, left ? "AML" : "AMR", left ? "ML" : "MR", left ? "DL" : "DR");
        route.add(touch(crosser, new PitchPoint(between(r, 91, 94), left ? between(r, 11, 19) : between(r, 81, 89)), 4, left ? 2 : -2));
        route.add(touch(s.scorer(), point(r, 84, 88, 44, 55), 4, 1));
        return open(id(), route, between(r, -1.5, 1.5));
    }
}

final class LegacyLongShot extends LegacyBasePattern {
    LegacyLongShot() { super(PatternId.LONG_SHOT, AnimationPhase.OPEN_PLAY); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && (s.assister() != null || supportCount(s) >= 1); }
    @Override public double weight(MatchMomentSpec s) { return Set.of("MC", "DM", "AMC").contains(s.scorer().tacticalPosition()) ? 1.8 : 0.7; }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        List<PlayScript.Touch> route = new ArrayList<>(); PlayerSnapshot start = support(s, r, "DM", "MC", "DC");
        if (start != null) route.add(touch(start, point(r, 38, 47, 34, 66), 6, 0));
        if (s.assister() != null) route.add(touch(s.assister(), point(r, 57, 65, 36, 64), 5, 1));
        route.add(touch(s.scorer(), point(r, 67, 72, 41, 59), 6, 1));
        return open(id(), route, between(r, -3, 3));
    }
}

final class LegacyCornerCross extends LegacyBasePattern {
    LegacyCornerCross() { super(PatternId.CORNER_CROSS, AnimationPhase.CORNER); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && (s.assister() != null || supportCount(s) >= 1); }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        boolean left = r.nextBoolean(); PitchPoint spot = new PitchPoint(99, left ? 2 : 98);
        PlayerSnapshot taker = finalPasser(s, r, Set.of(), "AMC", "MC", "ML", "MR");
        return setPiece(id(), List.of(touch(taker, spot, 0, 0), touch(s.scorer(), point(r, 89, 93, 46, 54), 4, left ? 7 : -7)), spot, 38, between(r, -1.5, 1.5));
    }
}

final class LegacyShortCorner extends LegacyBasePattern {
    LegacyShortCorner() { super(PatternId.SHORT_CORNER, AnimationPhase.CORNER); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && supportCount(s) >= (s.assister() == null ? 2 : 1); }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        boolean left = r.nextBoolean(); PitchPoint spot = new PitchPoint(99, left ? 2 : 98); Set<Long> used = new LinkedHashSet<>();
        PlayerSnapshot taker = support(s, r, used, "AMC", "MC", left ? "ML" : "MR"); used.add(taker.playerId());
        PlayerSnapshot deliverer = s.assister() != null ? s.assister() : support(s, r, used, "AMC", "MC", left ? "AML" : "AMR");
        List<PlayScript.Touch> route = List.of(touch(taker, spot, 0, 0), touch(deliverer, new PitchPoint(between(r, 86, 90), left ? between(r, 12, 21) : between(r, 79, 88)), 4, 1), touch(s.scorer(), point(r, 88, 92, 45, 55), 4, left ? 5 : -5));
        return setPiece(id(), route, spot, 35, between(r, -1.5, 1.5));
    }
}

final class LegacyDirectFreeKick extends LegacyBasePattern {
    LegacyDirectFreeKick() { super(PatternId.DIRECT_FREE_KICK, AnimationPhase.FREE_KICK); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && s.assister() == null; }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        PitchPoint spot = point(r, 69, 79, 29, 71);
        return setPiece(id(), List.of(touch(s.scorer(), spot, 0, 0)), spot, 48, (spot.y() > 50 ? -1 : 1) * between(r, 3, 6));
    }
}

final class LegacyCrossedFreeKick extends LegacyBasePattern {
    LegacyCrossedFreeKick() { super(PatternId.CROSSED_FREE_KICK, AnimationPhase.FREE_KICK); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && (s.assister() != null || supportCount(s) >= 1); }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        boolean left = r.nextBoolean(); PitchPoint spot = new PitchPoint(between(r, 65, 75), left ? between(r, 17, 33) : between(r, 67, 83));
        PlayerSnapshot taker = finalPasser(s, r, Set.of(), "AMC", "MC", left ? "ML" : "MR");
        return setPiece(id(), List.of(touch(taker, spot, 0, 0), touch(s.scorer(), point(r, 88, 92, 44, 56), 4, left ? 6 : -6)), spot, 42, between(r, -1.5, 1.5));
    }
}

final class LegacyPenalty extends LegacyBasePattern {
    LegacyPenalty() { super(PatternId.PENALTY, AnimationPhase.PENALTY, EnumSet.of(AnimationOutcome.GOAL, AnimationOutcome.SAVE, AnimationOutcome.MISS)); }
    @Override public boolean supports(MatchMomentSpec s) { return super.supports(s) && s.assister() == null; }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        PitchPoint spot = new PitchPoint(88, 50);
        return setPiece(id(), List.of(touch(s.scorer(), spot, 0, 0)), spot, 62, between(r, -1, 1));
    }
}

final class LegacySafeFallback implements PlayPattern {
    @Override public PatternId id() { return PatternId.SAFE_FALLBACK; }
    @Override public AnimationPhase phase() { return AnimationPhase.OPEN_PLAY; }
    @Override public Set<AnimationOutcome> supportedOutcomes() { return EnumSet.allOf(AnimationOutcome.class); }
    @Override public boolean supports(MatchMomentSpec spec) { return true; }
    @Override public PlayScript create(MatchMomentSpec s, Random r) {
        List<PlayScript.Touch> route = new ArrayList<>();
        if (s.assister() != null) route.add(new PlayScript.Touch(s.assisterId(), new PitchPoint(74, 50), 7, 0));
        route.add(new PlayScript.Touch(s.scorerId(), new PitchPoint(84, 50), 6, 1));
        return new PlayScript(id(), route, null, 0, 0);
    }
}
