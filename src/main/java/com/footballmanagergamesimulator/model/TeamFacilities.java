package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="teamFacilities")
public class TeamFacilities {

  @Id
  @GeneratedValue(strategy= GenerationType.IDENTITY)
  private long id;

  /**
   * TeamFacilities relation ids
   */
  private long teamId;

  /**
   * TeamFacilties Stats
   */
  private long youthAcademyLevel;
  private long youthTrainingLevel;
  private long seniorTrainingLevel;

}
