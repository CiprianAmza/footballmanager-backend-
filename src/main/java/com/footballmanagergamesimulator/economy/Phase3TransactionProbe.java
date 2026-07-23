package com.footballmanagergamesimulator.economy;

import org.springframework.stereotype.Component;

/** Test seam for proving rollback at every multi-ledger transaction stage. */
@Component
public class Phase3TransactionProbe {
    public void checkpoint(String ignoredStage) {
        // Production is deliberately a no-op. Tests may spy this bean and throw.
    }
}
