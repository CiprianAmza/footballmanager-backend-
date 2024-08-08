package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name="teamCompetitionDetail")
public class TeamCompetitionDetail {

  @Id
  @GeneratedValue(strategy= GenerationType.IDENTITY)
  private long id;

  /**
   * Relation ids
   */
  private long teamId;
  private long competitionId;

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
  private String last10Positions;
}
