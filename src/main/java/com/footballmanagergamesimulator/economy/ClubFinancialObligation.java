package com.footballmanagergamesimulator.economy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "club_financial_obligation")
public class ClubFinancialObligation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(name = "team_id", nullable = false)
    private long teamId;
    @Column(nullable = false, length = 80)
    private String category;
    @Column(name = "amount_remaining", nullable = false)
    private long amountRemaining;
    @Column(name = "due_season", nullable = false)
    private int dueSeason;
    @Column(name = "due_day", nullable = false)
    private int dueDay;
    @Column(name = "restricts_withdrawal", nullable = false)
    private boolean restrictsWithdrawal;
    @Column(nullable = false)
    private boolean settled;

    public long getId() { return id; }
    public long getTeamId() { return teamId; }
    public void setTeamId(long value) { this.teamId = value; }
    public String getCategory() { return category; }
    public void setCategory(String value) { this.category = value; }
    public long getAmountRemaining() { return amountRemaining; }
    public void setAmountRemaining(long value) { this.amountRemaining = value; }
    public int getDueSeason() { return dueSeason; }
    public void setDueSeason(int value) { this.dueSeason = value; }
    public int getDueDay() { return dueDay; }
    public void setDueDay(int value) { this.dueDay = value; }
    public boolean isRestrictsWithdrawal() { return restrictsWithdrawal; }
    public void setRestrictsWithdrawal(boolean value) { this.restrictsWithdrawal = value; }
    public boolean isSettled() { return settled; }
    public void setSettled(boolean value) { this.settled = value; }
}
