package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static com.footballmanagergamesimulator.animation.AnimationFixtures.spec;
import static org.junit.jupiter.api.Assertions.*;

class AnimationQueueTest {
    private final AnimationDirector director = new AnimationDirector();

    @Test void twoGoalsInMinute63RemainDistinctAndOrderedBySlot() {
        AnimationReplay slot5 = director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 5, 63, null)).replay();
        AnimationReplay slot4 = director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 4, 63, null)).replay();
        AnimationQueue queue = new AnimationQueue(); queue.enqueue(slot5); queue.enqueue(slot4);
        List<AnimationReplay> ordered = queue.ordered();
        assertEquals(2, ordered.size()); assertEquals(4, ordered.get(0).slotIndex()); assertEquals(5, ordered.get(1).slotIndex());
        assertEquals(63, ordered.get(0).minute()); assertEquals(63, ordered.get(1).minute());
    }

    @Test void playbackSortsByMinuteThenSlot() {
        AnimationQueue queue = new AnimationQueue();
        queue.enqueue(director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 7, 80, null)).replay());
        queue.enqueue(director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 2, 12, null)).replay());
        queue.enqueue(director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 1, 80, null)).replay());
        assertEquals(List.of(2, 1, 7), queue.ordered().stream().map(AnimationReplay::slotIndex).toList());
    }

    @Test void sameIdentityIsIdempotentButCannotChangeFrames() {
        AnimationQueue queue = new AnimationQueue(); AnimationReplay replay = director.direct(spec()).replay();
        queue.enqueue(replay); queue.enqueue(replay); assertEquals(1, queue.size());
        assertThrows(IllegalArgumentException.class, () -> queue.enqueue(
                director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 31, null)).replay()));
    }

    @Test void sameIdentityCannotChangeCanonicalFactsWhileKeepingFrames() {
        AnimationQueue queue = new AnimationQueue();
        AnimationReplay replay = director.direct(spec()).replay();
        queue.enqueue(replay);

        AnimationReplay alteredMinute = new AnimationReplay(
                replay.fixtureKey(), replay.slotIndex(), replay.minute() + 1, replay.firstHalfStoppage(),
                replay.period(), replay.scoringTeamId(), replay.defendingTeamId(), replay.homeTeamId(),
                replay.phase(), replay.outcome(),
                replay.pattern(), replay.renderedWithVersion(), replay.scorerId(), replay.assisterId(),
                replay.homeAttacksRight(), replay.scoringTeamAttacksRight(), replay.players(), replay.frames(), replay.events());

        assertThrows(IllegalArgumentException.class, () -> queue.enqueue(alteredMinute));
        assertSame(replay, queue.get(replay.key()));
    }
}
