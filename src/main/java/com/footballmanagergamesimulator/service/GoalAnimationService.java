package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.GoalAnimationData;
import com.footballmanagergamesimulator.model.Human;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Thin coordinator for the goal-animation engine. Round-level work lives in
 * the three scenario-specific generators ({@link GoalAnimationOpenPlayGenerator},
 * {@link GoalAnimationPenaltyGenerator}, {@link GoalAnimationFreeKickGenerator});
 * shared infrastructure (kit attach, stoppage stash, math helpers, eleven-vs-eleven
 * selection) lives in {@link GoalAnimationContext}.
 *
 * <p>Surface preserved as delegate methods so existing callers ({@code MatchController},
 * {@code LiveMatchSimulationService}, {@code LiveMatchSession}) don't churn.
 */
@Service
public class GoalAnimationService {

    @Autowired private GoalAnimationContext context;
    @Autowired private GoalAnimationOpenPlayGenerator openPlayGenerator;
    @Autowired private GoalAnimationPenaltyGenerator penaltyGenerator;
    @Autowired private GoalAnimationFreeKickGenerator freeKickGenerator;

    // ===== Lifecycle =====

    public void setMatchStoppage(int firstHalfStoppage) {
        context.setMatchStoppage(firstHalfStoppage);
    }

    public void clearMatchStoppage() {
        context.clearMatchStoppage();
    }

    /** Set the canonical animation seed (fixtureKey + slotIndex) for the next
     *  generation on this thread, so a same-minute goal gets a distinct, restart-stable
     *  animation. Clear it right after with {@link #clearAnimationSeed()}. */
    public void setAnimationSeed(long seed) {
        context.setSeedOverride(seed);
    }

    public void clearAnimationSeed() {
        context.clearSeedOverride();
    }

    // ===== Generators =====

    public GoalAnimationData generate(
            List<Human> attackingAll,
            List<Human> defendingAll,
            Human scorer,
            Human assister,
            long scoringTeamId,
            long defendingTeamId,
            long homeTeamId,
            int minute) {
        return openPlayGenerator.generate(attackingAll, defendingAll, scorer, assister,
                scoringTeamId, defendingTeamId, homeTeamId, minute);
    }

    public GoalAnimationData generate(
            List<Human> attackingAll,
            List<Human> defendingAll,
            Human scorer,
            Human assister,
            long scoringTeamId,
            long defendingTeamId,
            long homeTeamId,
            int minute,
            String outcome) {
        return openPlayGenerator.generate(attackingAll, defendingAll, scorer, assister,
                scoringTeamId, defendingTeamId, homeTeamId, minute, outcome);
    }

    public GoalAnimationData generatePenalty(
            List<Human> attackingAll,
            List<Human> defendingAll,
            Human taker,
            long scoringTeamId,
            long defendingTeamId,
            long homeTeamId,
            int minute,
            boolean isGoal) {
        return penaltyGenerator.generatePenalty(attackingAll, defendingAll, taker,
                scoringTeamId, defendingTeamId, homeTeamId, minute, isGoal);
    }

    public GoalAnimationData generateFreeKick(
            List<Human> attackingAll,
            List<Human> defendingAll,
            Human taker,
            long scoringTeamId,
            long defendingTeamId,
            long homeTeamId,
            int minute,
            String outcome) {
        return freeKickGenerator.generateFreeKick(attackingAll, defendingAll, taker,
                scoringTeamId, defendingTeamId, homeTeamId, minute, outcome);
    }
}
