package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Every relation in the world, in one table (decided 2026-11-12): how {@code subject} feels about {@code target},
 * on a 0–100 scale, 50 = neutral. Examples: agent → manager, player → club, player → manager,
 * manager → director. Directional: the agent's view of me is one row, mine of him (if ever needed) another.
 * No row = neutral (50).
 */
@Entity
@Data
@Table(name = "human_entity_relation",
        uniqueConstraints = @UniqueConstraint(name = "uk_human_entity_relation",
                columnNames = {"subjectType", "subjectId", "targetType", "targetId"}))
public class HumanEntityRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private RelationParty subjectType;
    private long subjectId;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private RelationParty targetType;
    private long targetId;

    private int relation;
}
