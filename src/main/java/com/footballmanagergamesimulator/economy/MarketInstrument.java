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
@Table(name = "market_instrument", uniqueConstraints = {
        @UniqueConstraint(name = "uk_market_instrument_code", columnNames = "code"),
        @UniqueConstraint(name = "uk_market_instrument_team", columnNames = "team_id")
})
public class MarketInstrument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(nullable = false, length = 80, unique = true)
    private String code;
    @Enumerated(EnumType.STRING)
    @Column(name = "instrument_type", nullable = false, length = 20)
    private MarketInstrumentType instrumentType;
    @Column(name = "team_id", unique = true)
    private Long teamId;
    @Column(nullable = false, length = 160)
    private String name;
    @Column(name = "total_supply", nullable = false)
    private long totalSupply;
    @Column(name = "available_supply", nullable = false)
    private long availableSupply;
    @Column(name = "current_price", nullable = false)
    private long currentPrice;
    @Column(name = "price_seed", nullable = false)
    private long priceSeed;
    @Column(name = "price_algorithm_version", nullable = false, length = 32)
    private String priceAlgorithmVersion = DeterministicMarketPriceService.MARKET_V1;
    @Column(name = "daily_limit_bps", nullable = false)
    private int dailyLimitBps;
    @Column(name = "weekly_limit_bps", nullable = false)
    private int weeklyLimitBps;
    @Column(nullable = false)
    private boolean active = true;
    @Version
    private long version;

    public long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public MarketInstrumentType getInstrumentType() { return instrumentType; }
    public void setInstrumentType(MarketInstrumentType type) { this.instrumentType = type; }
    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public long getTotalSupply() { return totalSupply; }
    public void setTotalSupply(long value) { this.totalSupply = value; }
    public long getAvailableSupply() { return availableSupply; }
    public void setAvailableSupply(long value) { this.availableSupply = value; }
    public long getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(long value) { this.currentPrice = value; }
    public long getPriceSeed() { return priceSeed; }
    public void setPriceSeed(long value) { this.priceSeed = value; }
    public String getPriceAlgorithmVersion() { return priceAlgorithmVersion; }
    public void setPriceAlgorithmVersion(String value) { this.priceAlgorithmVersion = value; }
    public int getDailyLimitBps() { return dailyLimitBps; }
    public void setDailyLimitBps(int value) { this.dailyLimitBps = value; }
    public int getWeeklyLimitBps() { return weeklyLimitBps; }
    public void setWeeklyLimitBps(int value) { this.weeklyLimitBps = value; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public long getVersion() { return version; }
}
