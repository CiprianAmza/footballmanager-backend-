package com.footballmanagergamesimulator.economy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(name = "takeover_quote", uniqueConstraints = {
        @UniqueConstraint(name = "uk_takeover_quote_public", columnNames = "quote_key"),
        @UniqueConstraint(name = "uk_takeover_quote_retry", columnNames = {"buyer_account_id", "idempotency_key"})
})
public class TakeoverQuote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(name = "quote_key", nullable = false, length = 36)
    private String quoteKey;
    @Column(name = "buyer_account_id", nullable = false)
    private long buyerAccountId;
    @Column(name = "buyer_profile_id", nullable = false)
    private long buyerProfileId;
    @Column(name = "instrument_id", nullable = false)
    private long instrumentId;
    @Column(name = "team_id", nullable = false)
    private long teamId;
    @Column(name = "valuation_formula_version", nullable = false, length = 40)
    private String valuationFormulaVersion;
    @Column(name = "valuation_state_version", nullable = false, length = 64)
    private String valuationStateVersion;
    @Column(name = "instrument_version", nullable = false)
    private long instrumentVersion;
    @Column(name = "issued_shares", nullable = false)
    private long issuedShares;
    @Column(name = "shares_to_acquire", nullable = false)
    private long sharesToAcquire;
    @Column(name = "unit_price", nullable = false)
    private long unitPrice;
    @Column(name = "premium_bps", nullable = false)
    private int premiumBps;
    @Column(name = "total_consideration", nullable = false)
    private long totalConsideration;
    @Column(name = "quoted_season", nullable = false)
    private int quotedSeason;
    @Column(name = "quoted_day", nullable = false)
    private int quotedDay;
    @Column(name = "expires_absolute_day", nullable = false)
    private long expiresAbsoluteDay;
    @Column(name = "idempotency_key", nullable = false, length = 140)
    private String idempotencyKey;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TakeoverQuoteStatus status = TakeoverQuoteStatus.OPEN;
    @Version
    private long version;

    public long getId() { return id; }
    public String getQuoteKey() { return quoteKey; }
    public void setQuoteKey(String value) { this.quoteKey = value; }
    public long getBuyerAccountId() { return buyerAccountId; }
    public void setBuyerAccountId(long value) { this.buyerAccountId = value; }
    public long getBuyerProfileId() { return buyerProfileId; }
    public void setBuyerProfileId(long value) { this.buyerProfileId = value; }
    public long getInstrumentId() { return instrumentId; }
    public void setInstrumentId(long value) { this.instrumentId = value; }
    public long getTeamId() { return teamId; }
    public void setTeamId(long value) { this.teamId = value; }
    public String getValuationFormulaVersion() { return valuationFormulaVersion; }
    public void setValuationFormulaVersion(String value) { this.valuationFormulaVersion = value; }
    public String getValuationStateVersion() { return valuationStateVersion; }
    public void setValuationStateVersion(String value) { this.valuationStateVersion = value; }
    public long getInstrumentVersion() { return instrumentVersion; }
    public void setInstrumentVersion(long value) { this.instrumentVersion = value; }
    public long getIssuedShares() { return issuedShares; }
    public void setIssuedShares(long value) { this.issuedShares = value; }
    public long getSharesToAcquire() { return sharesToAcquire; }
    public void setSharesToAcquire(long value) { this.sharesToAcquire = value; }
    public long getUnitPrice() { return unitPrice; }
    public void setUnitPrice(long value) { this.unitPrice = value; }
    public int getPremiumBps() { return premiumBps; }
    public void setPremiumBps(int value) { this.premiumBps = value; }
    public long getTotalConsideration() { return totalConsideration; }
    public void setTotalConsideration(long value) { this.totalConsideration = value; }
    public int getQuotedSeason() { return quotedSeason; }
    public void setQuotedSeason(int value) { this.quotedSeason = value; }
    public int getQuotedDay() { return quotedDay; }
    public void setQuotedDay(int value) { this.quotedDay = value; }
    public long getExpiresAbsoluteDay() { return expiresAbsoluteDay; }
    public void setExpiresAbsoluteDay(long value) { this.expiresAbsoluteDay = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { this.idempotencyKey = value; }
    public TakeoverQuoteStatus getStatus() { return status; }
    public void setStatus(TakeoverQuoteStatus value) { this.status = value; }
    public long getVersion() { return version; }
}
