package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "award")
public class Award {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private int seasonNumber;
    private String awardType; // "BEST_PLAYER", "TOP_SCORER", "BEST_YOUNG_PLAYER", "MANAGER_OF_YEAR", "GOLDEN_BOOT"
    private long competitionId;
    private String competitionName;
    private long winnerId;
    private String winnerName;
    private long winnerTeamId;
    private String winnerTeamName;
    @Column(name = "award_value")
    private String value; // nullable - e.g. "23 goals"
}
