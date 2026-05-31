package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.Asset;
import com.footballmanagergamesimulator.model.ClubShareholding;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.AssetRepository;
import com.footballmanagergamesimulator.repository.ClubShareholdingRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Buy/sell of club shares against a notional market. Money moves between the
 * human's {@link Human#getWealth()} and the market (it does NOT touch the club's
 * {@code totalFinances} — shares trade on a notional market, not the balance
 * sheet). Updates the {@link ClubShareholding} stake and keeps the convenience
 * SHARES {@link Asset} mirror in sync, then re-syncs ownership.
 *
 * <p>Dividends are intentionally NOT implemented in Faza 1-2 (passive income is a
 * later phase). The price model is a simple notional valuation; see
 * {@link MatchEngineConfig.Boardroom}.
 */
@Service
public class ShareMarketService {

    @Autowired
    private ClubShareholdingRepository shareholdingRepository;
    @Autowired
    private AssetRepository assetRepository;
    @Autowired
    private HumanRepository humanRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private MatchEngineConfig engineConfig;
    @Autowired
    private OwnershipService ownershipService;

    /** Notional price of 1% of the given club's shares (config-driven, floored). */
    public long pricePerPercent(long teamId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
        MatchEngineConfig.Boardroom b = engineConfig.getBoardroom();
        long valuation = (long) (team.getTotalFinances() * b.getSharePricePerPercentOfFinances())
                + team.getReputation() * b.getSharePriceReputationValue() / 100L;
        return Math.max(b.getSharePriceFloorPerPercent(), valuation);
    }

    /** Total cost to buy {@code percent}% of the club at current notional price. */
    public long quoteCost(long teamId, double percent) {
        return (long) (pricePerPercent(teamId) * percent);
    }

    /**
     * Buy {@code percent}% of a club. Deducts cost from wealth, raises the stake,
     * mirrors a SHARES asset, and re-syncs ownership.
     *
     * @throws IllegalArgumentException on bad percent, insufficient wealth, or
     *                                  if the stake would exceed 100%.
     */
    public ClubShareholding buyShares(long humanId, long teamId, double percent) {
        if (percent <= 0) throw new IllegalArgumentException("Percent must be positive");
        Human human = humanRepository.findById(humanId)
                .orElseThrow(() -> new IllegalArgumentException("Human not found: " + humanId));
        teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));

        ClubShareholding holding = shareholdingRepository.findByHumanIdAndTeamId(humanId, teamId)
                .orElseGet(() -> {
                    ClubShareholding c = new ClubShareholding();
                    c.setHumanId(humanId);
                    c.setTeamId(teamId);
                    c.setPercent(0);
                    return c;
                });
        if (holding.getPercent() + percent > 100.0) {
            throw new IllegalArgumentException("Cannot own more than 100% of a club");
        }

        long cost = quoteCost(teamId, percent);
        if (human.getWealth() < cost) {
            throw new IllegalArgumentException("Insufficient wealth");
        }
        human.setWealth(human.getWealth() - cost);
        humanRepository.save(human);

        holding.setPercent(holding.getPercent() + percent);
        shareholdingRepository.save(holding);

        syncShareAsset(humanId, teamId, holding.getPercent());
        ownershipService.syncOwnership(humanId, teamId);
        return holding;
    }

    /**
     * Sell {@code percent}% of a club back to the notional market. Credits wealth,
     * lowers the stake (deleting at zero), updates the mirror asset, and re-syncs
     * ownership.
     *
     * @throws IllegalArgumentException on bad percent or selling more than owned.
     */
    public ClubShareholding sellShares(long humanId, long teamId, double percent) {
        if (percent <= 0) throw new IllegalArgumentException("Percent must be positive");
        ClubShareholding holding = shareholdingRepository.findByHumanIdAndTeamId(humanId, teamId)
                .orElseThrow(() -> new IllegalArgumentException("No shareholding to sell"));
        if (percent > holding.getPercent() + 1e-9) {
            throw new IllegalArgumentException("Cannot sell more than owned");
        }
        Human human = humanRepository.findById(humanId)
                .orElseThrow(() -> new IllegalArgumentException("Human not found: " + humanId));

        long proceeds = quoteCost(teamId, percent);
        human.setWealth(human.getWealth() + proceeds);
        humanRepository.save(human);

        double remaining = holding.getPercent() - percent;
        if (remaining <= 1e-9) {
            shareholdingRepository.delete(holding);
            holding.setPercent(0);
            removeShareAsset(humanId, teamId);
        } else {
            holding.setPercent(remaining);
            shareholdingRepository.save(holding);
            syncShareAsset(humanId, teamId, remaining);
        }
        ownershipService.syncOwnership(humanId, teamId);
        return holding;
    }

    /**
     * Distress-sale helper: a cash-strapped human liquidates a slice of a club
     * holding to raise liquidity. Sells the smaller of {@code maxPercent} and the
     * full holding. Returns the proceeds credited to wealth (0 if nothing held).
     */
    public long distressSale(long humanId, long teamId, double maxPercent) {
        ClubShareholding holding = shareholdingRepository.findByHumanIdAndTeamId(humanId, teamId).orElse(null);
        if (holding == null || holding.getPercent() <= 0) return 0;
        double toSell = Math.min(maxPercent, holding.getPercent());
        long proceeds = quoteCost(teamId, toSell);
        sellShares(humanId, teamId, toSell);
        return proceeds;
    }

    // --- keep the convenience SHARES Asset mirror in sync ---

    private void syncShareAsset(long humanId, long teamId, double percent) {
        Asset asset = findShareAsset(humanId, teamId);
        long value = quoteCost(teamId, percent);
        if (asset == null) {
            asset = new Asset();
            asset.setOwnerHumanId(humanId);
            asset.setType(Asset.AssetType.SHARES);
            asset.setClubTeamId(teamId);
        }
        Team team = teamRepository.findById(teamId).orElse(null);
        asset.setName((team != null ? team.getName() : "Club " + teamId)
                + String.format(" shares (%.1f%%)", percent));
        asset.setValue(value);
        assetRepository.save(asset);
    }

    private void removeShareAsset(long humanId, long teamId) {
        Asset asset = findShareAsset(humanId, teamId);
        if (asset != null) assetRepository.delete(asset);
    }

    private Asset findShareAsset(long humanId, long teamId) {
        List<Asset> shares = assetRepository.findAllByOwnerHumanIdAndType(humanId, Asset.AssetType.SHARES);
        for (Asset a : shares) {
            if (a.getClubTeamId() != null && a.getClubTeamId() == teamId) return a;
        }
        return null;
    }
}
