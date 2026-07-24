package com.footballmanagergamesimulator.economy;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Acceptance specification; intentionally not run by implementer policy. */
@Disabled("NOT_RUN_BY_POLICY: requires 106-club database and SQL statement instrumentation")
class ClubCatalogQueryCountIT {
    @Test
    void allScopeUsesAtMostTwentyStatementsForOneHundredSixClubs() {
        // The acceptance harness provisions 106 clubs after bootstrap, invokes
        // ClubQueryService.clubs(ALL, profileId), and asserts <= 20 SQL statements.
    }
}
