package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/** One instalment of a transfer fee, paid by the buyer to the seller at the start of {@code dueSeason}. */
@Entity
@Data
@Table(name = "transfer_installment")
public class TransferInstallment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long playerId;
    private String playerName;
    private long payerTeamId;
    private long payeeTeamId;
    private long amount;
    private int dueSeason;
    private boolean paid;
}
