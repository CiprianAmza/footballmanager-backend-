package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.person.CareerType;
import com.footballmanagergamesimulator.person.ControlType;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class WealthQueryService {

    private final PersonalEconomyBootstrapService bootstrapService;
    private final PersonalAccountRepository accountRepository;
    private final PersonalLedgerEntryRepository ledgerRepository;
    private final PersonProfileRepository profileRepository;
    private final OwnedAssetRepository ownedAssetRepository;
    private final AssetCatalogItemRepository catalogRepository;
    private final RegentEconomyProperties properties;

    public WealthQueryService(PersonalEconomyBootstrapService bootstrapService,
                              PersonalAccountRepository accountRepository,
                              PersonalLedgerEntryRepository ledgerRepository,
                              PersonProfileRepository profileRepository,
                              OwnedAssetRepository ownedAssetRepository,
                              AssetCatalogItemRepository catalogRepository,
                              RegentEconomyProperties properties) {
        this.bootstrapService = bootstrapService;
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.profileRepository = profileRepository;
        this.ownedAssetRepository = ownedAssetRepository;
        this.catalogRepository = catalogRepository;
        this.properties = properties;
    }

    public EconomyDtos.AccountView account(long profileId) {
        bootstrapService.ensureAllAccounts();
        return accountView(requireAccount(profileId));
    }

    public EconomyDtos.LedgerPage ledger(long profileId, int page, int size) {
        PersonalAccount account = requireAccount(profileId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        var result = ledgerRepository.findAllByAccountId(account.getId(),
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id")));
        return new EconomyDtos.LedgerPage(result.stream().map(this::ledgerView).toList(),
                safePage, safeSize, result.getTotalElements(), result.getTotalPages());
    }

    public EconomyDtos.WealthView wealth(long profileId) {
        PersonalAccount account = requireAccount(profileId);
        long assets = ownedAssetRepository.sumCurrentValue(account.getId(), OwnedAssetStatus.OWNED);
        return wealth(account, assets);
    }

    public EconomyDtos.PublicProfileView publicProfile(long profileId) {
        bootstrapService.ensureAllAccounts();
        PersonProfile profile = profileRepository.findById(profileId)
                .orElseThrow(() -> new EconomyConflictException("PROFILE_NOT_FOUND", "Person profile was not found"));
        return publicProfile(profile, wealth(profileId));
    }

    public EconomyDtos.RankingPage rankings(String role, String control, String sort,
                                            int page, int size) {
        bootstrapService.ensureAllAccounts();
        RoleFilter roleFilter = RoleFilter.parse(role);
        ControlFilter controlFilter = ControlFilter.parse(control);
        RankingSort rankingSort = RankingSort.parse(sort);
        Map<Long, PersonProfile> profiles = new HashMap<>();
        profileRepository.findAll().forEach(profile -> profiles.put(profile.getId(), profile));
        Map<Long, Long> assetsByAccount = new HashMap<>();
        for (OwnedAsset asset : ownedAssetRepository.findAllByStatus(OwnedAssetStatus.OWNED)) {
            assetsByAccount.merge(asset.getAccountId(), asset.getCurrentValue(), Math::addExact);
        }

        List<Rankable> rows = new ArrayList<>();
        for (PersonalAccount account : accountRepository.findAll()) {
            PersonProfile profile = profiles.get(account.getProfileId());
            if (profile == null || !roleFilter.matches(profile) || !controlFilter.matches(profile)) continue;
            EconomyDtos.WealthView wealth = wealth(account, assetsByAccount.getOrDefault(account.getId(), 0L));
            rows.add(new Rankable(profile, wealth));
        }
        rows.sort(rankingSort.comparator().thenComparingLong(row -> row.profile().getId()));

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        int from = Math.min(rows.size(), safePage * safeSize);
        int to = Math.min(rows.size(), from + safeSize);
        List<EconomyDtos.RankingEntry> content = new ArrayList<>();
        for (int index = from; index < to; index++) {
            Rankable row = rows.get(index);
            content.add(new EconomyDtos.RankingEntry(index + 1L,
                    publicProfile(row.profile(), row.wealth())));
        }
        int pages = rows.isEmpty() ? 0 : (rows.size() + safeSize - 1) / safeSize;
        return new EconomyDtos.RankingPage(List.copyOf(content), safePage, safeSize, rows.size(), pages);
    }

    public List<EconomyDtos.CatalogItemView> catalog() {
        return catalogRepository.findAllByActiveTrueOrderByAssetTypeAscApartmentRoomsAscPurchasePriceAsc()
                .stream().map(this::catalogView).toList();
    }

    public List<EconomyDtos.OwnedAssetView> assets(long profileId) {
        PersonalAccount account = requireAccount(profileId);
        Map<Long, AssetCatalogItem> catalog = new HashMap<>();
        catalogRepository.findAll().forEach(item -> catalog.put(item.getId(), item));
        return ownedAssetRepository.findAllByAccountIdAndStatusOrderByIdAsc(account.getId(), OwnedAssetStatus.OWNED)
                .stream().map(asset -> assetView(asset, catalog.get(asset.getCatalogItemId()))).toList();
    }

    public EconomyDtos.AssetMutationView mutationView(PersonalAssetService.AssetMutation mutation) {
        AssetCatalogItem item = catalogRepository.findById(mutation.asset().getCatalogItemId()).orElse(null);
        return new EconomyDtos.AssetMutationView(assetView(mutation.asset(), item),
                accountView(mutation.account()), mutation.replayed());
    }

    private PersonalAccount requireAccount(long profileId) {
        return accountRepository.findByProfileId(profileId)
                .orElseThrow(() -> new EconomyConflictException("ACCOUNT_NOT_FOUND", "Personal account is missing"));
    }

    private EconomyDtos.AccountView accountView(PersonalAccount account) {
        return new EconomyDtos.AccountView(account.getId(), account.getProfileId(), money(account.getCashBalance()),
                money(account.getLifetimeCareerEarnings()), money(account.getRealizedInvestmentGain()),
                account.getVersion());
    }

    private EconomyDtos.LedgerEntryView ledgerView(PersonalLedgerEntry entry) {
        return new EconomyDtos.LedgerEntryView(entry.getId(), entry.getEntryType(), entry.getSignedAmount(),
                entry.getCareerEarningsDelta(), entry.getBalanceAfter(), entry.getSeasonNumber(), entry.getGameDay(),
                entry.getCorrelationId(), entry.getIdempotencyKey(), entry.getCounterpartTeamId(),
                entry.getCounterpartAssetId(), entry.getDescription());
    }

    private EconomyDtos.WealthView wealth(PersonalAccount account, long assetValue) {
        long investments = 0;
        long clubEquity = 0;
        long netWorth;
        try {
            netWorth = Math.addExact(Math.addExact(account.getCashBalance(), assetValue),
                    Math.addExact(investments, clubEquity));
        } catch (ArithmeticException exception) {
            throw new EconomyConflictException("MONEY_OVERFLOW", "Net worth exceeds supported range");
        }
        return new EconomyDtos.WealthView(account.getProfileId(), money(account.getCashBalance()),
                money(assetValue), money(investments), money(clubEquity), money(netWorth),
                money(account.getLifetimeCareerEarnings()), money(account.getRealizedInvestmentGain()));
    }

    private EconomyDtos.PublicProfileView publicProfile(PersonProfile profile, EconomyDtos.WealthView wealth) {
        return new EconomyDtos.PublicProfileView(profile.getId(), profile.getDisplayName(), profile.getCareerType(),
                profile.getControlType(), profile.isActive(), profile.isRetired(), wealth);
    }

    private EconomyDtos.CatalogItemView catalogView(AssetCatalogItem item) {
        return new EconomyDtos.CatalogItemView(item.getId(), item.getCode(), item.getAssetType(),
                item.getApartmentRooms(), item.getName(), item.getIconKey(), money(item.getPurchasePrice()),
                item.getResaleHaircutBps());
    }

    private EconomyDtos.OwnedAssetView assetView(OwnedAsset asset, AssetCatalogItem item) {
        String code = item == null ? "UNKNOWN" : item.getCode();
        AssetType type = item == null ? AssetType.CAR : item.getAssetType();
        Integer rooms = item == null ? null : item.getApartmentRooms();
        String name = item == null ? "Unknown asset" : item.getName();
        return new EconomyDtos.OwnedAssetView(asset.getId(), asset.getCatalogItemId(), code, type, rooms, name,
                money(asset.getPurchasePrice()), money(asset.getCurrentValue()), asset.getPurchaseSeason(),
                asset.getPurchaseDay(), asset.getStatus(), asset.getSalePrice() == null ? null : money(asset.getSalePrice()));
    }

    private EconomyDtos.Money money(long amount) {
        return new EconomyDtos.Money(amount, properties.getEconomy().getCurrency(), 0);
    }

    private record Rankable(PersonProfile profile, EconomyDtos.WealthView wealth) { }

    private enum RoleFilter {
        ALL, MANAGERS, CHAIRMEN, PLAYERS;
        static RoleFilter parse(String value) {
            try { return value == null ? ALL : valueOf(value.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Invalid ranking role"); }
        }
        boolean matches(PersonProfile profile) {
            return this == ALL || (this == MANAGERS && profile.getCareerType() == CareerType.MANAGER)
                    || (this == CHAIRMEN && profile.getCareerType() == CareerType.CHAIRMAN)
                    || (this == PLAYERS && profile.getCareerType() == CareerType.PLAYER);
        }
    }

    private enum ControlFilter {
        ALL, AI, USER;
        static ControlFilter parse(String value) {
            try { return value == null ? ALL : valueOf(value.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Invalid control filter"); }
        }
        boolean matches(PersonProfile profile) {
            return this == ALL || profile.getControlType() == ControlType.valueOf(name());
        }
    }

    private enum RankingSort {
        NET_WORTH, CASH, CAREER_EARNINGS;
        static RankingSort parse(String value) {
            try { return value == null ? NET_WORTH : valueOf(value.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Invalid ranking sort"); }
        }
        Comparator<Rankable> comparator() {
            return switch (this) {
                case NET_WORTH -> Comparator.comparingLong((Rankable row) -> row.wealth().netWorth().amount()).reversed();
                case CASH -> Comparator.comparingLong((Rankable row) -> row.wealth().cash().amount()).reversed();
                case CAREER_EARNINGS -> Comparator
                        .comparingLong((Rankable row) -> row.wealth().lifetimeCareerEarnings().amount()).reversed();
            };
        }
    }
}
