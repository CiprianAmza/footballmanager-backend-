package com.footballmanagergamesimulator.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
public class CompetitionTeamInfo {

  @Id
  @GeneratedValue(strategy= GenerationType.IDENTITY)
  private long id;

  /**
   * Relation ids
   */
  private long teamId;
  private long competitionId;

  /**
   * CompetitionTeam information
   */
  private long round;
  private long seasonNumber;
}
