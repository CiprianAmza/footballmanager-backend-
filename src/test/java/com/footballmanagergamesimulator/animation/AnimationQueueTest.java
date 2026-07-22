package com.footballmanagergamesimulator.animation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.footballmanagergamesimulator.animation.AnimationTestFixtures.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Two goals in the same minute stay two distinct, ordered animations. */
class AnimationQueueTest {

    private final AnimationDirector director = new AnimationDirector();

    @Test
    void twoGoalsInMinute63AreKeptSeparateAndOrderedBySlotIndex() {
        AnimationReplay slot4 = director.direct(
                spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 4, 63, null)).replay();
        AnimationReplay slot5 = director.direct(
                spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 5, 63, null)).replay();

        AnimationQueue queue = new AnimationQueue();
        queue.enqueue(slot5); // inserted out of order on purpose
        queue.enqueue(slot4);

        assertEquals(2, queue.size(), "same minute must NOT overwrite");
        List<AnimationReplay> ordered = queue.ordered();
        assertEquals(4, ordered.get(0).slotIndex());
        assertEquals(5, ordered.get(1).slotIndex());
        assertEquals(63, ordered.get(0).minute(), "minute is never shifted");
        assertEquals(63, ordered.get(1).minute(), "minute is never shifted");
        assertNotEquals(ordered.get(0).fingerprint(), ordered.get(1).fingerprint(),
                "the two goals are distinct animations");
        assertEquals(2, queue.atMinute(63).size());
    }

    @Test
    void playbackOrderIsMinuteThenSlotIndex() {
        AnimationQueue queue = new AnimationQueue();
        queue.enqueue(director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 7, 80, null)).replay());
        queue.enqueue(director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 2, 12, null)).replay());
        queue.enqueue(director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 1, 80, null)).replay());

        List<AnimationReplay> ordered = queue.ordered();
        assertEquals(12, ordered.get(0).minute());
        assertEquals(1, ordered.get(1).slotIndex());
        assertEquals(7, ordered.get(2).slotIndex());
    }

    @Test
    void reEnqueueSameKeyReplacesInsteadOfDuplicating() {
        AnimationQueue queue = new AnimationQueue();
        AnimationReplay r = director.direct(spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 30, null)).replay();
        queue.enqueue(r);
        queue.enqueue(r);
        assertEquals(1, queue.size());
    }

    @Test
    void sameIdentityCannotBeReusedForDifferentCanonicalFacts() {
        AnimationQueue queue = new AnimationQueue();
        queue.enqueue(director.direct(
                spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 30, null)).replay());

        assertThrows(IllegalArgumentException.class, () -> queue.enqueue(director.direct(
                spec(AnimationPhase.OPEN_PLAY, AnimationOutcome.GOAL, 0, 31, null)).replay()));
    }
}
