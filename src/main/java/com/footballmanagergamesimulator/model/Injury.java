package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "injury")
public class Injury {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long playerId;
    private long teamId;

    private String injuryType;
    private String severity;
    private int daysRemaining;
    private int seasonNumber;

}
