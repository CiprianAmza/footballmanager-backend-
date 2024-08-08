package com.footballmanagergamesimulator.model;


import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="humanTeamRelation")
public class HumanTeamRelation {

  @Id
  @GeneratedValue(strategy= GenerationType.IDENTITY)
  private long id;

  /**
   * Relation ids
   */
  private long humanId;
  private long teamId;
  private long humanTypeId;

}
