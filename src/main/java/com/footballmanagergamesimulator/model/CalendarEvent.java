package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "calendar_event", indexes = {
        @Index(name = "idx_calendar_season_day_phase", columnList = "season,event_day,phase"),
        @Index(name = "idx_calendar_pending", columnList = "season,event_status,event_day,priority"),
        @Index(name = "idx_calendar_comp_matchday", columnList = "competitionId,season,matchday")
})
public class CalendarEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private int season;
    @Column(name = "event_day")
    private int day; // 1-365
    private String phase; // "MORNING", "AFTERNOON", "EVENING"
    private String eventType; // "MATCH_LEAGUE", "MATCH_CUP", "MATCH_EUROPEAN", "EUROPEAN_DRAW", "MATCH_FRIENDLY", "TRAINING_SESSION", "PRESS_CONFERENCE", "YOUTH_ACADEMY_REPORT", "BOARD_MEETING", "PLAYER_INTERACTION", "AWARDS_CEREMONY", "SPONSOR_OFFER", "AGENT_CONTACT", "NATIONAL_TEAM_CALL", "FACILITY_UPGRADE_COMPLETE", "SEASON_START", "SEASON_END", "TRANSFER_WINDOW_OPEN", "TRANSFER_WINDOW_CLOSE", "ANALYTICS_REPORT", "CONTRACT_EXPIRY_CHECK", "INJURY_UPDATE"
    private Long competitionId; // nullable
    private int matchday; // the matchday number within the competition
    @Column(name = "event_status")
    private String status; // "PENDING", "PROCESSING", "COMPLETED", "SKIPPED"
    private String title;
    private String description; // nullable
    private int priority; // ordering within same day+phase
    private Long relatedEntityId; // nullable - for linking to specific matches, players, etc.

    /**
     * Leg number for a two-leg knockout matchday: 0 = single match (one event per
     * matchday, the default), 1 = first leg, 2 = second leg. Two-leg European
     * rounds emit two MATCH events on different days so the legs are played apart.
     */
    @Column(name = "leg_number")
    private int legNumber;
}
