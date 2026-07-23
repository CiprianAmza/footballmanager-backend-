package com.footballmanagergamesimulator.economy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(name = "portfolio_position",
        uniqueConstraints = @UniqueConstraint(name = "uk_portfolio_account_instrument",
                columnNames = {"account_id", "instrument_id"}))
public class PortfolioPosition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(name = "account_id", nullable = false)
    private long accountId;
    @Column(name = "profile_id", nullable = false)
    private long profileId;
    @Column(name = "instrument_id", nullable = false)
    private long instrumentId;
    @Column(nullable = false)
    private long quantity;
    @Column(name = "total_cost_basis", nullable = false)
    private long totalCostBasis;
    @Version
    private long version;

    public long getId() { return id; }
    public long getAccountId() { return accountId; }
    public void setAccountId(long value) { this.accountId = value; }
    public long getProfileId() { return profileId; }
    public void setProfileId(long value) { this.profileId = value; }
    public long getInstrumentId() { return instrumentId; }
    public void setInstrumentId(long value) { this.instrumentId = value; }
    public long getQuantity() { return quantity; }
    public void setQuantity(long value) { this.quantity = value; }
    public long getTotalCostBasis() { return totalCostBasis; }
    public void setTotalCostBasis(long value) { this.totalCostBasis = value; }
    public long getVersion() { return version; }
}
