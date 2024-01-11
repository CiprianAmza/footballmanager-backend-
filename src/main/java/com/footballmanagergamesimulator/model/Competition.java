package com.footballmanagergamesimulator.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
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
