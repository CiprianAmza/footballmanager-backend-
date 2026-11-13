package com.footballmanagergamesimulator.service.squad;

import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.frontend.ManagerBestTeamTacticView;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;

/** The press's predicted league finish of a club: the same ranking as the media prediction page. */
@Service
public class LeaguePredictionService {

    private final TacticController tacticController;

    public LeaguePredictionService(@Lazy TacticController tacticController) {
        this.tacticController = tacticController;
    }

    /** {position (1 = favourite), teams}; {0, 0} when the club is not in that league. */
    public int[] predictedFinish(long competitionId, long teamId) {
        List<ManagerBestTeamTacticView> ranking =
                tacticController.getTeamRatingByManagerTacticForCompetitionIdAndBestPossibleTactic(competitionId, true);
        for (int i = 0; i < ranking.size(); i++) {
            if (ranking.get(i).getManagerTeamTacticView().getTeamId() == teamId) return new int[]{i + 1, ranking.size()};
        }
        return new int[]{0, 0};
    }
}
