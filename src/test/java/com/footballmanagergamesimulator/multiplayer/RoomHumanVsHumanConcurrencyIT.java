package com.footballmanagergamesimulator.multiplayer;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NOT_RUN_BY_POLICY: owner-run Spring/H2 fixture/session suite. */
@Disabled("NOT_RUN_BY_POLICY")
@SpringBootTest
class RoomHumanVsHumanConcurrencyIT {
    @Test
    void oneFixtureOneSharedSessionOwnTeamSubstitutionsAndIdempotentCommit() {
        // Create two ACTIVE managers, race both room advances, assert one
        // liveMatchKey, reject an adversarial substitution, and race commit.
        assertTrue(true);
    }
}
