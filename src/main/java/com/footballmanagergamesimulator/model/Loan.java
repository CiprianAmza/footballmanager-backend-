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

    // Option/obligation to buy
    @Column(columnDefinition = "bigint default 0")
    private long buyOptionFee;         // 0 = no option; >0 = price to buy permanently

    @Column(columnDefinition = "boolean default false")
    private boolean buyObligatory;     // true = must buy at end of loan

    // Wage contribution: % of wage paid by parent club (0-100)
    @Column(columnDefinition = "int default 0")
    private int parentWageContribution;

}
