package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="competitionTeamInfoDetail", indexes = {
        @Index(name = "idx_ctid_comp_season_round", columnList = "competitionId,seasonNumber,roundId"),
        @Index(name = "idx_ctid_team1_season", columnList = "team1Id,seasonNumber"),
        @Index(name = "idx_ctid_team2_season", columnList = "team2Id,seasonNumber")
})
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
  /** Decisive winner even when the displayed score is level after penalties. */
  private Long winnerTeamId;
  /** NORMAL, EXTRA_TIME, PENALTIES, AGGREGATE, or FIRST_LEG. */
  private String decidedBy;
  private long roundId;
  private String teamName1;
  private String teamName2;
  private long seasonNumber;

  /**
   * Stable bracket coordinates copied from the scheduled fixture when the
   * result is written.  CompetitionTeamInfoMatch rows are intentionally
   * cleared between seasons, so the result row must own enough information to
   * rebuild an old bracket on its own.
   */
  @Column(name = "match_index", nullable = false,
          columnDefinition = "integer default 0")
  private int matchIndex;

  @Column(name = "match_day", nullable = false,
          columnDefinition = "integer default 0")
  private int day;

  @Column(name = "tie_id", nullable = false,
          columnDefinition = "bigint default 0")
  private long tieId;

  /**
   * Leg number for a two-leg knockout tie (0 = single match, 1 = first leg,
   * 2 = second leg). Lets the matchday dispatcher tell whether a specific leg of
   * a round has already been simulated when the two legs fall on different days.
   */
  @Column(name = "leg_number")
  private int legNumber;

}
