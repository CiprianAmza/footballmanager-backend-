package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.Asset;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.AssetRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Buy/sell of personal assets (houses, cars) for a {@link Human}, adjusting
 * {@link Human#getWealth()} accordingly. Share assets are managed by
 * {@link ShareMarketService}; this service only deals with HOUSE/CAR.
 */
@Service
public class AssetService {

    @Autowired
    private AssetRepository assetRepository;
    @Autowired
    private HumanRepository humanRepository;
    @Autowired
    private MatchEngineConfig engineConfig;

    public List<Asset> listAssets(long humanId) {
        return assetRepository.findAllByOwnerHumanId(humanId);
    }

    /** Default catalog price for a HOUSE/CAR (used when the caller sends no price). */
    public long defaultPrice(Asset.AssetType type) {
        MatchEngineConfig.Boardroom b = engineConfig.getBoardroom();
        return type == Asset.AssetType.HOUSE ? b.getHouseDefaultPrice() : b.getCarDefaultPrice();
    }

    /**
     * Buy a personal asset of the given type. Deducts {@code value} from the
     * human's wealth (must be sufficient) and records the asset.
     *
     * @throws IllegalArgumentException on bad type or insufficient wealth.
     */
    public Asset buyAsset(long humanId, Asset.AssetType type, String name, long value) {
        if (type == Asset.AssetType.SHARES) {
            throw new IllegalArgumentException("Use the share market to buy club shares");
        }
        Human human = humanRepository.findById(humanId)
                .orElseThrow(() -> new IllegalArgumentException("Human not found: " + humanId));
        if (value <= 0) value = defaultPrice(type);
        if (human.getWealth() < value) {
            throw new IllegalArgumentException("Insufficient wealth");
        }
        human.setWealth(human.getWealth() - value);
        humanRepository.save(human);

        Asset asset = new Asset();
        asset.setOwnerHumanId(humanId);
        asset.setType(type);
        asset.setName(name != null && !name.isBlank() ? name : type.name());
        asset.setValue(value);
        asset.setClubTeamId(0L);
        return assetRepository.save(asset);
    }

    /**
     * Sell a personal asset (HOUSE/CAR). Credits its value back to wealth and
     * deletes the asset. SHARES assets must be sold via {@link ShareMarketService}.
     *
     * @throws IllegalArgumentException if the asset is missing, not owned, or shares.
     */
    public void sellAsset(long humanId, long assetId) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + assetId));
        if (asset.getOwnerHumanId() != humanId) {
            throw new IllegalArgumentException("Asset not owned by human");
        }
        if (asset.getType() == Asset.AssetType.SHARES) {
            throw new IllegalArgumentException("Sell club shares via the share market");
        }
        Human human = humanRepository.findById(humanId)
                .orElseThrow(() -> new IllegalArgumentException("Human not found: " + humanId));
        human.setWealth(human.getWealth() + asset.getValue());
        humanRepository.save(human);
        assetRepository.delete(asset);
    }
}
