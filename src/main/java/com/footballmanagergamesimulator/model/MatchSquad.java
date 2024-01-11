package com.footballmanagergamesimulator.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
public class MatchSquad {

  @Id
  @GeneratedValue(strategy= GenerationType.IDENTITY)
  private long id;

  /**
   * Relation ids
   */
  private long teamId;
  private long tacticId;

  /**
   * 18 positions (11 first team positions + 7 substitutes) playerId + playerName
   */
  private long positionPlayerId1;
  private String positionPlayerName1;
  private long positionPlayerId2;
  private String positionPlayerName2;
  private long positionPlayerId3;
  private String positionPlayerName3;
  private long positionPlayerId4;
  private String positionPlayerName4;
  private long positionPlayerId5;
  private String positionPlayerName5;
  private long positionPlayerId6;
  private String positionPlayerName6;
  private long positionPlayerId7;
  private String positionPlayerName7;
  private long positionPlayerId8;
  private String positionPlayerName8;
  private long positionPlayerId9;
  private String positionPlayerName9;
  private long positionPlayerId10;
  private String positionPlayerName10;
  private long positionPlayerId11;
  private String positionPlayerName11;
  private long positionPlayerId12;
  private long positionPlayerName12;
  private long positionPlayerId13;
  private String positionPlayerName13;
  private long positionPlayerId14;
  private String positionPlayerName14;
  private long positionPlayerId15;
  private String positionPlayerName15;
  private long positionPlayerId16;
  private String positionPlayerName16;
  private long positionPlayerId17;
  private String positionPlayerName17;
  private long positionPlayerId18;
  private String positionPlayerName18;



}
