package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.regent.market.core.AdviceAction;
import com.footballmanagergamesimulator.regent.market.core.MarketRiskClass;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

public final class MarketDtos {
    private MarketDtos() { }

    public record InstrumentView(long id, String code, MarketInstrumentType type, Long teamId,
                                 String name, EconomyDtos.Money price, long totalSupply,
                                 long availableSupply, MarketRiskClass riskClass,
                                 int dailyLimitBps, int weeklyLimitBps,
                                 String algorithmVersion, EconomyDtos.Money underlyingClubValuation,
                                 String clubValuationVersion) { }
    public record PriceView(long id, int season, int day, EconomyDtos.Money previousClose,
                            EconomyDtos.Money closePrice, EconomyDtos.Money weeklyAnchorPrice,
                            int dailyChangeBps, String algorithmVersion) { }
    public record PositionView(long instrumentId, String code, MarketInstrumentType type, Long teamId,
                               String name, MarketRiskClass riskClass, long quantity, EconomyDtos.Money costBasis,
                               EconomyDtos.Money marketValue, EconomyDtos.Money unrealizedGain) { }
    public record PortfolioView(List<PositionView> positions, EconomyDtos.Money totalCostBasis,
                                EconomyDtos.Money marketValue, EconomyDtos.Money unrealizedGain,
                                EconomyDtos.Money realizedGain) { }
    public record TradeRequest(@Positive long instrumentId, @NotNull MarketTradeSide side,
                               @Positive long quantity, @NotBlank String idempotencyKey) { }
    public record TradeView(long id, long instrumentId, String code, MarketTradeSide side,
                            long quantity, EconomyDtos.Money unitPrice, EconomyDtos.Money grossAmount,
                            EconomyDtos.Money costBasisAmount, EconomyDtos.Money realizedGain,
                            int season, int day, String idempotencyKey,
                            EconomyDtos.Money cashBalanceAfter, long quantityAfter,
                            EconomyDtos.Money costBasisAfter, boolean replayed) { }
    public record TradePage(List<TradeView> content, int page, int size,
                            long totalElements, int totalPages) { }
    public record GameDateView(int season, int day) { }
    public record AdviserHireOptionView(String optionCode, String adviserName, int skill,
                                        int reputation, EconomyDtos.Money salaryPerDay,
                                        int durationDays, String modelVersion) { }
    public record AdviserContractView(long contractId, String adviserCode, String adviserName,
                                      int skill, int reputation, EconomyDtos.Money salaryPerDay,
                                      GameDateView startDate, GameDateView endDate,
                                      String status, String terminationReason,
                                      String modelVersion, boolean replayed) { }
    public record AdviserDashboardView(GameDateView currentDate, AdviserContractView currentContract,
                                       List<AdviserHireOptionView> hireOptions) { }
    public record HireAdviserRequest(@NotBlank String optionCode, @NotBlank String idempotencyKey) { }
    public record AdviceView(long recommendationId, long instrumentId, String instrumentCode,
                             String instrumentName, AdviceAction action, MarketRiskClass riskClass,
                             int season, int day, int horizonDays, BigDecimal confidence,
                             BigDecimal risk, BigDecimal trailingReturn, BigDecimal observedVolatility,
                             String explanation, String modelVersion, boolean replayed) { }
}
