package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A single, ordered, persisted question inside a {@link PressConferenceSession}.
 *
 * <p>The exact prompt text and the catalog question id are frozen here at
 * session creation so that refresh/restart shows identical wording and never
 * re-selects. The answer variants offered for this question are persisted as
 * catalog references (see the answer catalog); the chosen answer is recorded in
 * {@link PressConferenceAnswer} and pointed to by {@link #answeredAnswerId}.</p>
 */
@Entity
@Data
@Table(name = "press_conference_question",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pc_question_order",
                columnNames = {"session_id", "order_index"}),
        indexes = @Index(name = "idx_pc_question_session", columnList = "session_id,order_index"))
public class PressConferenceQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "session_id", nullable = false)
    private long sessionId;

    /** 0-based position within the session. */
    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    /** Catalog question id (stable across catalog versions where possible). */
    @Column(name = "catalog_question_id", nullable = false, length = 96)
    private String catalogQuestionId;

    /** Context key that made this question eligible (e.g. "SQUAD_VALUE_GAP", "DERBY"). */
    @Column(name = "context_key", length = 64)
    private String contextKey;

    /** Frozen prompt text as shown to the manager. */
    @Column(name = "prompt_text", columnDefinition = "TEXT", nullable = false)
    private String promptText;

    /** Frozen JSON array of the answer variants offered (catalog ids + display fields). */
    @Column(name = "answer_options", columnDefinition = "TEXT")
    private String answerOptions;

    /** Id of the {@link PressConferenceAnswer} row once answered; null while pending. */
    @Column(name = "answered_answer_id")
    private Long answeredAnswerId;
}
