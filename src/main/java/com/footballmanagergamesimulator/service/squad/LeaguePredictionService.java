package com.footballmanagergamesimulator.service.squad;

import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The press's predicted league finish of a club: the same ranking as the media prediction page.
 *
 * <p>The ranking picks the best eleven of every club in every formation, which takes seconds. The first-day
 * meetings ask for it on every page load and after every answer, so a league's ranking is worked out once per
 * game day and reused until the day changes or a save is loaded. It is worked out outside the caller's
 * transaction: inside a write transaction (closing the board meeting, holding the squad meeting) every one of
 * its queries first dirty-checks all the squads loaded so far (measured: 24 s instead of 1.7 s).
 *
 * <p>The first day's page asks for it ahead ({@link #prepareLeagueOf}), in the background, so that even the first
 * meeting opens at once; a meeting that comes while it is still being worked out waits for it.
 */
@Service
public class LeaguePredictionService {

    private record Ranking(int season, int day, CompletableFuture<List<Long>> teamIds) {
        boolean usableOn(int[] today) {
            return season == today[0] && day == today[1] && !teamIds.isCompletedExceptionally();
        }
    }

    private final TacticController tacticController;
    private final GameCalendarRepository calendarRepository;
    private final TeamRepository teamRepository;
    private final TransactionTemplate readOnly;
    private final ExecutorService background = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "league-prediction");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Long, Ranking> rankings = new ConcurrentHashMap<>();

    public LeaguePredictionService(@Lazy TacticController tacticController, GameCalendarRepository calendarRepository,
                                   TeamRepository teamRepository, PlatformTransactionManager transactionManager) {
        this.tacticController = tacticController;
        this.calendarRepository = calendarRepository;
        this.teamRepository = teamRepository;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /** {position (1 = favourite), teams}; {0, 0} when the club is not in that league. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
    public int[] predictedFinish(long competitionId, long teamId) {
        int[] today = today();
        List<Long> ranking = null;
        Ranking known = rankings.get(competitionId);
        if (known != null && known.usableOn(today)) {
            try {
                ranking = known.teamIds().join();
            } catch (RuntimeException backgroundFailed) {
                // worked out again below, here, so the failure reaches the caller as it always did
            }
        }
        if (ranking == null) {
            ranking = compute(competitionId);
            rankings.put(competitionId, new Ranking(today[0], today[1], CompletableFuture.completedFuture(ranking)));
        }
        int index = ranking.indexOf(teamId);
        return index < 0 ? new int[]{0, 0} : new int[]{index + 1, ranking.size()};
    }

    /** Starts working out the ranking of the club's league in the background, unless today's is known or on its way. */
    public void prepareLeagueOf(long teamId) {
        long competitionId = teamRepository.findById(teamId).map(team -> team.getCompetitionId()).orElse(0L);
        if (competitionId <= 0) return;
        int[] today = today();
        CompletableFuture<List<Long>> work = new CompletableFuture<>();
        Ranking fresh = new Ranking(today[0], today[1], work);
        if (rankings.compute(competitionId, (id, known) -> known != null && known.usableOn(today) ? known : fresh) != fresh)
            return;
        background.execute(() -> {
            try {
                work.complete(readOnly.execute(status -> compute(competitionId)));
            } catch (RuntimeException failed) {
                work.completeExceptionally(failed);
            }
        });
    }

    /** A loaded save is another world: forget every ranking worked out before it. */
    public void forget() {
        rankings.clear();
    }

    @PreDestroy
    void stop() {
        background.shutdownNow();
    }

    private List<Long> compute(long competitionId) {
        return tacticController
                .getTeamRatingByManagerTacticForCompetitionIdAndBestPossibleTactic(competitionId, true).stream()
                .map(view -> view.getManagerTeamTacticView().getTeamId())
                .toList();
    }

    private int[] today() {
        GameCalendar today = calendarRepository.findTopByOrderBySeasonDesc().orElse(null);
        return today == null ? new int[]{0, 0} : new int[]{today.getSeason(), today.getCurrentDay()};
    }
}
