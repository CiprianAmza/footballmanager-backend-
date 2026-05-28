package com.footballmanagergamesimulator.engine.tuner;

import com.footballmanagergamesimulator.engine.invariants.InvariantResult;

import java.util.List;

/**
 * Output of one tuning run.
 *
 * @param best                the candidate with the highest fitness found
 * @param bestFitness         that candidate's fitness score (higher = better)
 * @param bestResults         per-invariant results for {@code best}
 * @param trajectory          best-fitness-so-far at each phase milestone
 * @param totalCandidates     number of distinct candidates evaluated
 * @param randomSearchPhaseSize how many of those came from the random-search phase
 * @param hillClimbPhaseSize  how many came from the hill-climb phase
 */
public record TuningResult(
        ParameterSpace.Candidate best,
        double bestFitness,
        List<InvariantResult> bestResults,
        List<TrajectoryPoint> trajectory,
        int totalCandidates,
        int randomSearchPhaseSize,
        int hillClimbPhaseSize) {

    public int passCount() {
        return (int) bestResults.stream().filter(InvariantResult::passed).count();
    }

    public int totalInvariants() {
        return bestResults.size();
    }

    /** One snapshot in the tuning trajectory — for plotting / convergence diagnosis. */
    public record TrajectoryPoint(String phase, int iteration, double bestFitness) {}
}
