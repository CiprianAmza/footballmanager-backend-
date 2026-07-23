package com.footballmanagergamesimulator.analytics;

import com.footballmanagergamesimulator.analytics.AnalyticsProvenanceDtos.FixtureCoordinates;
import com.footballmanagergamesimulator.analytics.AnalyticsProvenanceDtos.ProvenanceEnvelope;
import com.footballmanagergamesimulator.analytics.AnalyticsProvenanceDtos.ReconciliationView;
import com.footballmanagergamesimulator.matchplan.MatchPlan;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.MatchProvenance;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.MatchPlanRepository;
import com.footballmanagergamesimulator.repository.MatchProvenanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Writes and reads the Phase 0 canonical provenance envelope.
 *
 * <p><b>Write ({@link #stampCanonical}).</b> Called at the existing canonical
 * commit chokepoints (instant / AI / interactive-live finalize). It is a no-op
 * when the flag is OFF, so the default engine (MatchPlan OFF, provenance-v2 OFF)
 * writes nothing and behaves exactly as before. Idempotency is guaranteed by the
 * same pessimistic fixture-row lock that guards canonical plan creation plus the
 * unique constraint on {@code fixture_key}: a retry or two concurrent commits for
 * one fixture can only ever leave a single row.
 *
 * <p><b>Read ({@link #readEnvelope}).</b> Additive; never mutates. A fixture with
 * no persisted row (old save, or MatchPlan/flag off) is reported honestly as
 * {@code LEGACY_UNVERSIONED} — it is never back-filled with invented provenance.
 */
@Service
public class MatchProvenanceService {

    /** Provenance envelope schema version. Bump only when the envelope shape changes. */
    public static final int PROVENANCE_SCHEMA_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(MatchProvenanceService.class);

    private final AnalyticsProvenanceSettings settings;
    private final AnalyticsProvenanceMetrics metrics;
    private final MatchProvenanceRepository provenanceRepository;
    private final MatchPlanRepository matchPlanRepository;
    private final CompetitionTeamInfoMatchRepository fixtureRepository;
    private final MatchReconciliationService reconciliationService;

    public MatchProvenanceService(AnalyticsProvenanceSettings settings,
                                  AnalyticsProvenanceMetrics metrics,
                                  MatchProvenanceRepository provenanceRepository,
                                  MatchPlanRepository matchPlanRepository,
                                  CompetitionTeamInfoMatchRepository fixtureRepository,
                                  MatchReconciliationService reconciliationService) {
        this.settings = settings;
        this.metrics = metrics;
        this.provenanceRepository = provenanceRepository;
        this.matchPlanRepository = matchPlanRepository;
        this.fixtureRepository = fixtureRepository;
        this.reconciliationService = reconciliationService;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    /**
     * Idempotently stamp the canonical provenance row for a committed fixture.
     * Safe to call unconditionally at a commit site: a no-op when the flag is OFF
     * or the key is unusable. Returns the persisted (or pre-existing) row, or
     * {@code null} when nothing was written.
     */
    @Transactional
    public MatchProvenance stampCanonical(String fixtureKey, SourceKind kind) {
        if (!settings.enabled() || fixtureKey == null || kind == null) {
            return null;
        }

        // Serialize against canonical plan creation/commit for this same fixture,
        // exactly as MatchPlanService.lockCompetitionFixture does. Under the lock,
        // check-then-insert cannot produce a duplicate.
        CompetitionTeamInfoMatch fixture = null;
        long matchRowId = FixtureIdentity.matchRowId(fixtureKey);
        if (matchRowId >= 0) {
            fixture = fixtureRepository.findByIdForUpdate(matchRowId).orElse(null);
        }

        Optional<MatchProvenance> existing = provenanceRepository.findByFixtureKey(fixtureKey);
        if (existing.isPresent()) {
            metrics.recordDuplicatePrevented(fixtureKey);
            return existing.get();
        }

        MatchReconciliationService.Result reconciliation = reconciliationService.reconcile(fixtureKey);

        MatchProvenance row = new MatchProvenance();
        row.setFixtureKey(fixtureKey);
        row.setSourceKind(kind.name());
        row.setEngineVersion(engineVersion(fixtureKey));
        row.setSchemaVersion(PROVENANCE_SCHEMA_VERSION);
        row.setReconciliationStatus(reconciliation.status().name());
        row.setReconciliationDetail(reconciliation.detail());
        applyFixtureCoordinates(row, fixture, fixtureKey);

        MatchProvenance saved = provenanceRepository.save(row);
        metrics.recordStamp(kind);
        if (reconciliation.isMismatch()) {
            metrics.recordReconciliationMismatch(fixtureKey, reconciliation.detail());
        }
        log.debug("provenance stamped fixtureKey={} kind={} engine={} reconciliation={}",
                fixtureKey, kind, saved.getEngineVersion(), saved.getReconciliationStatus());
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<MatchProvenance> find(String fixtureKey) {
        return provenanceRepository.findByFixtureKey(fixtureKey);
    }

    /**
     * Build the additive v2 envelope for a fixture. When the flag is OFF, or no
     * provenance row exists, returns a truthful {@code LEGACY_UNVERSIONED}
     * envelope (still carrying any reconciliation that can be computed from an
     * existing plan). Read-only.
     */
    @Transactional(readOnly = true)
    public ProvenanceEnvelope readEnvelope(String fixtureKey, FixtureCoordinates coordinates) {
        boolean enabled = settings.enabled();
        Optional<MatchProvenance> rowOpt = enabled
                ? provenanceRepository.findByFixtureKey(fixtureKey)
                : Optional.empty();

        MatchReconciliationService.Result reconciliation = reconciliationService.reconcile(fixtureKey);
        ReconciliationView reconciliationView = ReconciliationView.from(reconciliation);

        if (rowOpt.isPresent()) {
            MatchProvenance row = rowOpt.get();
            metrics.recordCanonicalRead();
            return new ProvenanceEnvelope(
                    fixtureKey, true, true,
                    SourceKind.fromStored(row.getSourceKind()).name(),
                    row.getEngineVersion(), row.getSchemaVersion(),
                    reconciliationView, coordinates);
        }

        metrics.recordLegacyRead();
        return new ProvenanceEnvelope(
                fixtureKey, enabled, false,
                SourceKind.LEGACY_UNVERSIONED.name(),
                engineVersion(fixtureKey), PROVENANCE_SCHEMA_VERSION,
                reconciliationView, coordinates);
    }

    private String engineVersion(String fixtureKey) {
        return matchPlanRepository.findByFixtureKey(fixtureKey)
                .map(MatchPlan::getAlgorithmVersion)
                .orElse(null);
    }

    private void applyFixtureCoordinates(MatchProvenance row, CompetitionTeamInfoMatch fixture, String fixtureKey) {
        if (fixture != null) {
            row.setCompetitionId(fixture.getCompetitionId());
            row.setSeasonNumber(parseSeason(fixture.getSeasonNumber()));
            row.setRoundNumber((int) fixture.getRound());
            row.setHomeTeamId(fixture.getTeam1Id());
            row.setAwayTeamId(fixture.getTeam2Id());
            return;
        }
        matchPlanRepository.findByFixtureKey(fixtureKey).ifPresent(plan -> {
            row.setHomeTeamId(plan.getHomeTeamId());
            row.setAwayTeamId(plan.getAwayTeamId());
        });
    }

    private static int parseSeason(String season) {
        if (season == null) {
            return 0;
        }
        try {
            return Integer.parseInt(season.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
