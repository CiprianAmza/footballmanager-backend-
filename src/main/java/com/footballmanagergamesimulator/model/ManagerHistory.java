package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "manager_history")
public class ManagerHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long managerId;
    private String managerName;
    private long teamId;
    private String teamName;
    private int seasonNumber;
    private int gamesPlayed;
    private int wins;
    private int draws;
    private int losses;
    private int goalsFor;
    private int goalsAgainst;
    private int leaguePosition;

    @Column(columnDefinition = "TEXT")
    private String trophiesWon;

    private boolean promoted;
    private boolean relegated;
}
