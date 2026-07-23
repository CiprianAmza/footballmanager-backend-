package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.person.CareerType;
import com.footballmanagergamesimulator.person.ControlType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.List;

public final class EconomyDtos {
    private EconomyDtos() { }

    public record Money(long amount, String currency, int minorUnitScale) { }
    public record AccountView(long accountId, long profileId, Money cash,
                              Money lifetimeCareerEarnings, Money realizedInvestmentGain, long version) { }
    public record LedgerEntryView(long id, LedgerEntryType type, long signedAmount,
                                  long careerEarningsDelta, long balanceAfter,
                                  int season, int day, String correlationId,
                                  String idempotencyKey, Long counterpartTeamId,
                                  Long counterpartAssetId, String description) { }
    public record LedgerPage(List<LedgerEntryView> content, int page, int size,
                             long totalElements, int totalPages) { }
    public record CatalogItemView(long id, String code, AssetType type, Integer apartmentRooms,
                                  String name, String iconKey, Money purchasePrice,
                                  int resaleHaircutBps) { }
    public record OwnedAssetView(long id, long catalogItemId, String catalogCode,
                                 AssetType type, Integer apartmentRooms, String name,
                                 Money purchasePrice, Money currentValue,
                                 int purchaseSeason, int purchaseDay, OwnedAssetStatus status,
                                 Money salePrice) { }
    public record WealthView(long profileId, Money cash, Money assetValue,
                             Money investmentValue, Money clubEquityValue, Money netWorth,
                             Money lifetimeCareerEarnings, Money realizedInvestmentGain) { }
    public record PublicProfileView(long profileId, String displayName, CareerType careerType,
                                    ControlType controlType, boolean active, boolean retired,
                                    WealthView wealth) { }
    public record RankingEntry(long rank, PublicProfileView profile) { }
    public record RankingPage(List<RankingEntry> content, int page, int size,
                              long totalElements, int totalPages) { }
    public record PurchaseAssetRequest(@Positive long catalogItemId,
                                       @NotBlank String idempotencyKey) { }
    public record SellAssetRequest(@NotBlank String idempotencyKey) { }
    public record AssetMutationView(OwnedAssetView asset, AccountView account, boolean replayed) { }
    public record ApiError(String code, String message) { }
}
