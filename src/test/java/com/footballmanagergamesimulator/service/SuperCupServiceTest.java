package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuperCupServiceTest {

    @Test
    void domesticDoubleWinnerFacesLeagueRunnerUp() {
        CompetitionRepository competitions = mock(CompetitionRepository.class);
        CompetitionHistoryRepository histories = mock(CompetitionHistoryRepository.class);
        CompetitionTeamInfoDetailRepository results = mock(CompetitionTeamInfoDetailRepository.class);
        CompetitionTeamInfoRepository entries = mock(CompetitionTeamInfoRepository.class);
        CompetitionTeamInfoMatchRepository fixtures = mock(CompetitionTeamInfoMatchRepository.class);
        SuperCupService service = new SuperCupService(competitions, histories, results, entries, fixtures);

        Competition league = competition(1, 1, 7);
        Competition cup = competition(2, 2, 7);
        Competition superCup = competition(20, 6, 7);
        when(competitions.findAll()).thenReturn(List.of(league, cup, superCup));
        when(histories.findByCompetitionId(1)).thenReturn(List.of(
                history(10, 1, 3), history(11, 2, 3)));
        CompetitionTeamInfoDetail cupFinal = new CompetitionTeamInfoDetail();
        cupFinal.setRoundId(4);
        cupFinal.setWinnerTeamId(10L);
        cupFinal.setDecidedBy("NORMAL");
        when(results.findAllByCompetitionIdAndSeasonNumber(2, 3)).thenReturn(List.of(cupFinal));
        when(fixtures.findAllByCompetitionIdAndRoundAndSeasonNumber(20, 1, "4")).thenReturn(List.of());

        service.prepareSeason(4);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CompetitionTeamInfo>> entryCaptor = ArgumentCaptor.forClass(List.class);
        verify(entries).saveAll(entryCaptor.capture());
        assertEquals(List.of(10L, 11L), entryCaptor.getValue().stream()
                .map(CompetitionTeamInfo::getTeamId).toList());
        ArgumentCaptor<CompetitionTeamInfoMatch> fixtureCaptor = ArgumentCaptor.forClass(CompetitionTeamInfoMatch.class);
        verify(fixtures).save(fixtureCaptor.capture());
        assertEquals(10, fixtureCaptor.getValue().getTeam1Id());
        assertEquals(11, fixtureCaptor.getValue().getTeam2Id());
    }

    private Competition competition(long id, long type, long nation) {
        Competition c = new Competition();
        c.setId(id); c.setTypeId(type); c.setNationId(nation); c.setName("Test");
        return c;
    }

    private CompetitionHistory history(long teamId, long position, long season) {
        CompetitionHistory h = new CompetitionHistory();
        h.setTeamId(teamId); h.setLastPosition(position); h.setSeasonNumber(season);
        return h;
    }
}
