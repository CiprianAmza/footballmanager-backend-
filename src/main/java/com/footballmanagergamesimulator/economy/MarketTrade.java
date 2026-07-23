package com.footballmanagergamesimulator.economy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "market_trade",
        uniqueConstraints = @UniqueConstraint(name = "uk_market_trade_retry",
                columnNames = {"account_id", "idempotency_key"}),
        indexes = @Index(name = "idx_market_trade_account", columnList = "account_id,id"))
public class MarketTrade {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(name = "account_id", nullable = false)
    private long accountId;
    @Column(name = "profile_id", nullable = false)
    private long profileId;
    @Column(name = "instrument_id", nullable = false)
    private long instrumentId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MarketTradeSide side;
    @Column(nullable = false)
    private long quantity;
    @Column(name = "unit_price", nullable = false)
    private long unitPrice;
    @Column(name = "gross_amount", nullable = false)
    private long grossAmount;
    @Column(name = "cost_basis_amount", nullable = false)
    private long costBasisAmount;
    @Column(name = "realized_gain", nullable = false)
    private long realizedGain;
    @Column(name = "season_number", nullable = false)
    private int seasonNumber;
    @Column(name = "game_day", nullable = false)
    private int gameDay;
    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;
    @Column(name = "correlation_id", nullable = false, length = 160)
    private String correlationId;
    @Column(name = "cash_balance_after", nullable = false)
    private long cashBalanceAfter;
    @Column(name = "quantity_after", nullable = false)
    private long quantityAfter;
    @Column(name = "cost_basis_after", nullable = false)
    private long costBasisAfter;

    public long getId() { return id; }
    public long getAccountId() { return accountId; }
    public void setAccountId(long value) { this.accountId = value; }
    public long getProfileId() { return profileId; }
    public void setProfileId(long value) { this.profileId = value; }
    public long getInstrumentId() { return instrumentId; }
    public void setInstrumentId(long value) { this.instrumentId = value; }
    public MarketTradeSide getSide() { return side; }
    public void setSide(MarketTradeSide value) { this.side = value; }
    public long getQuantity() { return quantity; }
    public void setQuantity(long value) { this.quantity = value; }
    public long getUnitPrice() { return unitPrice; }
    public void setUnitPrice(long value) { this.unitPrice = value; }
    public long getGrossAmount() { return grossAmount; }
    public void setGrossAmount(long value) { this.grossAmount = value; }
    public long getCostBasisAmount() { return costBasisAmount; }
    public void setCostBasisAmount(long value) { this.costBasisAmount = value; }
    public long getRealizedGain() { return realizedGain; }
    public void setRealizedGain(long value) { this.realizedGain = value; }
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
    public long getCostBasisAfter() { return costBasisAfter; }
    public void setCostBasisAfter(long value) { this.costBasisAfter = value; }
}
