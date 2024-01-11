package com.footballmanagergamesimulator.frontend;

import lombok.Data;

@Data
public class PlayerView {

  private String name;
  private String teamName;
  private String position;
  private double rating;
  private int age;
}
