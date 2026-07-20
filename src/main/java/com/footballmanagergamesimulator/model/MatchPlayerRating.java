package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "matchPlayerRating", indexes = {
        @Index(name = "idx_mpr_comp_season_round", columnList = "competitionId,seasonNumber,roundNumber"),
        @Index(name = "idx_mpr_player_season", columnList = "playerId,seasonNumber"),
        @Index(name = "idx_mpr_team_season", columnList = "teamId,seasonNumber")
})
public class MatchPlayerRating {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private long id;

  /**
   * Match identity
   */
  private long competitionId;
  private int seasonNumber;
  private int roundNumber;
  private long teamId;

  /**
   * Player snapshot at match time
   */
  private long playerId;
  private String playerName;
  private String position;
  private int positionIndex;
  private String formation;
  private String role;
  private String duty;
  private boolean substitute;

  /** Attribute-weighted engine value used to calculate the team's strength. */
  private double rating;

  /** Post-match performance on the familiar 1-10 scale (0 = did not play). */
  private double performanceRating;
  private int goals;
  private int assists;
  private int age;
  private long nationId;

  /** Face snapshot: the historical lineup must not change when a player later changes club. */
  private int baseFaceId;
  private int skinTone;
  private int hairStyle;
  private int hairColor;
  private int eyeColor;
  private int faceShape;
  private int noseShape;
  private int eyeShape;
  private int mouthShape;
  private int browShape;
  private String species;
}
