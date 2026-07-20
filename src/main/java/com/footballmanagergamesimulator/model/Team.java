package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="team")
public class Team {

  @Id
  @GeneratedValue(strategy= GenerationType.IDENTITY)
  private long id;

  /**
   * Relation ids
   */
  private long competitionId;
  private long stadiumId;
  private long historyId;


  /**
   * General Information
   */
  private String name;
  private long totalFinances;
  private long transferBudget;
  private long salaryBudget;
  private int reputation;

  /**
   * Display information
   */
  private String color1;
  private String color2;
  private String border;

  private Long strategy;

  /**
   * Prevents a struggling club from replacing a newly appointed AI manager
   * again on the very next calendar tick. Zero means no mid-season change has
   * been made yet; a club may perform at most one such change per season.
   */
  @Column(columnDefinition = "int default 0")
  private int lastMidSeasonManagerChangeSeason;

  /**
   * Stadium information
   */
  @Column(columnDefinition = "int default 30000")
  private int stadiumCapacity = 30000;

  private String stadiumName;

  /**
   * Finance information
   */
  @Column(columnDefinition = "bigint default 0")
  private long debt = 0;

  // Board confidence in the manager (0-100). Affects what % of income goes to transfer budget.
  @Column(columnDefinition = "int default 50")
  private int boardConfidence = 50;

}
