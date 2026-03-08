package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "loan")
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long playerId;
    private String playerName;
    private long parentTeamId;
    private String parentTeamName;
    private long loanTeamId;
    private String loanTeamName;
    private int seasonNumber;
    private String status;
    private long loanFee;

}
