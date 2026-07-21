package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="competitionHistory", indexes = {
        @Index(name = "idx_comp_history_comp_season_team", columnList = "competitionId,seasonNumber,teamId")
})
public class CompetitionHistory {

  @Id
  @GeneratedValue(strategy= GenerationType.IDENTITY)
  private long id;

  /**
   * Relation ids
   */
  private long teamId;
  private long competitionId;
  private long seasonNumber;
  private long competitionTypeId;
  private String competitionName;

  /**
   * TeamStats in competition
   */

  private int games;
  private int wins;
  private int draws;
  private int loses;
  private int goalsFor;
  private int goalsAgainst;
  private int goalDifference;
  private int points;
  private String form;
  private long lastPosition;

  /**
   * Immutable competition-landscape snapshot.  Historical overview pages must
   * never derive these values from the club's current squad: transfers,
   * training and ageing would silently rewrite the past.
   */
  @Column(columnDefinition = "boolean default false")
  private boolean landscapeSnapshotCaptured;

  private Double topElevenRating;
  private Long squadValue;
  private Long monthlyPayroll;
  private Long annualPayroll;
  private Integer reputation;
  private Integer mediaPrediction;
  private Long entryRound;
}
