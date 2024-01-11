package com.footballmanagergamesimulator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(value = NON_NULL)
public class PlayerSkills {

  @Id
  @GeneratedValue(strategy= GenerationType.IDENTITY)
  private long id;

  /**
   * Relation ids
   */
  private long playerId;

  /**
   * Display information
   */
  private String position;
  private long skill1;
  private String skill1Name;
  private long skill2;
  private String skill2Name;
  private long skill3;
  private String skill3Name;

}
