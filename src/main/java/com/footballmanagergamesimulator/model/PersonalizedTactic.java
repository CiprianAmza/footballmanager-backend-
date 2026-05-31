package com.footballmanagergamesimulator.model;


import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="personalizedTactic")
public class PersonalizedTactic {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private long id;

    private long teamId;
    @Column(length = 10000)
    private String first11;
    private String tactic;

    private String mentality;
    private String timeWasting;
    private String inPossession;
    private String passingType;
    private String tempo;

    // Strat-2 tactical axes (null ⇒ neutral / no-op). See TacticalScoreService.vector().
    private String defensiveLine; // Deep / Standard / High
    private String pressing;      // Low / Standard / High
    private String width;         // Narrow / Balanced / Wide

    // Set piece takers
    private Long penaltyTakerId;
    private Long freeKickTakerId;
    private Long cornerTakerLeftId;
    private Long cornerTakerRightId;
}
