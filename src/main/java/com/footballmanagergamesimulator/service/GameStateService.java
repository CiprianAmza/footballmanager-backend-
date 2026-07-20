package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
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
    private final RoundRepository roundRepository;

    private Round round;

    private volatile Set<Long> cachedLeagueCompIds;
    private volatile Set<Long> cachedCupCompIds;
    private volatile Set<Long> cachedSecondLeagueCompIds;

    public GameStateService(GameInitializationService gameInitializationService,
                            CompetitionRepository competitionRepository,
                            RoundRepository roundRepository) {
        this.gameInitializationService = gameInitializationService;
        this.competitionRepository = competitionRepository;
        this.roundRepository = roundRepository;
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

    /**
     * Publishes a committed Round state to readers. Season-transition code calls
     * this from an after-commit callback so REST endpoints never observe a
     * half-built season.
     */
    public synchronized void publishRoundState(long currentRound, long currentSeason) {
        round.setRound(currentRound);
        round.setSeason(currentSeason);
    }

    public synchronized Set<Long> getLeagueCompetitionIdsCached() {
        if (cachedLeagueCompIds == null) {
            cachedLeagueCompIds = Set.copyOf(competitionRepository.findIdsByTypeId(1));
        }
        return cachedLeagueCompIds;
    }

    public synchronized Set<Long> getCupCompetitionIdsCached() {
        if (cachedCupCompIds == null) {
            cachedCupCompIds = Set.copyOf(competitionRepository.findIdsByTypeId(2));
        }
        return cachedCupCompIds;
    }

    public synchronized Set<Long> getSecondLeagueCompetitionIdsCached() {
        if (cachedSecondLeagueCompIds == null) {
            cachedSecondLeagueCompIds = Set.copyOf(competitionRepository.findIdsByTypeId(3));
        }
        return cachedSecondLeagueCompIds;
    }

    /** Refresh all process-local state after the native save-game importer runs. */
    public synchronized void reloadAfterImport() {
        this.round = roundRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("Imported save contains no Round row"));
        this.cachedLeagueCompIds = null;
        this.cachedCupCompIds = null;
        this.cachedSecondLeagueCompIds = null;
    }
}
