package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "calendar_event")
public class CalendarEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private int season;
    @Column(name = "event_day")
    private int day; // 1-365
    private String phase; // "MORNING", "AFTERNOON", "EVENING"
    private String eventType; // "MATCH_LEAGUE", "MATCH_CUP", "MATCH_EUROPEAN", "MATCH_FRIENDLY", "TRAINING_SESSION", "PRESS_CONFERENCE", "YOUTH_ACADEMY_REPORT", "BOARD_MEETING", "PLAYER_INTERACTION", "AWARDS_CEREMONY", "SPONSOR_OFFER", "AGENT_CONTACT", "NATIONAL_TEAM_CALL", "FACILITY_UPGRADE_COMPLETE", "SEASON_START", "SEASON_END", "TRANSFER_WINDOW_OPEN", "TRANSFER_WINDOW_CLOSE", "ANALYTICS_REPORT", "CONTRACT_EXPIRY_CHECK", "INJURY_UPDATE"
    private Long competitionId; // nullable
    private int matchday; // the matchday number within the competition
    @Column(name = "event_status")
    private String status; // "PENDING", "COMPLETED", "SKIPPED"
    private String title;
    private String description; // nullable
    private int priority; // ordering within same day+phase
    private Long relatedEntityId; // nullable - for linking to specific matches, players, etc.
}
