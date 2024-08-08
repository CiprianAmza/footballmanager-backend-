package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="competitionHistory")
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
}
