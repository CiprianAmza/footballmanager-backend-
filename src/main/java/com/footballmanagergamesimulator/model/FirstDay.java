package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A manager's first day at a new club (design DESIGN_START_CARIERA §6, decided 2026-11-13): the game does not
 * move on until he has read the article and the briefing, met the board, and held or refused the squad meeting,
 * the staff meeting and the press conference.
 */
@Entity
@Data
@Table(name = "first_day")
public class FirstDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private long managerId;
    private int season;
    /** The appointment article in the inbox (0 = none). */
    private long articleMessageId;
    /** The assistant's first briefing in the inbox (0 = none). */
    private long briefingMessageId;
    private boolean done;
}
