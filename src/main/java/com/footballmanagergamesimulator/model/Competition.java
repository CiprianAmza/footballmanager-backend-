package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="competition")
public class Competition {

  @Id
  @GeneratedValue(strategy= GenerationType.IDENTITY)
  private long id;

  /**
   * Relation ids
   */
  private long nationId;
  private long prizesId;
  private long typeId;

  /**
   * General Information
   */
  private String name;

}
