package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Restores every active club player to the common new-season baseline. */
@Service
public class NewSeasonPlayerReadinessService {

    static final double NEW_SEASON_MORALE = 80.0;
    static final double NEW_SEASON_FITNESS = 80.0;

    private final HumanRepository humanRepository;

    public NewSeasonPlayerReadinessService(HumanRepository humanRepository) {
        this.humanRepository = humanRepository;
    }

    /**
     * Runs once per real season boundary, after retirements, contracts and
     * minimum-squad academy promotions have settled the new club rosters.
     */
    @Transactional
    public int resetActiveTeamPlayers() {
        List<Human> activeTeamPlayers =
                humanRepository.findAllByTypeIdAndRetiredFalseAndTeamIdIsNotNull(TypeNames.PLAYER_TYPE);
        activeTeamPlayers.forEach(player -> {
            player.setMorale(NEW_SEASON_MORALE);
            player.setFitness(NEW_SEASON_FITNESS);
        });
        humanRepository.saveAll(activeTeamPlayers);
        return activeTeamPlayers.size();
    }
}
