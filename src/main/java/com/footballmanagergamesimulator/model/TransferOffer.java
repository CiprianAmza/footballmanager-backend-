package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="transfer_offer")
public class TransferOffer {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private long id;

    private long playerId;
    private String playerName;
    private long fromTeamId;
    private String fromTeamName;
    private long toTeamId;
    private String toTeamName;
    private long offerAmount;
    private long askingPrice;
    private String status;
    private int seasonNumber;
    private String direction;
    private long createdAt;

}
