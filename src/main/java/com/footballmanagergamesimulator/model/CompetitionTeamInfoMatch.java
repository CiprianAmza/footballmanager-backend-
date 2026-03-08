package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="competitionTeamInfoMatch")
public class CompetitionTeamInfoMatch {

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
   * CompetitionTeam information
   */
  private long round;
  @Column(name = "match_day")
  private int day;
  private String seasonNumber;
}
