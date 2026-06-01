package com.footballmanagergamesimulator.frontend;

import lombok.Data;

import java.sql.Date;
import java.util.List;

@Data
public class PlayerView {

  private long id;
  private String name;
  private long teamId;
  private String teamName;
  private String position;
  private double rating;
  private int age;

  private double bestEverRating;
  private int seasonOfBestEverRating;

  private long salary;
  private String agreedPlayingTime;
  private Date contractEndDate;
  private Date contractStartDate;

  private int contractEndSeason;
  private long wage;
  private long releaseClause;
  private long transferValue;

  private double fitness;
  private double morale;
  private String currentStatus;

  private long seasonCreated;
  private long wealth;

  List<String> skillNames;
  List<Long> skillValues;

  // Physical profile
  private String preferredFoot;
  private int heightCm;
  private int weightKg;

  // Nation (derived: team -> competition -> nationId)
  private long nationId;
  private String nationName;
  private String nationFlagCode;

  // Face descriptor (FE renders layered pieces from these indices)
  private int baseFaceId;
  private int skinTone;
  private int hairStyle;
  private int hairColor;
  private int eyeColor;
  // Shape indices (independent of colour) — picked randomly per player on the backend.
  private int faceShape;
  private int noseShape;
  private int eyeShape;
  private int mouthShape;
  private int browShape;
}
