package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="competitionTeamInfo", indexes = {
        @Index(name = "idx_cti_comp_season_round", columnList = "competitionId,seasonNumber,round"),
        @Index(name = "idx_cti_team_season", columnList = "teamId,seasonNumber")
})
public class CompetitionTeamInfo {

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "cti_seq")
  @SequenceGenerator(name = "cti_seq", sequenceName = "cti_seq", allocationSize = 1)
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
  private int groupNumber; // 0 = not in a group, 1-4 = group number
  private int potNumber;   // 0 = no pot assigned, 1-4 = pot number (seeding tier)
}
