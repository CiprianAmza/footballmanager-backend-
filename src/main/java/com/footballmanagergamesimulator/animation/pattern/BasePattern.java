package com.footballmanagergamesimulator.animation.pattern;

import com.footballmanagergamesimulator.animation.AnimationPhase;
import com.footballmanagergamesimulator.animation.Choreography;
import com.footballmanagergamesimulator.animation.Choreography.ChainStep;
import com.footballmanagergamesimulator.animation.MatchMomentSpec;
import com.footballmanagergamesimulator.animation.PlayPattern;
import com.footballmanagergamesimulator.animation.PlayerSnapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Shared helpers for the pattern library. Patterns only shape the phase in
 * canonical orientation (attack → x=100); outcome, scorer, assister and
 * minute always come from the spec and are never altered here.
 */
abstract class BasePattern implements PlayPattern {

    private final String id;
    private final AnimationPhase phase;

    BasePattern(String id, AnimationPhase phase) {
        this.id = id;
        this.phase = phase;
    }

    @Override
    public String id() {
        return id;
    }

    /** Default support: matching phase; every outcome; subclasses tighten. */
    @Override
    public boolean supports(MatchMomentSpec spec) {
        return spec.phase() == phase;
    }

    // ==================== HELPERS ====================

    static double jr(Random rng, double min, double max) {
        return min + rng.nextDouble() * (max - min);
    }

    static ChainStep step(PlayerSnapshot p, double x, double y, int dwell, double curve) {
        return new ChainStep(p.playerId(), x, y, dwell, curve);
    }

    static ChainStep step(long playerId, double x, double y, int dwell, double curve) {
        return new ChainStep(playerId, x, y, dwell, curve);
    }

    /**
     * Deterministically pick a teammate, preferring the given tactical
     * positions in order; falls back to any outfielder, then anyone. Never
     * returns the scorer or assister (they have fixed chain roles), unless
     * literally nobody else is on the pitch — then returns {@code null}.
     */
    static PlayerSnapshot pickSupport(MatchMomentSpec spec, Random rng, String... positions) {
        return pickSupport(spec, rng, Set.of(), positions);
    }

    /** Same, additionally excluding already-used chain members. */
    static PlayerSnapshot pickSupport(MatchMomentSpec spec, Random rng, Set<Long> used, String... positions) {
        Set<Long> excluded = new HashSet<>(used);
        excluded.add(spec.scorerId());
        if (spec.assisterId() != null) excluded.add(spec.assisterId());
        List<PlayerSnapshot> pool = spec.attackingPlayers();

        for (String pos : positions) {
            List<PlayerSnapshot> match = new ArrayList<>();
            for (PlayerSnapshot p : pool) {
                if (!excluded.contains(p.playerId()) && pos.equals(p.position())) match.add(p);
            }
            if (!match.isEmpty()) return match.get(rng.nextInt(match.size()));
        }
        List<PlayerSnapshot> outfield = new ArrayList<>();
        for (PlayerSnapshot p : pool) {
            if (!excluded.contains(p.playerId()) && !p.isGoalkeeper()) outfield.add(p);
        }
        if (!outfield.isEmpty()) return outfield.get(rng.nextInt(outfield.size()));
        for (PlayerSnapshot p : pool) if (!excluded.contains(p.playerId())) return p;
        return null;
    }

    /** True when at least {@code min} attackers besides scorer/assister exist. */
    static boolean hasSupport(MatchMomentSpec spec, int min) {
        int others = 0;
        for (PlayerSnapshot p : spec.attackingPlayers()) {
            if (p.playerId() != spec.scorerId()
                    && (spec.assisterId() == null || p.playerId() != spec.assisterId())) others++;
        }
        return others >= min;
    }

    /** The deliverer of the final pass: the canonical assister when present. */
    static PlayerSnapshot finalPasser(MatchMomentSpec spec, Random rng, String... fallbackPositions) {
        if (spec.assister() != null) return spec.assister();
        return pickSupport(spec, rng, fallbackPositions);
    }

    static Choreography openPlay(String patternId, List<ChainStep> chain, double shotCurve) {
        return new Choreography(patternId, List.copyOf(chain), null, 0, shotCurve);
    }

    static Choreography setPiece(String patternId, List<ChainStep> chain,
                                 double[] spot, int preKick, double shotCurve) {
        return new Choreography(patternId, List.copyOf(chain), spot, preKick, shotCurve);
    }
}
