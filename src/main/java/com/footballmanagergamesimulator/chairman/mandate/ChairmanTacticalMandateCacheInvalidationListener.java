package com.footballmanagergamesimulator.chairman.mandate;

import com.footballmanagergamesimulator.service.MatchRoundSimulator;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ChairmanTacticalMandateCacheInvalidationListener {
    private final MatchRoundSimulator matchRoundSimulator;

    public ChairmanTacticalMandateCacheInvalidationListener(@Lazy MatchRoundSimulator matchRoundSimulator) {
        this.matchRoundSimulator = matchRoundSimulator;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidate(ChairmanTacticalMandateChangedEvent event) {
        matchRoundSimulator.invalidateChairmanMandateCaches(event.teamId());
    }
}
