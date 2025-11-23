package com.footballmanagergamesimulator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.*;
import lombok.Data;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(value = NON_NULL)
@Data
@Entity
@Table(name="playerSkills")
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
  private long skill4;
  private String skill4Name;
  private long skill5;
  private String skill5Name;
  private long skill6;
  private String skill6Name;
  private long skill7;
  private String skill7Name;
  private long skill8;
  private String skill8Name;
  private long skill9;
  private String skill9Name;
  private long skill10;
  private String skill10Name;
}
