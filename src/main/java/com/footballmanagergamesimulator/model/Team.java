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

}
