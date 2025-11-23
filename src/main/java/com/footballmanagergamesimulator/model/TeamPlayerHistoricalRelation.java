package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="teamPlayerHistoricalRelation")
public class TeamPlayerHistoricalRelation {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private long id;

    /**
     * TeamFacilities relation ids
     */
    private long teamId;
    private long playerId;
    private long seasonNumber;

    private double rating;
}