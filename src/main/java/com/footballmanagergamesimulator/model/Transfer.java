package com.footballmanagergamesimulator.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
public class Transfer {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private long id;

    private long sellTeamId;
    private long buyTeamId;
    private long playerId;
    private long playerTransferValue;
    private double rating;
    private long seasonNumber;

    private String sellTeamName;
    private String buyTeamName;
    private String playerName;


}
