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
}
