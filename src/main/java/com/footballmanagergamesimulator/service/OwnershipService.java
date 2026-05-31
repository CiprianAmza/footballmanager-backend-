package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.ClubShareholding;
import com.footballmanagergamesimulator.model.Ownership;
import com.footballmanagergamesimulator.repository.ClubShareholdingRepository;
import com.footballmanagergamesimulator.repository.OwnershipRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives club ownership from {@link ClubShareholding} stakes. A human owns a
 * club when their stake exceeds the config threshold
 * ({@code match.engine.boardroom.ownership-threshold-percent}, default 50%).
 *
 * <p>Ownership is derived (no hidden state), but when a stake first crosses the
 * threshold an explicit {@link Ownership} row is written for clarity/queryability,
 * and removed when the stake drops back below it. Call
 * {@link #syncOwnership(long, long)} after any shareholding change.
 */
@Service
public class OwnershipService {

    @Autowired
    private ClubShareholdingRepository shareholdingRepository;
    @Autowired
    private OwnershipRepository ownershipRepository;
    @Autowired
    private MatchEngineConfig engineConfig;

    /** Ownership threshold percent (config-driven). */
    public double ownershipThreshold() {
        return engineConfig.getBoardroom().getOwnershipThresholdPercent();
    }

    /** True if the human's stake in the team is strictly above the threshold. */
    public boolean isOwner(long humanId, long teamId) {
        return shareholdingRepository.findByHumanIdAndTeamId(humanId, teamId)
                .map(s -> s.getPercent() > ownershipThreshold())
                .orElse(false);
    }

    /** Team ids the human currently owns (stake above threshold). */
    public List<Long> ownedClubIds(long humanId) {
        double threshold = ownershipThreshold();
        List<Long> owned = new ArrayList<>();
        for (ClubShareholding s : shareholdingRepository.findAllByHumanId(humanId)) {
            if (s.getPercent() > threshold) {
                owned.add(s.getTeamId());
            }
        }
        return owned;
    }

    /**
     * Reconcile the explicit {@link Ownership} record with the human's current
     * stake. Adds a record when crossing above the threshold, removes it when
     * dropping below. Idempotent.
     */
    public void syncOwnership(long humanId, long teamId) {
        boolean owner = isOwner(humanId, teamId);
        var existing = ownershipRepository.findByHumanIdAndTeamId(humanId, teamId);
        if (owner && existing.isEmpty()) {
            Ownership o = new Ownership();
            o.setHumanId(humanId);
            o.setTeamId(teamId);
            ownershipRepository.save(o);
        } else if (!owner && existing.isPresent()) {
            ownershipRepository.delete(existing.get());
        }
    }
}
