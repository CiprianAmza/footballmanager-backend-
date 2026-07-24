package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.regent.market.core.AdviceAction;
import com.footballmanagergamesimulator.regent.market.core.MarketRiskClass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;

@Entity
@Table(name = "trader_advice_recommendation", uniqueConstraints =
        @UniqueConstraint(name = "uk_trader_advice_day", columnNames =
                {"contract_id", "instrument_id", "season_number", "game_day"}))
public class TraderAdviceRecommendation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(name = "contract_id", nullable = false)
    private long contractId;
    @Column(name = "instrument_id", nullable = false)
    private long instrumentId;
    @Column(name = "season_number", nullable = false)
    private int seasonNumber;
    @Column(name = "game_day", nullable = false)
    private int gameDay;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private AdviceAction action;
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_class", nullable = false, length = 24)
    private MarketRiskClass riskClass;
    @Column(name = "horizon_days", nullable = false)
    private int horizonDays;
    @Column(nullable = false, precision = 8, scale = 4)
    private BigDecimal confidence;
    @Column(nullable = false, precision = 8, scale = 4)
    private BigDecimal risk;
    @Column(name = "trailing_return", nullable = false, precision = 20, scale = 8)
    private BigDecimal trailingReturn;
    @Column(name = "observed_volatility", nullable = false, precision = 20, scale = 8)
    private BigDecimal observedVolatility;
    @Column(nullable = false, length = 300)
    private String explanation;
    @Column(name = "model_version", nullable = false, length = 32)
    private String modelVersion;

    public long getId() { return id; }
    public long getContractId() { return contractId; }
    public void setContractId(long value) { contractId = value; }
    public long getInstrumentId() { return instrumentId; }
    public void setInstrumentId(long value) { instrumentId = value; }
    public int getSeasonNumber() { return seasonNumber; }
    public void setSeasonNumber(int value) { seasonNumber = value; }
    public int getGameDay() { return gameDay; }
    public void setGameDay(int value) { gameDay = value; }
    public AdviceAction getAction() { return action; }
    public void setAction(AdviceAction value) { action = value; }
    public MarketRiskClass getRiskClass() { return riskClass; }
    public void setRiskClass(MarketRiskClass value) { riskClass = value; }
    public int getHorizonDays() { return horizonDays; }
    public void setHorizonDays(int value) { horizonDays = value; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal value) { confidence = value; }
    public BigDecimal getRisk() { return risk; }
    public void setRisk(BigDecimal value) { risk = value; }
    public BigDecimal getTrailingReturn() { return trailingReturn; }
    public void setTrailingReturn(BigDecimal value) { trailingReturn = value; }
    public BigDecimal getObservedVolatility() { return observedVolatility; }
    public void setObservedVolatility(BigDecimal value) { observedVolatility = value; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String value) { explanation = value; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String value) { modelVersion = value; }
}
