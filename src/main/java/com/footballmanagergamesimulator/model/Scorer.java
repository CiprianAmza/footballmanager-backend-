package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="scorer")
public class Scorer {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private long id;

    private long playerId;
    private boolean isSubstitute;

    private long teamId;
    private String teamName;

    private long opponentTeamId;
    private String opponentTeamName;

    private long competitionId;
    private String competitionName;

    private int competitionTypeId;
    private int seasonNumber;

    private int teamScore;
    private int opponentScore;

    private int goals;
    private int assists;
    private double rating;
    private String position;

}
