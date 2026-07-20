package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "financial_record")
public class FinancialRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private int seasonNumber;

    private String category;
    // Categories: MATCH_DAY, TV_INCOME, MERCHANDISING, SPONSORSHIP, TRANSFER_SALE,
    //             TRANSFER_BUY, WAGES, PRIZE_MONEY, OWNER_INJECTION, OWNER_WITHDRAWAL,
    //             FINES, FINANCIAL_ADJUSTMENT, LOAN_FEE, SCOUT_COST, OTHER

    private String description;
    private long amount; // positive = income, negative = expense
    @Column(name = "record_day")
    private int day; // day of season when this record was created
}
