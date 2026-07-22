package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static com.footballmanagergamesimulator.animation.AnimationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deep immutability, stable identity, same-minute collisions and exact recipe round-trip. */
class AnimationImmutabilityTest {
    private final AnimationDirector director = new AnimationDirector();
    private final AnimationRecipeCodec codec = new AnimationRecipeCodec();

    @Test void replayCollectionsAreDeeplyUnmodifiable() {
        AnimationReplay replay = director.direct(spec()).replay();
        assertThrows(UnsupportedOperationException.class, () -> replay.frames().clear());
        assertThrows(UnsupportedOperationException.class, () -> replay.events().clear());
        assertThrows(UnsupportedOperationException.class, () -> replay.players().clear());
        assertThrows(UnsupportedOperationException.class, () -> replay.frames().get(0).positions().clear());
    }

    @Test void recipeSnapshotIsUnmodifiable() {
        AnimationRecipe recipe = director.direct(spec()).recipe();
        assertThrows(UnsupportedOperationException.class, () -> recipe.playersOnPitch().clear());
    }

    @Test void mutatingTheInputSnapshotDoesNotAffectAGeneratedReplay() {
        List<PlayerSnapshot> players = new ArrayList<>(side(100, HOME, "Home"));
        players.addAll(side(200, AWAY, "Away"));
        MatchMomentSpec spec = new MatchMomentSpec(FIXTURE, 0, PLAN_SEED,
                AnimationDirector.CURRENT_GENERATOR_VERSION, 30, 2, MatchPeriod.FIRST_HALF,
                HOME, AWAY, HOME, AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, SCORER, ASSISTER, players, null);
        AnimationReplay first = director.direct(spec).replay();
        players.clear();
        AnimationReplay second = director.direct(spec).replay();
        assertExact(first, second);
    }

    @Test void twoGoalsInTheSameMinuteAreDistinctBySlot() {
        AnimationQueue queue = new AnimationQueue();
        AnimationReplay a = director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 3, 63, null)).replay();
        AnimationReplay b = director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 4, 63, ASSISTER)).replay();
        queue.enqueue(a);
        queue.enqueue(b);
        assertEquals(2, queue.size());
        assertNotEquals(a.fingerprint(), b.fingerprint());
        assertEquals(List.of(3, 4), queue.ordered().stream().map(AnimationReplay::slotIndex).toList());
    }

    @Test void recipeJsonRoundTripIsExact() {
        for (AnimationOutcome outcome : AnimationOutcome.values()) for (Long assist : new Long[]{ASSISTER, null}) {
            var directed = director.direct(spec(AnimationPhase.OPEN_PLAY, outcome, 1, 48, assist));
            AnimationRecipe decoded = codec.decode(codec.encode(directed.recipe()));
            assertEquals(directed.recipe(), decoded);
            assertExact(directed.replay(), director.replay(decoded));
        }
    }
}
