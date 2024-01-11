package com.footballmanagergamesimulator.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
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
