package com.footballmanagergamesimulator.animation.pattern;

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
import java.util.List;
import java.util.Random;
import java.util.Set;

abstract class BasePattern implements PlayPattern {
    private final PatternId id;
    private final AnimationPhase phase;
    private final Set<AnimationOutcome> outcomes;

    BasePattern(PatternId id, AnimationPhase phase, Set<AnimationOutcome> outcomes) {
        this.id = id;
        this.phase = phase;
        this.outcomes = Set.copyOf(outcomes);
    }

    BasePattern(PatternId id, AnimationPhase phase) {
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

    static int supportCount(MatchMomentSpec spec) {
        int count = 0;
        for (PlayerSnapshot player : spec.attackers()) {
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
            List<PlayerSnapshot> matches = spec.attackers().stream()
                    .filter(player -> !excluded.contains(player.playerId()))
                    .filter(player -> position.equals(player.tacticalPosition())).toList();
            if (!matches.isEmpty()) return matches.get(random.nextInt(matches.size()));
        }
        List<PlayerSnapshot> any = new ArrayList<>();
        for (PlayerSnapshot player : spec.attackers()) {
            if (!excluded.contains(player.playerId()) && !player.goalkeeper()) any.add(player);
        }
        if (any.isEmpty()) {
            for (PlayerSnapshot player : spec.attackers()) if (!excluded.contains(player.playerId())) any.add(player);
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
