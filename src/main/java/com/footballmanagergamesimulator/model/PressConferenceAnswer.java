package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * The chosen answer for one {@link PressConferenceQuestion}.
 *
 * <p>Exactly one answer row exists per answered question (enforced by a unique
 * key on {@code question_id}); answering is idempotent — a duplicate submit of
 * the same answer is a no-op and never re-applies effects. The effects actually
 * applied are frozen in {@link #appliedEffects} for audit and save/load
 * round-tripping.</p>
 */
@Entity
@Data
@Table(name = "press_conference_answer",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pc_answer_question",
                columnNames = {"question_id"}),
        indexes = @Index(name = "idx_pc_answer_session", columnList = "session_id"))
public class PressConferenceAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "session_id", nullable = false)
    private long sessionId;

    @Column(name = "question_id", nullable = false)
    private long questionId;

    /** Catalog answer id chosen. */
    @Column(name = "catalog_answer_id", nullable = false, length = 96)
    private String catalogAnswerId;

    /**
     * Response code (e.g. "confident", "cautious", "resent", "assertive"). Kept
     * for compatibility with the legacy modal + boardroom vocabularies, resolved
     * through the shared stance mapping.
     */
    @Column(name = "answer_code", length = 48)
    private String answerCode;

    /** Emotional tone of the answer (e.g. "CALM", "BULLISH", "DEFENSIVE"). */
    @Column(name = "tone", length = 32)
    private String tone;

    /** Strategic stance (e.g. "SUPPORTIVE", "DEFIANT", "NEUTRAL", "DEFLECT"). */
    @Column(name = "stance", length = 32)
    private String stance;

    /** Frozen JSON array of effects actually applied when this answer was committed. */
    @Column(name = "applied_effects", columnDefinition = "TEXT")
    private String appliedEffects;

    /** True when the assistant manager auto-answered this on delegation. */
    @Column(name = "delegated")
    private boolean delegated;

    @Column(name = "applied_at", nullable = false)
    private long appliedAt;
}
