package com.footballmanagergamesimulator.economy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "market_price_snapshot",
        uniqueConstraints = @UniqueConstraint(name = "uk_market_price_day",
                columnNames = {"instrument_id", "season_number", "game_day"}),
        indexes = @Index(name = "idx_market_price_history",
                columnList = "instrument_id,season_number,game_day"))
public class MarketPriceSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(name = "instrument_id", nullable = false)
    private long instrumentId;
    @Column(name = "season_number", nullable = false)
    private int seasonNumber;
    @Column(name = "game_day", nullable = false)
    private int gameDay;
    @Column(name = "previous_close", nullable = false)
    private long previousClose;
    @Column(name = "close_price", nullable = false)
    private long closePrice;
    @Column(name = "weekly_anchor_price", nullable = false)
    private long weeklyAnchorPrice;
    @Column(name = "daily_change_bps", nullable = false)
    private int dailyChangeBps;
    @Column(name = "algorithm_version", nullable = false, length = 32)
    private String algorithmVersion = DeterministicMarketPriceService.MARKET_V1;
    @Column(name = "deterministic_hash", nullable = false)
    private long deterministicHash;

    public long getId() { return id; }
    public long getInstrumentId() { return instrumentId; }
    public void setInstrumentId(long value) { this.instrumentId = value; }
    public int getSeasonNumber() { return seasonNumber; }
    public void setSeasonNumber(int value) { this.seasonNumber = value; }
    public int getGameDay() { return gameDay; }
    public void setGameDay(int value) { this.gameDay = value; }
    public long getPreviousClose() { return previousClose; }
    public void setPreviousClose(long value) { this.previousClose = value; }
    public long getClosePrice() { return closePrice; }
    public void setClosePrice(long value) { this.closePrice = value; }
    public long getWeeklyAnchorPrice() { return weeklyAnchorPrice; }
    public void setWeeklyAnchorPrice(long value) { this.weeklyAnchorPrice = value; }
    public int getDailyChangeBps() { return dailyChangeBps; }
    public void setDailyChangeBps(int value) { this.dailyChangeBps = value; }
    public String getAlgorithmVersion() { return algorithmVersion; }
    public void setAlgorithmVersion(String value) { this.algorithmVersion = value; }
    public long getDeterministicHash() { return deterministicHash; }
    public void setDeterministicHash(long value) { this.deterministicHash = value; }
}
