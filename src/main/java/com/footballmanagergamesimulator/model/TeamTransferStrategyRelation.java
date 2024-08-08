package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="teamTransferStrategyRelation")
public class TeamTransferStrategyRelation {

  @Id
  @GeneratedValue(strategy= GenerationType.IDENTITY)
  private long id;

  /**
   * TeamFacilities relation ids
   */
  private long teamId;
  private long transferStrategyId;
}
