package com.footballmanagergamesimulator.service.squad;

import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The press's predicted league finish of a club: the same ranking as the media prediction page.
 *
 * <p>The ranking picks the best eleven of every club in every formation, which takes seconds. The first-day
 * meetings ask for it on every page load and after every answer, so a league's ranking is worked out once per
 * game day and reused until the day changes or a save is loaded. It is worked out outside the caller's
 * transaction: inside a write transaction (closing the board meeting, holding the squad meeting) every one of
 * its queries first dirty-checks all the squads loaded so far (measured: 24 s instead of 1.7 s).
 */
@Service
public class LeaguePredictionService {

    private record Ranking(int season, int day, List<Long> teamIds) {}

    private final TacticController tacticController;
    private final GameCalendarRepository calendarRepository;
    private final Map<Long, Ranking> rankings = new ConcurrentHashMap<>();

    public LeaguePredictionService(@Lazy TacticController tacticController, GameCalendarRepository calendarRepository) {
        this.tacticController = tacticController;
        this.calendarRepository = calendarRepository;
    }

    /** {position (1 = favourite), teams}; {0, 0} when the club is not in that league. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
    public int[] predictedFinish(long competitionId, long teamId) {
        List<Long> ranking = ranking(competitionId);
        int index = ranking.indexOf(teamId);
        return index < 0 ? new int[]{0, 0} : new int[]{index + 1, ranking.size()};
    }

    /** A loaded save is another world: forget every ranking worked out before it. */
    public void forget() {
        rankings.clear();
    }

    private List<Long> ranking(long competitionId) {
        GameCalendar today = calendarRepository.findTopByOrderBySeasonDesc().orElse(null);
        int season = today == null ? 0 : today.getSeason();
        int day = today == null ? 0 : today.getCurrentDay();
        Ranking known = rankings.get(competitionId);
        if (known != null && known.season() == season && known.day() == day) return known.teamIds();
        List<Long> teamIds = tacticController
                .getTeamRatingByManagerTacticForCompetitionIdAndBestPossibleTactic(competitionId, true).stream()
                .map(view -> view.getManagerTeamTacticView().getTeamId())
                .toList();
        rankings.put(competitionId, new Ranking(season, day, teamIds));
        return teamIds;
    }
}
