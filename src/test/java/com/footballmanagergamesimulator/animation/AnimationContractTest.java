package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static com.footballmanagergamesimulator.animation.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AnimationContractTest {
    @Test void specDefensivelyCopiesTheSnapshot() {
        List<PlayerSnapshot> players = new ArrayList<>(side(100, HOME, "Home"));
        players.addAll(side(200, AWAY, "Away"));
        MatchMomentSpec moment = new MatchMomentSpec(FIXTURE, 0, PLAN_SEED, 1, 30, 0,
                HOME, AWAY, HOME, AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL,
                SCORER, ASSISTER, players, null);
        players.clear();
        assertEquals(22, moment.playersOnPitch().size());
        assertThrows(UnsupportedOperationException.class, () -> moment.playersOnPitch().clear());
    }

    @Test void thirdTeamAndDuplicatePlayersAreRejected() {
        List<PlayerSnapshot> players = new ArrayList<>(side(100, HOME, "Home"));
        players.addAll(side(200, AWAY, "Away"));
        players.add(new PlayerSnapshot(999, 30, "Intruder", 1, "MC", "CM", 60));
        assertThrows(IllegalArgumentException.class, () -> new MatchMomentSpec(FIXTURE, 0, PLAN_SEED, 1,
                30, 0, HOME, AWAY, HOME, AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL,
                SCORER, null, players, null));
    }

    @Test void invalidRecipeSeedIsRejected() {
        MatchMomentSpec s = spec();
        assertThrows(IllegalArgumentException.class, () -> new AnimationRecipe(s.fixtureKey(), s.slotIndex(),
                s.planSeed(), 123, s.generatorVersion(), PatternId.SAFE_FALLBACK, s.minute(),
                s.firstHalfStoppage(), s.scoringTeamId(), s.defendingTeamId(), s.homeTeamId(),
                s.phase(), s.outcome(), s.scorerId(), s.assisterId(), s.playersOnPitch(),
                s.tacticalContext(), AnimationPhysicsProfile.defaults()));
    }

    @Test void unavailableVersionFailsExplicitly() {
        MatchMomentSpec s = spec();
        MatchMomentSpec unavailable = new MatchMomentSpec(s.fixtureKey(), s.slotIndex(), s.planSeed(), 99,
                s.minute(), s.firstHalfStoppage(), s.scoringTeamId(), s.defendingTeamId(), s.homeTeamId(),
                s.phase(), s.outcome(), s.scorerId(), s.assisterId(), s.playersOnPitch(), s.tacticalContext());
        assertThrows(UnsupportedAnimationVersionException.class, () -> new AnimationDirector().direct(unavailable));
    }

    @Test void impossibleSpecialisedCombinationUsesSafeFallbackAndKeepsFacts() {
        MatchMomentSpec s = spec(AnimationPhase.PENALTY, AnimationOutcome.BLOCKED, 4, 63, ASSISTER);
        AnimationReplay replay = new AnimationDirector().direct(s).replay();
        assertEquals(PatternId.SAFE_FALLBACK, replay.pattern());
        assertEquals(s.outcome(), replay.outcome()); assertEquals(s.minute(), replay.minute());
        assertEquals(s.scorerId(), replay.scorerId()); assertEquals(s.assisterId(), replay.assisterId());
    }
}
