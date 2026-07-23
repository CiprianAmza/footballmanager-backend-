package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Press Conference V2 — a persistent, deterministic press-conference session.
 *
 * <p>A session is created once per (team, type, fixture, season) and its
 * questions/answers are frozen at creation from a seeded, context-driven
 * generator. Refresh/restart never regenerates: callers read the persisted
 * {@link PressConferenceQuestion} rows and resume at {@link #currentQuestionIndex}.
 * Retry of creation is idempotent thanks to the unique key on
 * (team_id, session_type, fixture_key, season_number).</p>
 *
 * <p>The {@link #contextSnapshot} is an immutable JSON document captured at
 * creation. It is the single source of truth the generator saw, so replaying
 * the same seed + snapshot reproduces the same questions.</p>
 */
@Entity
@Data
@Table(name = "press_conference_session",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pc_session_fixture",
                columnNames = {"team_id", "session_type", "fixture_key", "season_number"}),
        indexes = {
                @Index(name = "idx_pc_session_team_season", columnList = "team_id,season_number"),
                @Index(name = "idx_pc_session_status", columnList = "status")
        })
public class PressConferenceSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "session_type", nullable = false, length = 16)
    private PressConferenceType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PressConferenceStatus status = PressConferenceStatus.PENDING;

    /**
     * Stable identifier of the fixture this conference is attached to, e.g.
     * "{competitionId}:{season}:{matchday}:{teamId}-{opponentId}". Also part of
     * the seed and of the idempotency key.
     */
    @Column(name = "fixture_key", nullable = false, length = 128)
    private String fixtureKey;

    @Column(name = "team_id", nullable = false)
    private long teamId;

    @Column(name = "opponent_id")
    private long opponentId;

    @Column(name = "competition_id")
    private long competitionId;

    @Column(name = "season_number", nullable = false)
    private int seasonNumber;

    /** Calendar day (1-365) the conference is scheduled on. */
    @Column(name = "conference_day", nullable = false)
    private int day;

    /** Deterministic seed driving question/answer selection. */
    @Column(name = "seed", nullable = false)
    private long seed;

    /** Version of the generator + catalog used to build this session. */
    @Column(name = "generator_version", nullable = false, length = 32)
    private String generatorVersion;

    /** Index of the next unanswered question (0-based). Equals question count when complete. */
    @Column(name = "current_question_index", nullable = false)
    private int currentQuestionIndex;

    /** Immutable JSON snapshot of the eligible context at creation time. */
    @Column(name = "context_snapshot", columnDefinition = "TEXT")
    private String contextSnapshot;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "completed_at")
    private Long completedAt;

    @Column(name = "delegated_at")
    private Long delegatedAt;

    /** Human id (assistant manager) that the session was delegated to, if any. */
    @Column(name = "delegated_by")
    private Long delegatedBy;
}
