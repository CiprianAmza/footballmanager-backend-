package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class CompetitionReputationServiceTest {
    @Test
    void assignsReputationToEveryCompetitionAndKeepsChampionsHighest() {
        CompetitionRepository competitions = mock(CompetitionRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        CompetitionReputationService service = new CompetitionReputationService(competitions, teams);

        Competition league = competition(1, 1, Competition.LEAGUE);
        Competition cup = competition(2, 1, Competition.CUP);
        Competition superCup = competition(3, 1, Competition.SUPER_CUP);
        Competition champions = competition(4, 0, Competition.LEAGUE_OF_CHAMPIONS);
        Competition stars = competition(5, 0, Competition.STARS_CUP);
        Team strongest = new Team(); strongest.setCompetitionId(1); strongest.setReputation(10_200);
        when(competitions.findAll()).thenReturn(List.of(league, cup, superCup, champions, stars));
        when(teams.findAll()).thenReturn(List.of(strongest));

        service.ensureReputations();

        assertEquals(10_000, league.getReputation());
        assertEquals(6_000, cup.getReputation());
        assertEquals(2_500, superCup.getReputation());
        assertEquals(13_000, champions.getReputation());
        assertEquals(9_000, stars.getReputation());
        verify(competitions).saveAll(anyList());
    }

    @Test
    void updatesPreviouslySavedDomesticCupReputation() {
        CompetitionRepository competitions = mock(CompetitionRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        CompetitionReputationService service = new CompetitionReputationService(competitions, teams);
        Competition league = competition(1, 1, Competition.LEAGUE);
        league.setReputation(10_000);
        Competition cup = competition(2, 1, Competition.CUP);
        cup.setReputation(7_500);
        Team strongest = new Team(); strongest.setCompetitionId(1); strongest.setReputation(10_000);
        when(competitions.findAll()).thenReturn(List.of(league, cup));
        when(teams.findAll()).thenReturn(List.of(strongest));

        service.ensureReputations();

        assertEquals(6_000, cup.getReputation());
        assertEquals(6_000, CompetitionReputationService.reputationOf(cup));
    }

    private static Competition competition(long id, long nationId, long typeId) {
        Competition value = new Competition();
        value.setId(id); value.setNationId(nationId); value.setTypeId(typeId);
        return value;
    }
}
