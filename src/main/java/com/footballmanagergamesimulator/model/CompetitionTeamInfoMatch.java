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

  /**
   * 1-based position of this match within its round of a cup bracket.
   * For round R match i, the winner advances to round R+1 match ceil(i/2),
   * as team1 if i is odd, team2 if i is even.
   * Stays 0 for league/group-stage matches where bracket positioning is irrelevant.
   */
  @Column(name = "match_index")
  private int matchIndex;

  /**
   * Leg number for a two-leg (home-and-away) knockout tie:
   * 0 = single match (league/group/single-leg knockout), 1 = first leg, 2 = second leg.
   * The winner of a two-leg tie is decided on aggregate only after leg 2.
   */
  @Column(name = "leg_number")
  private int legNumber;

  /**
   * Groups the two legs of one two-leg tie together (both legs share the same tieId).
   * 0 for single matches. Lets the simulator find the first-leg result when playing leg 2.
   */
  @Column(name = "tie_id")
  private long tieId;
}
