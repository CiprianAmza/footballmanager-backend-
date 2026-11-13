package com.footballmanagergamesimulator.service.board;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.user.CoachingLicence;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import(LicenceService.class)
class LicenceServiceTest {

    @Autowired private LicenceService licences;
    @Autowired private CompetitionRepository competitions;
    @Autowired private TeamRepository teams;
    @Autowired private HumanRepository humans;
    @Autowired private ManagerBackgroundRepository backgrounds;

    @Test
    @DisplayName("""
            GIVEN the ladder C < B < A < Pro, plus no licence and a goalkeeping licence
            WHEN leagues of tier 1, 2 and 3 ask for theirs
            THEN they need Pro, A and B; an A-licence holder qualifies below the top flight only; without the licence
             he is registered as assistant (goalkeeping coach with a goalkeeping licence); a club above 7,000 refuses him
            """)
    void theRule() {
        assertEquals(CoachingLicence.PRO, LicenceRule.required(1));
        assertEquals(CoachingLicence.A, LicenceRule.required(2));
        assertEquals(CoachingLicence.B, LicenceRule.required(3));
        assertFalse(LicenceRule.qualifies(CoachingLicence.A, CoachingLicence.PRO));
        assertTrue(LicenceRule.qualifies(CoachingLicence.A, CoachingLicence.B));
        assertEquals(LicenceRule.Role.ASSISTANT, LicenceRule.role(CoachingLicence.C, CoachingLicence.B));
        assertEquals(LicenceRule.Role.GOALKEEPING_COACH, LicenceRule.role(CoachingLicence.GOALKEEPING, CoachingLicence.B));
        assertTrue(LicenceRule.refuses(7_500, CoachingLicence.A, CoachingLicence.PRO));
        assertFalse(LicenceRule.refuses(6_500, CoachingLicence.A, CoachingLicence.PRO));
        assertFalse(LicenceRule.refuses(9_000, CoachingLicence.PRO, CoachingLicence.PRO));
    }

    @Test
    @DisplayName("""
            GIVEN a top-flight club with an assistant manager, a human manager holding a B licence, and an AI manager
            WHEN their registration is checked
            THEN the human manager is registered as assistant and the assistant manager is head coach on paper;
             the AI manager (no licence on record) always qualifies
            """)
    void frontCoachOnPaper() {
        Competition league = new Competition();
        league.setName("First League");
        league.setTypeId(Competition.LEAGUE);
        league.setTier(1);
        long leagueId = competitions.save(league).getId();
        Team t = new Team();
        t.setName("Tik Tok");
        t.setCompetitionId(leagueId);
        t.setReputation(5_000);
        long club = teams.save(t).getId();
        Human assistant = new Human();
        assistant.setName("Front Man");
        assistant.setTypeId(TypeNames.ASSISTANT_MANAGER_TYPE);
        assistant.setTeamId(club);
        humans.save(assistant);
        Human me = humans.save(manager(club));
        ManagerBackground background = new ManagerBackground();
        background.setManagerId(me.getId());
        background.setCoachingLicence("B");
        backgrounds.save(background);
        Human ai = humans.save(manager(club));

        LicenceService.Registration registration = licences.registration(me.getId(), club);

        assertEquals("Pro licence", registration.requiredLicence());
        assertEquals(LicenceRule.Role.ASSISTANT, registration.role());
        assertEquals("Front Man", registration.frontCoachName());
        assertFalse(licences.qualifies(me.getId(), club));
        assertTrue(licences.qualifies(ai.getId(), club));
        assertFalse(licences.refuses(me.getId(), club), "a 5,000 club still takes him");
    }

    private static Human manager(long club) {
        Human h = new Human();
        h.setName("M");
        h.setTypeId(TypeNames.MANAGER_TYPE);
        h.setTeamId(club);
        return h;
    }
}
