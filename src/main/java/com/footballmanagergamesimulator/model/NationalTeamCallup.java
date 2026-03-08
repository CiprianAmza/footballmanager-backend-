package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "national_team_callup")
public class NationalTeamCallup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long playerId;
    private String playerName;
    private long teamId;
    private int seasonNumber;
    private int startDay;
    private int endDay;
    private boolean returned;
    private boolean injuredDuringCallup;
}
