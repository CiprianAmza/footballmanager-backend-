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
@Table(name="playerSkills", indexes = {
        @Index(name = "idx_player_skills_player", columnList = "playerId")
})
public class PlayerSkills {

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "player_skills_seq")
  @SequenceGenerator(name = "player_skills_seq", sequenceName = "player_skills_seq", allocationSize = 1)
  private long id;

  private long playerId;
  private String position;

  // ===== TECHNICAL ATTRIBUTES (1-20) =====
  @Column(columnDefinition = "int default 10")
  private int corners;
  @Column(columnDefinition = "int default 10")
  private int crossing;
  @Column(columnDefinition = "int default 10")
  private int dribbling;
  @Column(columnDefinition = "int default 10")
  private int finishing;
  @Column(columnDefinition = "int default 10")
  private int firstTouch;
  @Column(columnDefinition = "int default 10")
  private int freeKick;
  @Column(columnDefinition = "int default 10")
  private int heading;
  @Column(columnDefinition = "int default 10")
  private int longShots;
  @Column(columnDefinition = "int default 10")
  private int longThrows;
  @Column(columnDefinition = "int default 10")
  private int marking;
  @Column(columnDefinition = "int default 10")
  private int passing;
  @Column(columnDefinition = "int default 10")
  private int penaltyTaking;
  @Column(columnDefinition = "int default 10")
  private int tackling;
  @Column(columnDefinition = "int default 10")
  private int technique;

  // ===== MENTAL ATTRIBUTES (1-20) =====
  @Column(columnDefinition = "int default 10")
  private int aggression;
  @Column(columnDefinition = "int default 10")
  private int anticipation;
  @Column(columnDefinition = "int default 10")
  private int bravery;
  @Column(columnDefinition = "int default 10")
  private int composure;
  @Column(columnDefinition = "int default 10")
  private int concentration;
  @Column(columnDefinition = "int default 10")
  private int decisions;
  @Column(columnDefinition = "int default 10")
  private int determination;
  @Column(columnDefinition = "int default 10")
  private int flair;
  @Column(columnDefinition = "int default 10")
  private int leadership;
  @Column(columnDefinition = "int default 10")
  private int offTheBall;
  @Column(columnDefinition = "int default 10")
  private int positioning;
  @Column(columnDefinition = "int default 10")
  private int teamwork;
  @Column(columnDefinition = "int default 10")
  private int vision;
  @Column(columnDefinition = "int default 10")
  private int workRate;

  // ===== PHYSICAL ATTRIBUTES (1-20) =====
  @Column(columnDefinition = "int default 10")
  private int acceleration;
  @Column(columnDefinition = "int default 10")
  private int agility;
  @Column(columnDefinition = "int default 10")
  private int balance;
  @Column(columnDefinition = "int default 10")
  private int jumpingReach;
  @Column(columnDefinition = "int default 10")
  private int naturalFitness;
  @Column(columnDefinition = "int default 10")
  private int pace;
  @Column(columnDefinition = "int default 10")
  private int stamina;
  @Column(columnDefinition = "int default 10")
  private int strength;

  // ===== GOALKEEPER ATTRIBUTES (1-20) =====
  @Column(columnDefinition = "int default 1")
  private int handling;
  @Column(columnDefinition = "int default 1")
  private int reflexes;
  @Column(columnDefinition = "int default 1")
  private int oneOnOnes;
  @Column(columnDefinition = "int default 1")
  private int commandOfArea;
  @Column(columnDefinition = "int default 1")
  private int kicking;
  @Column(columnDefinition = "int default 1")
  private int throwing;

  // ===== BACKWARD COMPATIBILITY (mapped from new attributes) =====
  // These are kept for DB migration but computed from real attributes
  @Transient
  public long getSkill1() { return acceleration; }
  @Transient
  public long getSkill2() { return pace; }
  @Transient
  public long getSkill3() { return strength; }
  @Transient
  public long getSkill4() { return dribbling; }
  @Transient
  public long getSkill5() { return passing; }
  @Transient
  public long getSkill6() { return workRate; }
  @Transient
  public long getSkill7() { return longShots; }
  @Transient
  public long getSkill8() { return finishing; }
  @Transient
  public long getSkill9() { return crossing; }
  @Transient
  public long getSkill10() { return tackling; }
}
