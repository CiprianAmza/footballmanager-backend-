package com.footballmanagergamesimulator.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
public class TransferStrategy {

  @Id
  @GeneratedValue(strategy= GenerationType.IDENTITY)
  private long id;

}
