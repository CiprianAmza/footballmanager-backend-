package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.RelationParty;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.service.RelationService.Change;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How players grow attached (decided 2026-11-12): every season played at a club adds +10 to PLAYER → CLUB and
 * +10 to PLAYER → MANAGER for the club's manager; coming up from a club's youth adds +20 to PLAYER → CLUB.
 */
@Service
public class PlayerBondService {

    public static final int PER_SEASON = 10;
    public static final int YOUTH_PRODUCT = 20;

    @Autowired HumanRepository humanRepository;
    @Autowired RelationService relationService;

    /** Season end, before anyone moves: each active player bonds with his club and its manager. */
    @Transactional
    public int recordSeasonPlayed() {
        Map<Long, Long> managerByTeam = new HashMap<>();
        for (Human manager : humanRepository.findAllByTypeId(TypeNames.MANAGER_TYPE)) {
            if (manager.getTeamId() != null && manager.getTeamId() > 0) {
                managerByTeam.putIfAbsent(manager.getTeamId(), manager.getId());
            }
        }
        List<Change> changes = new ArrayList<>();
        for (Human player : humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE)) {
            if (player.isRetired() || player.getTeamId() == null || player.getTeamId() <= 0) continue;
            changes.add(new Change(RelationParty.PLAYER, player.getId(), RelationParty.CLUB, player.getTeamId(), PER_SEASON));
            Long managerId = managerByTeam.get(player.getTeamId());
            if (managerId != null) {
                changes.add(new Change(RelationParty.PLAYER, player.getId(), RelationParty.MANAGER, managerId, PER_SEASON));
            }
        }
        relationService.changeAll(changes);
        return changes.size();
    }

    /** A youth player promoted to his club's first team. */
    public void recordYouthProduct(long playerId, long clubId) {
        relationService.change(RelationParty.PLAYER, playerId, RelationParty.CLUB, clubId, YOUTH_PRODUCT);
    }
}
