package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Owns the in-memory game state previously held on {@code CompetitionController}:
 * the current {@link Round} (cached so callers don't hit the DB every read) and
 * the per-type competition-id caches used by hot match-simulation paths.
 *
 * <p>{@code @PostConstruct} drives the one-time cold start / warm resume via
 * {@link GameInitializationService} and stores the resulting Round here.
 * Services that mutate Round (e.g. season transition incrementing the season
 * counter) get the same instance via {@link #getRound()} and call
 * {@code roundRepository.save(...)} themselves; the cached reference stays in
 * sync because it's the same object.
 */
@Service
public class GameStateService {

    private final GameInitializationService gameInitializationService;
    private final CompetitionRepository competitionRepository;

    private Round round;

    private Set<Long> cachedLeagueCompIds;
    private Set<Long> cachedCupCompIds;
    private Set<Long> cachedSecondLeagueCompIds;

    public GameStateService(GameInitializationService gameInitializationService,
                            CompetitionRepository competitionRepository) {
        this.gameInitializationService = gameInitializationService;
        this.competitionRepository = competitionRepository;
    }

    @PostConstruct
    public void initialize() {
        this.round = gameInitializationService.initializeRound();
    }

    public Round getRound() {
        return round;
    }

    public int currentSeason() {
        return (int) round.getSeason();
    }

    public long currentRound() {
        return round.getRound();
    }

    public Set<Long> getLeagueCompetitionIdsCached() {
        if (cachedLeagueCompIds == null) {
            cachedLeagueCompIds = competitionRepository.findIdsByTypeId(1);
        }
        return cachedLeagueCompIds;
    }

    public Set<Long> getCupCompetitionIdsCached() {
        if (cachedCupCompIds == null) {
            cachedCupCompIds = competitionRepository.findIdsByTypeId(2);
        }
        return cachedCupCompIds;
    }

    public Set<Long> getSecondLeagueCompetitionIdsCached() {
        if (cachedSecondLeagueCompIds == null) {
            cachedSecondLeagueCompIds = competitionRepository.findIdsByTypeId(3);
        }
        return cachedSecondLeagueCompIds;
    }
}
