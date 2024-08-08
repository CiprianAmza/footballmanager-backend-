package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="transferStrategy")
public class TransferStrategy {

  @Id
  @GeneratedValue(strategy= GenerationType.IDENTITY)
  private long id;

}
