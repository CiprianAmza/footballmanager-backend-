package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "game_calendar")
public class GameCalendar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private int season;
    private int currentDay; // 1-365
    private String currentPhase; // "MORNING", "AFTERNOON", "EVENING", "END_OF_DAY"
    private String seasonPhase; // "PRE_SEASON", "EARLY_SEASON", "MID_SEASON", "WINTER_BREAK", "LATE_SEASON", "END_OF_SEASON", "OFF_SEASON"
    private boolean transferWindowOpen;
    private boolean managerFired;
    private boolean paused; // for user input events like tactical changes
}
