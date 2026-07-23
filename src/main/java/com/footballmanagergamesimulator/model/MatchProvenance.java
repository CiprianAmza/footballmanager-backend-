package com.footballmanagergamesimulator.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * Phase 0 canonical provenance row for a single fixture (Analytics Option B).
 *
 * <p>One row per canonical fixture, uniquely identified by {@code fixtureKey}
 * (the existing {@code "CTIM:<matchRowId>"} key already used by MatchPlan and
 * MatchEvent). The unique constraint on {@code fixture_key} is what makes the
 * stamp idempotent: a daily retry or two concurrent commits for the same fixture
 * can only ever leave one row. This table records identity, source/engine
 * version and reconciliation state; it never stores a score, event or any new
 * football metric. Old saves that predate this table simply have no row and are
 * reported as {@code LEGACY_UNVERSIONED} — never back-filled with invented data.
 *
 * <p>All columns are explicitly named in snake_case so the JPA mapping matches
 * the H2-only Flyway migration exactly under the project's identity naming
 * strategy.
 */
@Entity
@Data
@Table(name = "match_provenance",
        uniqueConstraints = @UniqueConstraint(name = "uk_match_provenance_fixture_key",
                columnNames = "fixture_key"),
        indexes = {
                @Index(name = "idx_match_provenance_comp_season_round",
                        columnList = "competition_id,season_number,round_number")
        })
public class MatchProvenance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    /** Stable canonical fixture identity, e.g. {@code "CTIM:1234"}. Unique. */
    @Column(name = "fixture_key", nullable = false, unique = true, length = 120)
    private String fixtureKey;

    /** {@link com.footballmanagergamesimulator.analytics.SourceKind} name. */
    @Column(name = "source_kind", nullable = false, length = 32)
    private String sourceKind;

    /** Real engine/algorithm version that produced the canonical result (e.g. {@code "matchplan-2"}). */
    @Column(name = "engine_version", length = 64)
    private String engineVersion;

    /** Provenance envelope schema version — bumped only when the envelope shape changes. */
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    /** {@link com.footballmanagergamesimulator.analytics.ReconciliationStatus} name. */
    @Column(name = "reconciliation_status", nullable = false, length = 32)
    private String reconciliationStatus;

    /** Human-readable diff when the reconciliation status is a mismatch; null otherwise. */
    @Column(name = "reconciliation_detail", length = 1024)
    private String reconciliationDetail;

    @Column(name = "competition_id", nullable = false)
    private long competitionId;

    @Column(name = "season_number", nullable = false)
    private int seasonNumber;

    @Column(name = "round_number", nullable = false)
    private int roundNumber;

    @Column(name = "home_team_id", nullable = false)
    private long homeTeamId;

    @Column(name = "away_team_id", nullable = false)
    private long awayTeamId;
}
