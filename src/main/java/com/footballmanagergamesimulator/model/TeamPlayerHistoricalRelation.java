package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="teamPlayerHistoricalRelation")
public class TeamPlayerHistoricalRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "tphr_seq")
    @SequenceGenerator(name = "tphr_seq", sequenceName = "tphr_seq", allocationSize = 1)
    private long id;

    /**
     * TeamFacilities relation ids
     */
    private long teamId;
    private long playerId;
    private long seasonNumber;

    private double rating;
}