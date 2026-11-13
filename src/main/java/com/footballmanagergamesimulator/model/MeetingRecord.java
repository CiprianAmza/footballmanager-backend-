package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Whether a manager held or refused one of the first-day meetings in a season (design DESIGN_START_CARIERA §6.4):
 * the squad meeting, the staff meeting or the press conference.
 */
@Entity
@Data
@Table(name = "meeting_record", uniqueConstraints = @UniqueConstraint(columnNames = {"teamId", "season", "type"}))
public class MeetingRecord {

    public static final String SQUAD = "SQUAD";
    public static final String STAFF = "STAFF";
    public static final String PRESS = "PRESS";
    /** The meeting with the chairman and the director (§6.2). */
    public static final String BOARD = "BOARD";
    public static final String HELD = "HELD";
    public static final String REFUSED = "REFUSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private long managerId;
    private int season;
    private String type;
    private String outcome;
}
