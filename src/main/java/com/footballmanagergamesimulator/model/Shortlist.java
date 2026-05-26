package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "shortlist")
public class Shortlist {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long userId;
    private long playerId;
    private String playerName;
    private String position;
    private String teamName;
    private long addedAtDay;
    private int addedAtSeason;
    private String notes;
}
