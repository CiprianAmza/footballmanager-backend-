package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;

import java.util.Map;
import java.util.function.ToIntFunction;

/** A coach's rating on a training type: the average of the type's two coaching attributes. */
public final class CoachRatings {

    private static final Map<String, ToIntFunction<Human>> ATTRIBUTES = Map.ofEntries(
            Map.entry("Attacking", Human::getCoachingAttacking),
            Map.entry("Set Pieces", Human::getCoachingSetPieces),
            Map.entry("Defending", Human::getCoachingDefending),
            Map.entry("Duels", Human::getCoachingDuels),
            Map.entry("Technical", Human::getCoachingTechnical),
            Map.entry("Ball Work", Human::getCoachingBallWork),
            Map.entry("Mental", Human::getCoachingMental),
            Map.entry("Motivating", Human::getMotivating),
            Map.entry("Tactical", Human::getCoachingTactical),
            Map.entry("Game Reading", Human::getCoachingGameReading),
            Map.entry("Fitness", Human::getCoachingFitness),
            Map.entry("Conditioning", Human::getCoachingConditioning),
            Map.entry("GK", Human::getCoachingGK),
            Map.entry("Shot-stopping", Human::getCoachingShotStopping));

    private CoachRatings() {
    }

    /**
     * The coach's rating on {@code type} (1-20). A second attribute still at 0 (staff created
     * before it existed) counts as equal to the first.
     */
    public static double rating(Human coach, TrainingType type) {
        int first = ATTRIBUTES.get(type.coachAttributes().get(0)).applyAsInt(coach);
        int second = ATTRIBUTES.get(type.coachAttributes().get(1)).applyAsInt(coach);
        if (second <= 0) second = first;
        return Math.max(1, (first + second) / 2.0);
    }
}
