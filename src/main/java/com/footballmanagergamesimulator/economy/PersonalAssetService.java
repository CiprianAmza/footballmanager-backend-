package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.person.PersonProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PersonalAssetService {

    private final PersonalAccountRepository accountRepository;
    private final AssetCatalogItemRepository catalogRepository;
    private final OwnedAssetRepository ownedAssetRepository;
    private final PersonalAccountingService accountingService;
    private final EconomyClock clock;

    public PersonalAssetService(PersonalAccountRepository accountRepository,
                                AssetCatalogItemRepository catalogRepository,
                                OwnedAssetRepository ownedAssetRepository,
                                PersonalAccountingService accountingService,
                                EconomyClock clock) {
        this.accountRepository = accountRepository;
        this.catalogRepository = catalogRepository;
        this.ownedAssetRepository = ownedAssetRepository;
        this.accountingService = accountingService;
        this.clock = clock;
    }

    public List<AssetCatalogItem> catalog() {
        return catalogRepository.findAllByActiveTrueOrderByAssetTypeAscApartmentRoomsAscPurchasePriceAsc();
    }

    public List<OwnedAsset> owned(long profileId) {
        PersonalAccount account = accountRepository.findByProfileId(profileId)
                .orElseThrow(() -> new EconomyConflictException("ACCOUNT_NOT_FOUND", "Personal account is missing"));
        return ownedAssetRepository.findAllByAccountIdAndStatusOrderByIdAsc(account.getId(), OwnedAssetStatus.OWNED);
    }

    @Transactional
    public AssetMutation purchase(PersonProfile profile, long catalogItemId, String idempotencyKey) {
        requireKey(idempotencyKey);
        PersonalAccount account = accountRepository.findByProfileIdForUpdate(profile.getId())
                .orElseThrow(() -> new EconomyConflictException("ACCOUNT_NOT_FOUND", "Personal account is missing"));
        OwnedAsset existing = ownedAssetRepository
                .findByAccountIdAndPurchaseIdempotencyKey(account.getId(), idempotencyKey).orElse(null);
        if (existing != null) {
            if (existing.getCatalogItemId() != catalogItemId) {
                throw new EconomyConflictException("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency key was already used for a different asset purchase");
            }
            return new AssetMutation(existing, account, true);
        }

        AssetCatalogItem item = catalogRepository.findById(catalogItemId)
                .filter(AssetCatalogItem::isActive)
                .orElseThrow(() -> new EconomyConflictException("CATALOG_ITEM_NOT_FOUND", "Asset is not available"));
        EconomyClock.GameDate date = clock.current();
        OwnedAsset asset = new OwnedAsset();
        asset.setAccountId(account.getId());
        asset.setProfileId(profile.getId());
        asset.setCatalogItemId(item.getId());
        asset.setPurchasePrice(item.getPurchasePrice());
        asset.setCurrentValue(item.getPurchasePrice());
        asset.setPurchaseSeason(date.season());
        asset.setPurchaseDay(date.day());
        asset.setStatus(OwnedAssetStatus.OWNED);
        asset.setPurchaseIdempotencyKey(idempotencyKey);
        asset = ownedAssetRepository.saveAndFlush(asset);

        String correlation = "ASSET-PURCHASE:" + idempotencyKey;
        accountingService.postLocked(account, LedgerEntryType.ASSET_PURCHASE,
                Math.negateExact(item.getPurchasePrice()), 0, date.season(), date.day(),
                correlation, correlation, null, asset.getId(), "Purchased " + item.getName());
        return new AssetMutation(asset, account, false);
    }

    @Transactional
    public AssetMutation sell(PersonProfile profile, long ownedAssetId, String idempotencyKey) {
        requireKey(idempotencyKey);
        PersonalAccount account = accountRepository.findByProfileIdForUpdate(profile.getId())
                .orElseThrow(() -> new EconomyConflictException("ACCOUNT_NOT_FOUND", "Personal account is missing"));
        OwnedAsset replay = ownedAssetRepository.findByAccountIdAndSaleIdempotencyKey(
                account.getId(), idempotencyKey).orElse(null);
        if (replay != null) {
            if (replay.getId() != ownedAssetId) {
                throw new EconomyConflictException("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency key was already used for a different asset sale");
            }
            return new AssetMutation(replay, account, true);
        }
        OwnedAsset asset = ownedAssetRepository.findOwnedForUpdate(ownedAssetId, account.getId())
                .orElseThrow(() -> new EconomyConflictException("OWNED_ASSET_NOT_FOUND", "Owned asset was not found"));
        if (asset.getStatus() == OwnedAssetStatus.SOLD) {
            throw new EconomyConflictException("ASSET_ALREADY_SOLD", "Asset has already been sold");
        }
        AssetCatalogItem item = catalogRepository.findById(asset.getCatalogItemId())
                .orElseThrow(() -> new EconomyConflictException("CATALOG_ITEM_NOT_FOUND", "Asset catalogue entry is missing"));
        long retainedBps = 10_000L - item.getResaleHaircutBps();
        long proceeds;
        try {
            proceeds = Math.multiplyExact(asset.getCurrentValue(), retainedBps) / 10_000L;
        } catch (ArithmeticException exception) {
            throw new EconomyConflictException("MONEY_OVERFLOW", "Asset valuation exceeds supported range");
        }
        EconomyClock.GameDate date = clock.current();
        asset.setStatus(OwnedAssetStatus.SOLD);
        asset.setSaleIdempotencyKey(idempotencyKey);
        asset.setSalePrice(proceeds);
        asset.setSaleSeason(date.season());
        asset.setSaleDay(date.day());
        asset.setCurrentValue(0);
        ownedAssetRepository.save(asset);

        String correlation = "ASSET-SALE:" + idempotencyKey;
        accountingService.postLocked(account, LedgerEntryType.ASSET_SALE,
                proceeds, 0, date.season(), date.day(), correlation, correlation,
                null, asset.getId(), "Sold " + item.getName());
        return new AssetMutation(asset, account, false);
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 120) {
            throw new IllegalArgumentException("idempotencyKey must contain 1 to 120 characters");
        }
    }

    public record AssetMutation(OwnedAsset asset, PersonalAccount account, boolean replayed) { }
}
