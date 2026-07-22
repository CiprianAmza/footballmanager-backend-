package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.ASSISTER_ID;
import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.ATTACKING_TEAM;
import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.DEFENDING_TEAM;
import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.FIXTURE_KEY;
import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.HOME_TEAM;
import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.PLAN_SEED;
import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.SCORER_ID;
import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.eleven;
import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Canonical input and persisted recipe are immutable, validated truth boundaries. */
class AnimationContractTest {

    @Test
    void matchMomentSpecDefensivelyCopiesPlayerSnapshots() {
        List<PlayerSnapshot> attackers = new ArrayList<>(eleven(100, "Att"));
        List<PlayerSnapshot> defenders = new ArrayList<>(eleven(200, "Def"));
        MatchMomentSpec moment = moment(attackers, defenders, 1);

        attackers.clear();
        defenders.clear();
        assertEquals(11, moment.attackingPlayers().size());
        assertEquals(11, moment.defendingPlayers().size());
        assertThrows(UnsupportedOperationException.class,
                () -> moment.attackingPlayers().add(eleven(300, "Other").get(0)));
    }

    @Test
    void invalidCanonicalIdentityAndTeamsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new AnimationKey(FIXTURE_KEY, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new MatchMomentSpec(FIXTURE_KEY, 0, PLAN_SEED, 1,
                        30, 2, ATTACKING_TEAM, ATTACKING_TEAM, HOME_TEAM,
                        AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL,
                        SCORER_ID, null, eleven(100, "Att"), eleven(200, "Def"), null));
    }

    @Test
    void recipeRejectsASeedThatDoesNotMatchCanonicalIdentity() {
        MatchMomentSpec s = spec();
        assertThrows(IllegalArgumentException.class, () -> new AnimationRecipe(
                s.fixtureKey(), s.slotIndex(), s.planSeed(), 123L, s.generatorVersion(),
                "SAFE_FALLBACK", s.phase(), s.outcome(), s.minute(),
                s.firstHalfStoppage(), s.scoringTeamId(), s.defendingTeamId(),
                s.homeTeamId(), s.scorerId(), s.assisterId(),
                AnimationMotionLimits.defaults(),
                s.attackingPlayers(), s.defendingPlayers(), s.tacticalContext()));
    }

    @Test
    void unsupportedGeneratorVersionFailsExplicitly() {
        AnimationDirector director = new AnimationDirector();
        MatchMomentSpec unsupported = moment(
                new ArrayList<>(eleven(100, "Att")),
                new ArrayList<>(eleven(200, "Def")), 99);
        assertThrows(UnsupportedAnimationVersionException.class,
                () -> director.direct(unsupported));
    }

    @Test
    void unsupportedCanonicalCombinationUsesSafeFallbackWithoutChangingFacts() {
        MatchMomentSpec s = spec(AnimationPhase.PENALTY, AnimationOutcome.BLOCKED,
                4, 63, ASSISTER_ID);
        AnimationReplay replay = new AnimationDirector().direct(s).replay();
        assertEquals("SAFE_FALLBACK", replay.patternId());
        assertEquals(s.outcome(), replay.outcome());
        assertEquals(s.minute(), replay.minute());
        assertEquals(s.scorerId(), replay.scorerId());
        assertEquals(s.assisterId(), replay.assisterId());
    }

    private static MatchMomentSpec moment(List<PlayerSnapshot> attackers,
                                          List<PlayerSnapshot> defenders,
                                          int generatorVersion) {
        return new MatchMomentSpec(FIXTURE_KEY, 0, PLAN_SEED, generatorVersion,
                30, 2, ATTACKING_TEAM, DEFENDING_TEAM, HOME_TEAM,
                AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL,
                SCORER_ID, ASSISTER_ID, attackers, defenders,
                new TacticalContext("attacking", "defensive"));
    }
}
