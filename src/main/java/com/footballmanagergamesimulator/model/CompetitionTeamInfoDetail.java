package com.footballmanagergamesimulator.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
public class CompetitionTeamInfoDetail {

  @Id
  @GeneratedValue(strategy= GenerationType.IDENTITY)
  private long id;

  /**
   * Relation ids
   */
  private long team1Id;
  private long team2Id;
  private long competitionId;

  /**
   * Score Info
   */

  private String score;
  private long roundId;
  private String teamName1;
  private String teamName2;
  private long seasonNumber;

}
