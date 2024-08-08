package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="competitionTeamInfo")
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
