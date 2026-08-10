package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.ScoutRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaffIntelligenceServiceTest {

    private HumanRepository humans;
    private ScoutRepository scouts;
    private TeamRepository teams;
    private RoundRepository rounds;
    private FinanceService finances;
    private StaffIntelligenceService service;

    @BeforeEach
    void setUp() {
        humans = mock(HumanRepository.class);
        scouts = mock(ScoutRepository.class);
        teams = mock(TeamRepository.class);
        rounds = mock(RoundRepository.class);
        finances = mock(FinanceService.class);
        service = new StaffIntelligenceService(humans, scouts, teams, rounds, finances);

        Round round = new Round();
        round.setSeason(3);
        round.setRound(18);
        when(rounds.findById(1L)).thenReturn(Optional.of(round));
        when(scouts.findAll()).thenReturn(List.of());
        for (long type = 5; type <= 10; type++) {
            when(humans.findAllByTypeId(type)).thenReturn(List.of());
        }
    }

    @Test
    void buildsRealLeagueRankingAndExposesEmployedStaffInMarket() {
        Team elite = team(1, "Elite FC", 8, 5_000_000, 20_000);
        Team rival = team(2, "Rival FC", 8, 5_000_000, 20_000);
        Human eliteCoach = coach(10, 1, "Elite Coach", 5, 18, 1_800, 6);
        Human rivalCoach = coach(11, 2, "Rival Coach", 5, 8, 800, 5);

        when(teams.findAll()).thenReturn(List.of(elite, rival));
        when(humans.findAllByTeamIdInAndTypeIdIn(any(Set.class), any(List.class)))
                .thenReturn(List.of(eliteCoach, rivalCoach));
        when(humans.findAllByTypeId(5L)).thenReturn(List.of(eliteCoach, rivalCoach));

        StaffIntelligenceService.StaffIntelligence result = service.intelligence(1);

        assertEquals(1, result.ranking().stream()
                .filter(row -> row.club().teamId() == 1).findFirst().orElseThrow().leagueRank());
        assertTrue(result.benchmarks().leagueAverage() > 0);
        assertEquals(1, result.market().size());
        assertEquals("Rival Coach", result.market().get(0).name());
        assertFalse(result.market().get(0).freeAgent());
        assertTrue(result.market().get(0).requiredCompensation() > 0);
    }

    @Test
    void transfersAnEmployedCoachOnlyAfterWageAndCompensationAreMet() {
        Team buyer = team(1, "Buyer", 8, 1_000_000, 10_000);
        Team seller = team(2, "Seller", 8, 1_000_000, 8_000);
        Human coach = coach(20, 2, "Target Coach", 6, 15, 1_000, 5);
        when(humans.findByIdForUpdate(20L)).thenReturn(Optional.of(coach));
        when(teams.findById(1L)).thenReturn(Optional.of(buyer));
        when(teams.findById(2L)).thenReturn(Optional.of(seller));

        StaffIntelligenceService.OfferResult rejected = service.makeOffer(1,
                new StaffIntelligenceService.StaffOffer("COACH", 20, 1_100, 3, 100_000));
        assertFalse(rejected.success());
        assertEquals(2L, coach.getTeamId());

        StaffIntelligenceService.OfferResult accepted = service.makeOffer(1,
                new StaffIntelligenceService.StaffOffer("COACH", 20, 1_200, 3, 250_000));

        assertTrue(accepted.success());
        assertEquals(1L, coach.getTeamId());
        assertEquals(1_200, coach.getWage());
        assertEquals(6, coach.getContractEndSeason());
        assertEquals(11_200, buyer.getSalaryBudget());
        assertEquals(7_000, seller.getSalaryBudget());
        verify(finances).recordExpense(1, 3, 18, "STAFF_COMPENSATION", "Compensation for Target Coach", 250_000);
        verify(finances).recordTransaction(2, 3, 18, "STAFF_COMPENSATION", "Compensation received for Target Coach", 250_000);
        verify(humans).save(coach);
    }

    private Team team(long id, String name, long competitionId, long finances, long salaryBudget) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        team.setCompetitionId(competitionId);
        team.setTotalFinances(finances);
        team.setSalaryBudget(salaryBudget);
        return team;
    }

    private Human coach(long id, long teamId, String name, long type, int ability, long wage, int endSeason) {
        Human coach = new Human();
        coach.setId(id);
        coach.setTeamId(teamId);
        coach.setName(name);
        coach.setTypeId(type);
        coach.setAge(42);
        coach.setWage(wage);
        coach.setContractEndSeason(endSeason);
        coach.setCoachingAttacking(ability);
        coach.setCoachingDefending(ability);
        coach.setCoachingTactical(ability);
        coach.setCoachingTechnical(ability);
        coach.setCoachingMental(ability);
        coach.setCoachingFitness(ability);
        coach.setCoachingGK(ability);
        coach.setWorkingWithYoungsters(ability);
        coach.setMotivating(ability);
        return coach;
    }
}
