package com.footballmanagergamesimulator.economy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public final class MarketDtos {
    private MarketDtos() { }

    public record InstrumentView(long id, String code, MarketInstrumentType type, Long teamId,
                                 String name, EconomyDtos.Money price, long totalSupply,
                                 long availableSupply, int dailyLimitBps, int weeklyLimitBps,
                                 String algorithmVersion) { }
    public record PriceView(long id, int season, int day, EconomyDtos.Money previousClose,
                            EconomyDtos.Money closePrice, EconomyDtos.Money weeklyAnchorPrice,
                            int dailyChangeBps, String algorithmVersion) { }
    public record PositionView(long instrumentId, String code, MarketInstrumentType type, Long teamId,
                               String name, long quantity, EconomyDtos.Money costBasis,
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
}
