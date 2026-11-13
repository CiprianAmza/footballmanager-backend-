package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransferPricingRelegationTest {

    private static final long TOP = 10;
    private static final long SECOND = 20;
    private static final long CUP = 30;

    private final TransferPricingService pricing = new TransferPricingService();
    private final Team club = new Team();

    @BeforeEach
    void leagues() {
        pricing.competitionHistoryRepository = mock(CompetitionHistoryRepository.class);
        pricing.competitionTeamInfoRepository = mock(CompetitionTeamInfoRepository.class);
        pricing.competitionRepository = mock(CompetitionRepository.class);
        when(pricing.competitionRepository.findById(TOP)).thenReturn(Optional.of(competition(TOP, Competition.LEAGUE, 1)));
        when(pricing.competitionRepository.findById(SECOND)).thenReturn(Optional.of(competition(SECOND, Competition.LEAGUE, 2)));
        when(pricing.competitionRepository.findById(CUP)).thenReturn(Optional.of(competition(CUP, Competition.CUP, 1)));
        club.setId(7);
        // Set when the world was created and never updated since: it must not matter.
        club.setCompetitionId(TOP);
    }

    @Test
    @DisplayName("""
            GIVEN a club that played the top league (and the cup) last season and the second league now
            WHEN the market asks whether it was relegated
            THEN it was, although its stale Team.competitionId still says top league
            """)
    void aClubNowInALowerLeagueWasRelegated() {
        seasons(List.of(TOP, CUP), List.of(SECOND, CUP));
        assertTrue(pricing.relegated(club, 5));
    }

    @Test
    @DisplayName("""
            GIVEN a club promoted from the second league to the top league, and one that stayed up
            WHEN the market asks whether they were relegated
            THEN neither was: a promotion is not a relegation
            """)
    void promotionAndStayingUpAreNotRelegation() {
        seasons(List.of(SECOND), List.of(TOP));
        assertFalse(pricing.relegated(club, 5));
        seasons(List.of(TOP), List.of(TOP));
        assertFalse(pricing.relegated(club, 5));
    }

    @Test
    @DisplayName("""
            GIVEN a club with no league last season (first season of the world)
            WHEN the market asks whether it was relegated
            THEN it was not
            """)
    void noHistoryIsNoRelegation() {
        seasons(List.of(), List.of(SECOND));
        assertFalse(pricing.relegated(club, 1));
    }

    private void seasons(List<Long> lastSeason, List<Long> thisSeason) {
        when(pricing.competitionHistoryRepository.findByTeamIdAndSeasonNumber(club.getId(), 4))
                .thenReturn(lastSeason.stream().map(id -> {
                    CompetitionHistory h = new CompetitionHistory();
                    h.setTeamId(club.getId());
                    h.setCompetitionId(id);
                    return h;
                }).toList());
        when(pricing.competitionHistoryRepository.findByTeamIdAndSeasonNumber(club.getId(), 0)).thenReturn(List.of());
        when(pricing.competitionTeamInfoRepository.findAllByTeamIdAndSeasonNumber(club.getId(), 5))
                .thenReturn(thisSeason.stream().map(id -> {
                    CompetitionTeamInfo info = new CompetitionTeamInfo();
                    info.setTeamId(club.getId());
                    info.setCompetitionId(id);
                    return info;
                }).toList());
        when(pricing.competitionTeamInfoRepository.findAllByTeamIdAndSeasonNumber(club.getId(), 1))
                .thenReturn(thisSeason.stream().map(id -> {
                    CompetitionTeamInfo info = new CompetitionTeamInfo();
                    info.setCompetitionId(id);
                    return info;
                }).toList());
    }

    private static Competition competition(long id, long type, int tier) {
        Competition c = new Competition();
        c.setId(id);
        c.setTypeId(type);
        c.setTier(tier);
        return c;
    }
}
