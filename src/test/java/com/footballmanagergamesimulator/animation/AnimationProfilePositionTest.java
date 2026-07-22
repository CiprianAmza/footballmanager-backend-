package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * direct() is total across the whole accepted profile domain crossed with participant position,
 * roster size, period, phase and outcome — including deep scorers and a goalkeeper assist at the
 * slowest accepted profile, where the safe fallback previously could not fit.
 */
class AnimationProfilePositionTest {
    private static final long HOME = 10;
    private static final long AWAY = 20;

    // Boundary profiles: default, strict, slowest accepted, high-acceleration slow step, fast.
    private static final AnimationPhysicsProfile[] PROFILES = {
            AnimationPhysicsProfile.defaults(),
            new AnimationPhysicsProfile(0.6, 0.25, 4.0),
            new AnimationPhysicsProfile(0.3, 0.1, 1.0),
            new AnimationPhysicsProfile(0.3, 100.0, 1.0),
            new AnimationPhysicsProfile(2.0, 0.6, 6.0)
    };
    // Scorer/assister positions of varied depth, including a goalkeeper as the (deep) assister.
    private static final String[] SCORER_POS = {"ST", "DC", "MC", "AMR"};
    private static final String[] ASSISTER_POS = {"GK", "DC", "AML", "ST"};

    private static PlayerSnapshot player(long id, long team, String pos) {
        return new PlayerSnapshot(id, team, "P" + id, (int) (id % 99), pos, "ROLE_" + pos, 60);
    }

    private static List<PlayerSnapshot> defenders(int m) {
        String[] pos = {"GK", "DC", "DL", "DR", "DM", "MC", "WBL", "WBR", "ML", "MR", "ST"};
        List<PlayerSnapshot> d = new ArrayList<>();
        for (int i = 0; i < m; i++) d.add(player(AWAY * 100 + i + 1, AWAY, pos[i]));
        return d;
    }

    private static List<PlayerSnapshot> attackers(String scorerPos, String assisterPos, int n) {
        String[] filler = {"MC", "DM", "AMC", "DL", "DR", "ML", "MR", "AML", "AMR"};
        List<PlayerSnapshot> a = new ArrayList<>();
        a.add(player(HOME * 100 + 1, HOME, scorerPos)); // scorer
        if (n >= 2) a.add(player(HOME * 100 + 2, HOME, assisterPos == null ? "MC" : assisterPos)); // assister slot
        for (int i = 2; i < n; i++) a.add(player(HOME * 100 + i + 1, HOME, filler[(i - 2) % filler.length]));
        return a;
    }

    @Test void directIsTotalAcrossProfilePositionRosterPeriodPhaseOutcome() {
        int checked = 0;
        for (AnimationPhysicsProfile profile : PROFILES) {
            AnimationDirector director = new AnimationDirector(profile);
            AnimationInvariantValidator validator = new AnimationInvariantValidator(profile);
            for (String scorerPos : SCORER_POS)
                for (String assisterPos : ASSISTER_POS)
                    for (Long useAssist : new Long[]{HOME * 100 + 2, null})
                        for (MatchPeriod period : MatchPeriod.values())
                            for (AnimationPhase phase : new AnimationPhase[]{AnimationPhase.OPEN_PLAY, AnimationPhase.CORNER})
                                for (AnimationOutcome outcome : AnimationOutcome.values()) {
                                    int attackers = useAssist == null ? 1 : 4;
                                    List<PlayerSnapshot> players = new ArrayList<>(attackers(scorerPos, assisterPos, attackers));
                                    players.addAll(defenders(useAssist == null ? 3 : 6));
                                    MatchMomentSpec spec = new MatchMomentSpec("CTIM:PP", 0, 314L,
                                            AnimationDirector.CURRENT_GENERATOR_VERSION, minuteFor(period), 2, period,
                                            HOME, AWAY, HOME, phase, outcome, HOME * 100 + 1, useAssist, players, null);
                                    AnimationReplay replay = assertDoesNotThrow(() -> director.direct(spec).replay(),
                                            profile.maxPlayerStep() + "/" + scorerPos + "/" + assisterPos + "/" + period
                                                    + "/" + phase + "/" + outcome + "/assist=" + useAssist);
                                    assertTrue(validator.validate(replay, spec).isEmpty(),
                                            "invalid: " + scorerPos + "/" + assisterPos + "/" + phase + "/" + outcome);
                                    checked++;
                                }
        }
        assertTrue(checked > 2000, "matrix size " + checked);
    }

    @Test void goalkeeperAssistAtSlowestProfileNeverThrows() {
        AnimationPhysicsProfile slowest = new AnimationPhysicsProfile(0.3, 0.1, 1.0);
        AnimationDirector director = new AnimationDirector(slowest);
        AnimationInvariantValidator validator = new AnimationInvariantValidator(slowest);
        List<PlayerSnapshot> players = new ArrayList<>();
        players.add(player(HOME * 100 + 1, HOME, "ST"));      // scorer
        players.add(player(HOME * 100 + 2, HOME, "GK"));      // goalkeeper assist — deepest possible
        players.addAll(defenders(3));
        for (MatchPeriod period : MatchPeriod.values())
            for (AnimationPhase phase : AnimationPhase.values())
                for (AnimationOutcome outcome : AnimationOutcome.values()) {
                    MatchMomentSpec spec = new MatchMomentSpec("CTIM:GK", 0, 7L,
                            AnimationDirector.CURRENT_GENERATOR_VERSION, minuteFor(period), 2, period,
                            HOME, AWAY, HOME, phase, outcome, HOME * 100 + 1, HOME * 100 + 2, players, null);
                    AnimationReplay replay = director.direct(spec).replay();
                    assertTrue(validator.validate(replay, spec).isEmpty());
                }
    }

    private static int minuteFor(MatchPeriod period) {
        return switch (period) {
            case FIRST_HALF -> 20;
            case SECOND_HALF -> 70;
            case EXTRA_TIME_FIRST_HALF -> 95;
            case EXTRA_TIME_SECOND_HALF -> 115;
        };
    }
}
