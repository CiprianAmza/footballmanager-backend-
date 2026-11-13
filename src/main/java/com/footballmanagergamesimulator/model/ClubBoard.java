package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Who runs a club above the manager (design DESIGN_START_CARIERA §5, decided 2026-11-12): its chairman (an AI
 * person; a user who controls the club's shares overrides him) and, at some clubs, a general director with a
 * personality. An ambitious director makes it a "conflict club", which is public knowledge.
 */
@Entity
@Data
@Table(name = "club_board", uniqueConstraints = @UniqueConstraint(columnNames = {"teamId"}))
public class ClubBoard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    /** The AI chairman (Human of chairman type). */
    private long chairmanId;
    /** 0 = no general director. */
    private long directorId;
    @Enumerated(EnumType.STRING)
    private DirectorPersonality directorPersonality;
    /** Season the director took office (his seniority). */
    private int directorSinceSeason;

    public boolean hasDirector() {
        return directorId > 0 && directorPersonality != null;
    }

    /** A club whose director's ambitions clash with the chairman's. */
    public boolean isConflict() {
        return hasDirector() && directorPersonality == DirectorPersonality.AMBITIOUS;
    }
}
