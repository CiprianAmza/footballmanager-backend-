package com.footballmanagergamesimulator.economy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public final class ClubDtos {
    private ClubDtos() { }

    public record ValuationView(String formulaVersion, String stateVersion,
                                EconomyDtos.Money squadMarketValue,
                                EconomyDtos.Money clubCash, EconomyDtos.Money debt,
                                EconomyDtos.Money dueObligations, EconomyDtos.Money netCash,
                                EconomyDtos.Money stadiumFacilitiesValue,
                                EconomyDtos.Money reputationBrandValue,
                                int recentPerformanceBps,
                                EconomyDtos.Money recentPerformanceValue,
                                EconomyDtos.Money totalValue) { }
    public record HoldingView(long profileId, String displayName, boolean protectedUser,
                              long quantity, int stakeBps, EconomyDtos.Money equityValue,
                              boolean controlling) { }
    public record CapTableView(long issuedShares, long freeFloat, int controlThresholdBps,
                               Long controllingProfileId, String controllingDisplayName,
                               long version, List<HoldingView> holdings) { }
    public record TreasuryView(EconomyDtos.Money balance, EconomyDtos.Money debt,
                               EconomyDtos.Money monthlyWages,
                               EconomyDtos.Money protectedReserve,
                               EconomyDtos.Money dueObligations,
                               EconomyDtos.Money distributableCash,
                               boolean withdrawalRestricted) { }
    public record ClubSummary(long teamId, String name, EconomyDtos.Money valuation,
                              Long controllingProfileId, String controllingDisplayName,
                              boolean controlledByPrincipal) { }
    public record Dashboard(long teamId, String name, ValuationView valuation,
                            CapTableView capTable, TreasuryView treasury,
                            boolean controlledByPrincipal) { }

    public record QuoteRequest(@NotBlank String idempotencyKey) { }
    public record TakeoverRequest(@NotBlank String quoteId, @NotBlank String idempotencyKey) { }
    public record TakeoverQuoteView(String quoteId, long teamId, long sharesToAcquire,
                                    EconomyDtos.Money unitPrice, int premiumBps,
                                    EconomyDtos.Money totalConsideration,
                                    String valuationFormulaVersion, String valuationStateVersion,
                                    long instrumentVersion, long expiresAbsoluteDay,
                                    TakeoverQuoteStatus status, boolean replayed) { }
    public record TakeoverExecutionView(String executionId, String quoteId, long teamId,
                                        long sharesAcquired, EconomyDtos.Money unitPrice,
                                        EconomyDtos.Money totalConsideration,
                                        EconomyDtos.Money cashBalanceAfter,
                                        long quantityAfter, int season, int day,
                                        boolean replayed) { }

    public record TreasuryTransferRequest(@NotNull ClubCashTransferDirection direction,
                                          @Positive long amount,
                                          @NotBlank String idempotencyKey) { }
    public record TreasuryTransferView(String transferId, long teamId,
                                       ClubCashTransferDirection direction,
                                       EconomyDtos.Money amount,
                                       EconomyDtos.Money personalBalanceAfter,
                                       EconomyDtos.Money clubBalanceAfter,
                                       EconomyDtos.Money distributableBefore,
                                       String correlationId, int season, int day,
                                       boolean replayed) { }
}
