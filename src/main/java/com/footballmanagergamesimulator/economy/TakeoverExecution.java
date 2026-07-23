package com.footballmanagergamesimulator.economy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "takeover_execution", uniqueConstraints = {
        @UniqueConstraint(name = "uk_takeover_execution_quote", columnNames = "quote_id"),
        @UniqueConstraint(name = "uk_takeover_execution_retry", columnNames = {"buyer_account_id", "idempotency_key"})
})
public class TakeoverExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(name = "execution_key", nullable = false, length = 36, unique = true)
    private String executionKey;
    @Column(name = "quote_id", nullable = false, unique = true)
    private long quoteId;
    @Column(name = "buyer_account_id", nullable = false)
    private long buyerAccountId;
    @Column(name = "buyer_profile_id", nullable = false)
    private long buyerProfileId;
    @Column(name = "instrument_id", nullable = false)
    private long instrumentId;
    @Column(name = "team_id", nullable = false)
    private long teamId;
    @Column(name = "shares_acquired", nullable = false)
    private long sharesAcquired;
    @Column(name = "unit_price", nullable = false)
    private long unitPrice;
    @Column(name = "total_consideration", nullable = false)
    private long totalConsideration;
    @Column(name = "season_number", nullable = false)
    private int seasonNumber;
    @Column(name = "game_day", nullable = false)
    private int gameDay;
    @Column(name = "idempotency_key", nullable = false, length = 140)
    private String idempotencyKey;
    @Column(name = "correlation_id", nullable = false, length = 120)
    private String correlationId;
    @Column(name = "cash_balance_after", nullable = false)
    private long cashBalanceAfter;
    @Column(name = "quantity_after", nullable = false)
    private long quantityAfter;

    public long getId() { return id; }
    public String getExecutionKey() { return executionKey; }
    public void setExecutionKey(String value) { this.executionKey = value; }
    public long getQuoteId() { return quoteId; }
    public void setQuoteId(long value) { this.quoteId = value; }
    public long getBuyerAccountId() { return buyerAccountId; }
    public void setBuyerAccountId(long value) { this.buyerAccountId = value; }
    public long getBuyerProfileId() { return buyerProfileId; }
    public void setBuyerProfileId(long value) { this.buyerProfileId = value; }
    public long getInstrumentId() { return instrumentId; }
    public void setInstrumentId(long value) { this.instrumentId = value; }
    public long getTeamId() { return teamId; }
    public void setTeamId(long value) { this.teamId = value; }
    public long getSharesAcquired() { return sharesAcquired; }
    public void setSharesAcquired(long value) { this.sharesAcquired = value; }
    public long getUnitPrice() { return unitPrice; }
    public void setUnitPrice(long value) { this.unitPrice = value; }
    public long getTotalConsideration() { return totalConsideration; }
    public void setTotalConsideration(long value) { this.totalConsideration = value; }
    public int getSeasonNumber() { return seasonNumber; }
    public void setSeasonNumber(int value) { this.seasonNumber = value; }
    public int getGameDay() { return gameDay; }
    public void setGameDay(int value) { this.gameDay = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { this.idempotencyKey = value; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String value) { this.correlationId = value; }
    public long getCashBalanceAfter() { return cashBalanceAfter; }
    public void setCashBalanceAfter(long value) { this.cashBalanceAfter = value; }
    public long getQuantityAfter() { return quantityAfter; }
    public void setQuantityAfter(long value) { this.quantityAfter = value; }
}
