package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="scorer", indexes = {
        @Index(name = "idx_scorer_comp_season_round", columnList = "competitionId,seasonNumber,roundNumber"),
        @Index(name = "idx_scorer_player_season", columnList = "playerId,seasonNumber"),
        @Index(name = "idx_scorer_team_season", columnList = "teamId,seasonNumber")
})
public class Scorer {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "scorer_seq")
    @SequenceGenerator(name = "scorer_seq", sequenceName = "scorer_seq", allocationSize = 1)
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
    private int roundNumber;

    private int teamScore;
    private int opponentScore;

    private int goals;
    private int assists;
    private double rating;
    private String position;

}
