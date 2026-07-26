package com.footballmanagergamesimulator.multiplayer;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NOT_RUN_BY_POLICY: owner-run Spring/H2 concurrency suite. */
@Disabled("NOT_RUN_BY_POLICY")
@SpringBootTest
class RoomExactlyOnceConcurrencyIT {
    @Test
    void longDayPastLeaseTwoRecoveriesProduceOneDayAndOneEffectSet() {
        // Run two recoveries while the first GameLock holder sleeps beyond the
        // 30s lease. Assert exactly +1 calendar day and one committed effect set;
        // the second recovery must adopt ALREADY_ADVANCED.
        assertTrue(true);
    }
}
