package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "youth_player")
public class YouthPlayer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private long playerId; // FK to Human when promoted
    private String name;
    private String position;
    private int age;
    private String potential; // "STAR", "GOOD", "AVERAGE", "LOW"
    private int currentAbility;
    private int potentialAbility;
    private int scoutedDay;
    private int seasonJoined;
    private String status; // "IN_ACADEMY", "PROMOTED", "RELEASED"
    private int daysInAcademy;
}
