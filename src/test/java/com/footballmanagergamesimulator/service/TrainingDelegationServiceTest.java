package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import(TrainingDelegationService.class)
class TrainingDelegationServiceTest {

    private static final long TEAM = 10L;
    private static final long OTHER_TEAM = 20L;

    @Autowired private TrainingDelegationService delegation;
    @Autowired private HumanRepository humans;

    /** A coach with every coaching attribute at {@code value}, except technical ones at {@code technical}. */
    private Human coach(long team, int value, int technical) {
        Human coach = new Human();
        coach.setTeamId(team);
        coach.setTypeId(TypeNames.FIRST_TEAM_COACH_TYPE);
        coach.setCoachingAttacking(value);
        coach.setCoachingSetPieces(value);
        coach.setCoachingDefending(value);
        coach.setCoachingDuels(value);
        coach.setCoachingTechnical(technical);
        coach.setCoachingBallWork(technical);
        coach.setCoachingMental(value);
        coach.setMotivating(value);
        coach.setCoachingTactical(value);
        coach.setCoachingGameReading(value);
        coach.setCoachingFitness(value);
        coach.setCoachingConditioning(value);
        coach.setCoachingGK(value);
        coach.setCoachingShotStopping(value);
        return humans.save(coach);
    }

    @Test
    @DisplayName("""
            GIVEN a human team with two coaches: A rated 18 on Technical and 12 elsewhere, B rated 15 everywhere
            WHEN B is delegated to Technical and nothing else is delegated
            THEN Technical counts B's 15 (the choice wins over the better coach),
             and every undelegated type falls back to the club's best coach (15)
            """)
    void humanTeamsUseTheirDelegationsAndFallBackToTheBestCoach() {
        coach(TEAM, 12, 18);
        Human chosen = coach(TEAM, 15, 15);

        delegation.delegate(TEAM, TrainingType.TECHNICAL, chosen.getId());

        Map<TrainingType, Double> ratings = delegation.humanCoachRatings(TEAM);
        assertEquals(15.0, ratings.get(TrainingType.TECHNICAL), 1e-9);
        assertEquals(15.0, ratings.get(TrainingType.MENTAL), 1e-9);
        assertEquals(Map.of(TrainingType.TECHNICAL, chosen.getId()), delegation.delegations(TEAM));
    }

    @Test
    @DisplayName("""
            GIVEN a human team that never delegated (just taken over, or an old save),
             and a delegation to a coach who has since left the club
            WHEN its coach ratings are read
            THEN every type gets the club's best coach, and a team with no coaches counts as level 1
            """)
    void undelegatedHumanTeamsGetTheirBestCoach() {
        Human leaver = coach(TEAM, 20, 20);
        delegation.delegate(TEAM, TrainingType.MENTAL, leaver.getId());
        leaver.setTeamId(OTHER_TEAM);
        humans.save(leaver);
        coach(TEAM, 13, 17);

        Map<TrainingType, Double> ratings = delegation.humanCoachRatings(TEAM);
        assertEquals(17.0, ratings.get(TrainingType.TECHNICAL), 1e-9);
        assertEquals(13.0, ratings.get(TrainingType.MENTAL), 1e-9);
        assertEquals(TrainingDelegationService.NO_COACH,
                delegation.humanCoachRatings(99L).get(TrainingType.MENTAL), 1e-9);
    }

    @Test
    @DisplayName("""
            GIVEN an AI team with two coaches, one better on Technical (18) and one better elsewhere (15 vs 8)
            WHEN its coach ratings are read
            THEN each type gets its best coach: 18 on Technical, 15 on the rest
            """)
    void aiTeamsAreDelegatedOptimally() {
        coach(OTHER_TEAM, 8, 18);
        coach(OTHER_TEAM, 15, 4);

        Map<TrainingType, Double> ratings = delegation.optimalCoachRatings(List.of(OTHER_TEAM)).get(OTHER_TEAM);

        assertEquals(18.0, ratings.get(TrainingType.TECHNICAL), 1e-9);
        assertEquals(15.0, ratings.get(TrainingType.TACTICAL), 1e-9);
    }

    @Test
    @DisplayName("""
            GIVEN a coach of another team, and an older coach whose new second attributes are still 0
            WHEN the first is delegated, and the second's rating is read
            THEN delegating a foreign coach is refused, and the older coach's rating falls back to his first attribute
            """)
    void foreignCoachesAreRefusedAndOldStaffFallsBack() {
        Human foreign = coach(OTHER_TEAM, 10, 10);
        assertThrows(IllegalArgumentException.class,
                () -> delegation.delegate(TEAM, TrainingType.MENTAL, foreign.getId()));

        Human old = new Human();
        old.setCoachingTactical(14);
        assertEquals(14.0, CoachRatings.rating(old, TrainingType.TACTICAL), 1e-9);
        assertTrue(delegation.delegations(TEAM).isEmpty());
    }

    @Test
    @DisplayName("""
            GIVEN a human team with coach A (18 on Technical, 12 elsewhere) and coach B (15 everywhere),
             B delegated to Mental and nothing else delegated
            WHEN the training screen's overview is read
            THEN Mental shows B, delegated, not automatic; Technical shows A, automatic, with A then B as candidates
            """)
    void overviewShowsDelegatedAndAutomaticCoaches() {
        Human a = coach(TEAM, 12, 18);
        Human b = coach(TEAM, 15, 15);
        delegation.delegate(TEAM, TrainingType.MENTAL, b.getId());

        Map<TrainingType, TrainingDelegationService.TypeOverview> byType = new java.util.EnumMap<>(TrainingType.class);
        delegation.overview(TEAM).forEach(row -> byType.put(row.type(), row));

        var mental = byType.get(TrainingType.MENTAL);
        assertEquals(b.getId(), mental.delegatedStaffId());
        assertEquals(b.getId(), mental.effectiveStaffId());
        assertEquals(false, mental.automatic());
        var technical = byType.get(TrainingType.TECHNICAL);
        assertEquals(null, technical.delegatedStaffId());
        assertEquals(a.getId(), technical.effectiveStaffId());
        assertEquals(18.0, technical.effectiveRating(), 1e-9);
        assertTrue(technical.automatic());
        assertEquals(List.of(a.getId(), b.getId()),
                technical.candidates().stream().map(TrainingDelegationService.CoachOption::staffId).toList());
        assertEquals(TrainingType.values().length, byType.size());
    }
}
