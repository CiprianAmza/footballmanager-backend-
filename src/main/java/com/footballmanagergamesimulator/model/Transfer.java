package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="transfer")
public class Transfer {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private long id;

    private long sellTeamId;
    private long buyTeamId;
    private long playerId;
    private long playerAge;
    private long playerTransferValue;
    private double rating;
    private long seasonNumber;

    private String sellTeamName;
    private String buyTeamName;
    private String playerName;


}
