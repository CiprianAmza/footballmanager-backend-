package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompetitionExpectationServiceTest {
    @Test
    void predictionIsFrozenBeforeMatchesAndNeverRecomputedFromChangedSquads() {
        CompetitionSeasonExpectationRepository saved = mock(CompetitionSeasonExpectationRepository.class);
        CompetitionRepository competitions = mock(CompetitionRepository.class);
        CompetitionTeamInfoRepository entries = mock(CompetitionTeamInfoRepository.class);
        CompetitionTeamInfoMatchRepository fixtures = mock(CompetitionTeamInfoMatchRepository.class);
        TeamCompetitionDetailRepository standings = mock(TeamCompetitionDetailRepository.class);
        CompetitionTeamInfoDetailRepository results = mock(CompetitionTeamInfoDetailRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        HumanRepository people = mock(HumanRepository.class);
        CompetitionExpectationService service = new CompetitionExpectationService(
                saved, competitions, entries, fixtures, standings, results, teams, people);

        Competition league = new Competition(); league.setId(7); league.setTypeId(Competition.LEAGUE);
        Team a = new Team(); a.setId(1); a.setReputation(100);
        Team b = new Team(); b.setId(2); b.setReputation(80);
        CompetitionTeamInfo ea = new CompetitionTeamInfo(); ea.setCompetitionId(7); ea.setTeamId(1);
        CompetitionTeamInfo eb = new CompetitionTeamInfo(); eb.setCompetitionId(7); eb.setTeamId(2);
        Human pa = new Human(); pa.setTeamId(1L); pa.setRating(220);
        Human pb = new Human(); pb.setTeamId(2L); pb.setRating(180);
        AtomicReference<List<CompetitionSeasonExpectation>> persisted = new AtomicReference<>(List.of());
        when(saved.findAllBySeasonNumber(4)).thenAnswer(call -> persisted.get());
        when(saved.saveAll(any())).thenAnswer(call -> {
            List<CompetitionSeasonExpectation> rows = new ArrayList<>();
            ((Iterable<CompetitionSeasonExpectation>) call.getArgument(0)).forEach(rows::add);
            persisted.set(rows);
            return rows;
        });
        when(competitions.findAll()).thenReturn(List.of(league));
        when(entries.findAllBySeasonNumber(4)).thenReturn(List.of(ea, eb));
        when(standings.findAll()).thenReturn(List.of());
        when(results.findAllBySeasonNumber(4)).thenReturn(List.of());
        when(teams.findAllById(anySet())).thenReturn(List.of(a, b));
        when(people.findAllByTeamIdInAndTypeId(anySet(), anyLong())).thenReturn(List.of(pa, pb));

        service.captureMissingBeforeMatches(4);
        assertEquals(2, persisted.get().size());
        assertEquals(1, persisted.get().stream().filter(row -> row.getTeamId() == 1)
                .findFirst().orElseThrow().getPredictedPosition());
        assertNotNull(persisted.get().get(0).getCapturedAt());

        pa.setRating(10); pb.setRating(250);
        service.captureMissingBeforeMatches(4);
        verify(saved, times(1)).saveAll(any());
        assertEquals(1, persisted.get().stream().filter(row -> row.getTeamId() == 1)
                .findFirst().orElseThrow().getPredictedPosition());
    }
}
