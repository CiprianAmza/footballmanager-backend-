package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.engine.invariants.DefaultInvariants;
import com.footballmanagergamesimulator.engine.invariants.EngineInvariant;
import com.footballmanagergamesimulator.engine.invariants.EngineInvariantSuiteRunner;
import com.footballmanagergamesimulator.engine.invariants.InvariantResult;
import com.footballmanagergamesimulator.service.MatchSimulationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the canonical {@link DefaultInvariants} catalog against the current
 * engine configuration and prints a pass/fail report.
 *
 * <p><b>Failure is informational, not destructive.</b> The test asserts that
 * the runner produced a result for every invariant (i.e. the harness works),
 * but does NOT assert that every invariant passes — that would block builds
 * every time someone tweaks a config knob. The pass/fail per invariant is
 * surfaced in stdout for human review and consumed by Faza 4's auto-tuner.
 *
 * <p>Two separate assertions guard against silent harness regressions:
 * <ul>
 *   <li>Same seed → identical totals on two consecutive runs (determinism).</li>
 *   <li>At least one invariant passes (so a broken runner can't make
 *       "everything fails silently" look fine).</li>
 * </ul>
 */
@SpringBootTest
@DisplayName("Engine invariants catalog — pass/fail report (informational)")
class EngineInvariantsCatalogIT {

    private static final long BASE_SEED = 20260528L;

    @Autowired private MatchSimulationService matchSimulationService;

    @Test
    @DisplayName("Runs the catalog + prints diagnostic; no hard fail on individual invariants")
    void runCatalogAndPrintReport() {
        EngineInvariantSuiteRunner runner = new EngineInvariantSuiteRunner(matchSimulationService, BASE_SEED);
        List<EngineInvariant> catalog = DefaultInvariants.catalog();

        List<InvariantResult> results = runner.runAll(catalog);
        String report = EngineInvariantSuiteRunner.report(results);
        System.out.println(report);

        assertThat(results)
                .as("runner must produce one result per invariant")
                .hasSize(catalog.size());
        assertThat(results.stream().anyMatch(InvariantResult::passed))
                .as("at least one invariant must pass — otherwise the harness is broken")
                .isTrue();
    }

    @Test
    @DisplayName("Catalog run is deterministic — same seed → same totals")
    void catalogRunIsDeterministic() {
        EngineInvariantSuiteRunner r1 = new EngineInvariantSuiteRunner(matchSimulationService, BASE_SEED);
        EngineInvariantSuiteRunner r2 = new EngineInvariantSuiteRunner(matchSimulationService, BASE_SEED);
        List<InvariantResult> a = r1.runAll(DefaultInvariants.catalog());
        List<InvariantResult> b = r2.runAll(DefaultInvariants.catalog());

        for (int i = 0; i < a.size(); i++) {
            assertThat(b.get(i).winsA()).as("winsA mismatch for %s", a.get(i).invariant().name())
                    .isEqualTo(a.get(i).winsA());
            assertThat(b.get(i).draws()).as("draws mismatch for %s", a.get(i).invariant().name())
                    .isEqualTo(a.get(i).draws());
            assertThat(b.get(i).winsB()).as("winsB mismatch for %s", a.get(i).invariant().name())
                    .isEqualTo(a.get(i).winsB());
        }
    }
}
