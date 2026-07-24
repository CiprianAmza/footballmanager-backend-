package com.footballmanagergamesimulator.chairman.mandate;

import com.footballmanagergamesimulator.service.MatchRoundSimulator;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ChairmanTacticalMandateCacheInvalidationListenerTest {
    @Test
    void invalidatesOnlyTheChangedTeamThroughTheAfterCommitListenerSeam() {
        MatchRoundSimulator simulator = mock(MatchRoundSimulator.class);
        ChairmanTacticalMandateCacheInvalidationListener listener =
                new ChairmanTacticalMandateCacheInvalidationListener(simulator);

        listener.invalidate(new ChairmanTacticalMandateChangedEvent(42L));

        verify(simulator).invalidateChairmanMandateCaches(42L);
    }
}
