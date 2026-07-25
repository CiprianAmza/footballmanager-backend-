package com.footballmanagergamesimulator.multiplayer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoomContinueCoordinatorTest {
    private final RoomContinueCoordinator coordinator = new RoomContinueCoordinator(null, null, null);

    @Test void twoPlayersZeroOfTwoNeedsOneVoteAtDefaultThreshold() {
        GameRoom room = room(50);
        assertEquals(1, coordinator.requiredVotes(room, 2));
    }

    @Test void fourPlayersOneOfFourStillNeedsTwoVotes() {
        GameRoom room = room(50);
        assertEquals(2, coordinator.requiredVotes(room, 4));
    }

    @Test void thresholdRoundsUpAndNeverFallsBelowOne() {
        assertEquals(3, coordinator.requiredVotes(room(51), 4));
        assertEquals(1, coordinator.requiredVotes(room(100), 0));
    }

    private GameRoom room(int threshold) { GameRoom room = new GameRoom(); room.setContinueThresholdPercent(threshold); return room; }
}
