package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "matchPlayerRating")
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
  private double rating;
  private int age;
  private long nationId;
}
