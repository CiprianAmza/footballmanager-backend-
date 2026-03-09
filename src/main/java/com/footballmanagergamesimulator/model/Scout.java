package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "scout")
public class Scout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private String name;
    private Long teamId; // null = available for hire (free agent scout)

    private int scoutingAbility; // 1-20, affects accuracy of revealed rating
    private int experience; // 1-20, affects scouting speed
    private int judgingPotential; // 1-20, affects potential ability accuracy

    private long wage; // weekly salary
    private long wageDemand; // what the scout wants (for negotiation)
    private int contractEndSeason;

    private String knownLeagues; // comma-separated competition IDs the scout specializes in

    @Column(columnDefinition = "boolean default false")
    private boolean hired;
}
