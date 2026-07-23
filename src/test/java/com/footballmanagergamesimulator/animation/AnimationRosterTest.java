package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

/** Any roster size from 1..11 per team, including red-card reductions and substitutions. */
class AnimationRosterTest {
    private static final long HOME = 10;
    private static final long AWAY = 20;
    private static final long SCORER = HOME * 100 + 1;
    private final AnimationDirector director = new AnimationDirector();
    private final AnimationInvariantValidator validator = new AnimationInvariantValidator(AnimationPhysicsProfile.defaults());

    private static final String[] ATTACK_POS = {"ST", "AMC", "MC", "WBL", "WBR", "DC", "ML", "MR", "DM", "DL", "DR"};
    private static final String[] DEFEND_POS = {"GK", "DC", "DL", "DR", "DM", "MC", "WBL", "WBR", "ML", "MR", "ST"};

    private static PlayerSnapshot player(long id, long team, String pos) {
        return new PlayerSnapshot(id, team, "P" + id, (int) (id % 99), pos, "ROLE_" + pos, 60);
    }

    private static List<PlayerSnapshot> side(long team, String[] pos, int count) {
        List<PlayerSnapshot> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(player(team * 100 + i + 1, team, pos[i]));
        return list;
    }

    private MatchMomentSpec spec(int attackers, int defenders, Long assist, long baseId) {
        List<PlayerSnapshot> players = new ArrayList<>(side(HOME, ATTACK_POS, attackers));
        players.addAll(side(AWAY, DEFEND_POS, defenders));
        return new MatchMomentSpec("CTIM:" + baseId, 0, 42L, AnimationDirector.CURRENT_GENERATOR_VERSION,
                50, 2, MatchPeriod.SECOND_HALF, HOME, AWAY, HOME, AnimationPhase.OPEN_PLAY,
                AnimationOutcome.GOAL, SCORER, assist, players, null);
    }

    @Test void everyRosterSizeFromOneToElevenAnimatesValidly() {
        for (int attackers = 1; attackers <= 11; attackers++) {
            for (int defenders = 1; defenders <= 11; defenders++) {
                Long assist = attackers >= 2 ? HOME * 100 + 2 : null;
                for (AnimationOutcome outcome : AnimationOutcome.values()) {
                    MatchMomentSpec spec = new MatchMomentSpec("CTIM:R", 0, 42L,
                            AnimationDirector.CURRENT_GENERATOR_VERSION, 50, 2, MatchPeriod.FIRST_HALF,
                            HOME, AWAY, HOME, AnimationPhase.OPEN_PLAY, outcome, SCORER, assist,
                            fullRoster(attackers, defenders), null);
                    AnimationReplay replay = director.direct(spec).replay();
                    assertTrue(validator.validate(replay, spec).isEmpty(),
                            attackers + "v" + defenders + "/" + outcome);
                    assertEquals(attackers + defenders, replay.players().size());
                }
            }
        }
    }

    private List<PlayerSnapshot> fullRoster(int attackers, int defenders) {
        List<PlayerSnapshot> players = new ArrayList<>(side(HOME, ATTACK_POS, attackers));
        players.addAll(side(AWAY, DEFEND_POS, defenders));
        return players;
    }

    @Test void redCardReductionStillAnimates() {
        // Eleven vs a team reduced to nine by two dismissals.
        MatchMomentSpec spec = spec(11, 9, HOME * 100 + 2, 7);
        AnimationReplay replay = director.direct(spec).replay();
        assertTrue(validator.validate(replay, spec).isEmpty());
        assertEquals(20, replay.players().size());
    }

    @Test void substitutionChangesTheSetButStaysValid() {
        MatchMomentSpec before = spec(11, 11, HOME * 100 + 2, 9);
        // Replace one attacker id (a substitution) - different set, still valid.
        List<PlayerSnapshot> after = new ArrayList<>(before.playersOnPitch());
        after.removeIf(p -> p.playerId() == HOME * 100 + 5);
        after.add(player(HOME * 100 + 90, HOME, "AML"));
        MatchMomentSpec subbed = new MatchMomentSpec("CTIM:9", 0, 42L,
                AnimationDirector.CURRENT_GENERATOR_VERSION, 50, 2, MatchPeriod.SECOND_HALF, HOME, AWAY, HOME,
                AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, SCORER, HOME * 100 + 2, after, null);
        assertTrue(validator.validate(director.direct(subbed).replay(), subbed).isEmpty());
    }

    @Test void smallRosterIsStillOrderIndependent() {
        MatchMomentSpec ordered = spec(3, 3, HOME * 100 + 2, 11);
        List<PlayerSnapshot> shuffled = new ArrayList<>(ordered.playersOnPitch());
        Collections.shuffle(shuffled, new Random(3));
        MatchMomentSpec reordered = new MatchMomentSpec("CTIM:11", 0, 42L,
                AnimationDirector.CURRENT_GENERATOR_VERSION, 50, 2, MatchPeriod.SECOND_HALF, HOME, AWAY, HOME,
                AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, SCORER, HOME * 100 + 2, shuffled, null);
        AnimationReplay a = director.direct(ordered).replay();
        AnimationReplay b = director.direct(reordered).replay();
        assertEquals(a.fingerprint(), b.fingerprint());
        assertEquals(a, b);
    }
}
