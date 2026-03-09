package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "round")
public class Round {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private long id;

  @Column(name = "round")
  private long round = 1;

  @Column(name = "season")
  private long season = 1;

  @Column(name = "human_team_id")
  private long humanTeamId = 0; // 0 = not yet selected (game setup needed)

  @Column(name = "manager_name")
  private String managerName;

  @Column(name = "manager_age")
  private Integer managerAge;
}
