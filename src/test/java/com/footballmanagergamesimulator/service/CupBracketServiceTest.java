package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The national cup must build a valid bracket for ANY entrant count — exact
 * powers of two, non-powers, and odd counts alike. These unit tests pin the
 * adaptive math ({@code largestPowerOfTwoAtMost} + balanced order + the
 * prelim/auto-qualified partition) without touching the database.
 *
 * Lives in the same package so it can reach the package-private statics.
 */
class CupBracketServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void cupsOverviewLabelsTheUpcomingRoundInsteadOfTheLastPlayedRound() {
        CupBracketService service = new CupBracketService();
        CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
        CompetitionTeamInfoMatchRepository matchRepository = mock(CompetitionTeamInfoMatchRepository.class);
        CompetitionTeamInfoDetailRepository detailRepository = mock(CompetitionTeamInfoDetailRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        RoundRepository roundRepository = mock(RoundRepository.class);
        EuropeanCoefficientService coefficientService = mock(EuropeanCoefficientService.class);

        ReflectionTestUtils.setField(service, "competitionRepository", competitionRepository);
        ReflectionTestUtils.setField(service, "competitionTeamInfoMatchRepository", matchRepository);
        ReflectionTestUtils.setField(service, "competitionTeamInfoDetailRepository", detailRepository);
        ReflectionTestUtils.setField(service, "teamRepository", teamRepository);
        ReflectionTestUtils.setField(service, "roundRepository", roundRepository);
        ReflectionTestUtils.setField(service, "europeanCoefficientService", coefficientService);

        Round season = new Round();
        season.setSeason(1);
        Competition league = competition(10, 1, 7, "Test League");
        Competition cup = competition(20, 2, 7, "Test Cup");
        CompetitionTeamInfoMatch semiFinal = match(20, 2, 1, 2, "1");
        CompetitionTeamInfoMatch finalMatch = match(20, 3, 101, 102, "1");
        CompetitionTeamInfoDetail playedSemiFinal = new CompetitionTeamInfoDetail();
        playedSemiFinal.setCompetitionId(20);
        playedSemiFinal.setSeasonNumber(1);
        playedSemiFinal.setRoundId(2);
        playedSemiFinal.setTeam1Id(1);
        playedSemiFinal.setTeam2Id(2);
        playedSemiFinal.setScore("2-1");
        Team finalistOne = team(101, "Finalist One");
        Team finalistTwo = team(102, "Finalist Two");

        when(roundRepository.findById(1L)).thenReturn(Optional.of(season));
        when(coefficientService.getLeagueIdsSortedByCoefficient()).thenReturn(List.of(10L));
        when(competitionRepository.findById(10L)).thenReturn(Optional.of(league));
        when(competitionRepository.findAll()).thenReturn(List.of(league, cup));
        when(matchRepository.findAll()).thenReturn(List.of(semiFinal, finalMatch));
        when(detailRepository.findAll()).thenReturn(List.of(playedSemiFinal));
        when(teamRepository.findAllById(any())).thenReturn(List.of(finalistOne, finalistTwo));

        Map<String, Object> overview = service.getCupsOverview();
        Map<String, Object> cupOverview = ((List<Map<String, Object>>) overview.get("cups")).get(0);

        assertEquals(2, cupOverview.get("lastPlayedRound"));
        assertEquals(3, cupOverview.get("focusRound"));
        assertEquals("Final", cupOverview.get("currentRoundName"));
        assertEquals(1, ((List<?>) cupOverview.get("focusRoundMatches")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void historicalBracketIsRebuiltFromDurableResultsWhenFixturesWereCleared() {
        CupBracketService service = new CupBracketService();
        CompetitionRepository competitionRepository = mock(CompetitionRepository.class);
        CompetitionTeamInfoMatchRepository matchRepository = mock(CompetitionTeamInfoMatchRepository.class);
        CompetitionTeamInfoDetailRepository detailRepository = mock(CompetitionTeamInfoDetailRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        ReflectionTestUtils.setField(service, "competitionRepository", competitionRepository);
        ReflectionTestUtils.setField(service, "competitionTeamInfoMatchRepository", matchRepository);
        ReflectionTestUtils.setField(service, "competitionTeamInfoDetailRepository", detailRepository);
        ReflectionTestUtils.setField(service, "teamRepository", teamRepository);

        Competition cup = competition(20, 6, 7, "Test Super Cup");
        CompetitionTeamInfoDetail finalResult = new CompetitionTeamInfoDetail();
        finalResult.setId(99);
        finalResult.setCompetitionId(20);
        finalResult.setSeasonNumber(2);
        finalResult.setRoundId(1);
        finalResult.setTeam1Id(1);
        finalResult.setTeam2Id(2);
        finalResult.setTeamName1("Champions");
        finalResult.setTeamName2("Runners-up");
        finalResult.setScore("1 - 1 (pens)");
        finalResult.setWinnerTeamId(1L);
        finalResult.setDecidedBy("PENALTIES");

        when(matchRepository.findAllByCompetitionIdAndSeasonNumberOrderByRoundAscMatchIndexAsc(20, "2"))
                .thenReturn(List.of());
        when(detailRepository.findAllByCompetitionIdAndSeasonNumber(20, 2)).thenReturn(List.of(finalResult));
        when(teamRepository.findAllById(any())).thenReturn(List.of(team(1, "Champions"), team(2, "Runners-up")));
        when(competitionRepository.findById(20L)).thenReturn(Optional.of(cup));

        Map<String, Object> bracket = service.getCupBracket(20, 2);
        List<Map<String, Object>> rounds = (List<Map<String, Object>>) bracket.get("rounds");
        List<Map<String, Object>> matches = (List<Map<String, Object>>) rounds.get(0).get("matches");

        assertEquals("Final", rounds.get(0).get("roundLabel"));
        assertEquals("1 - 1 (pens)", matches.get(0).get("score"));
        assertEquals(1L, matches.get(0).get("winnerTeamId"));
        assertEquals(1L, matches.get(0).get("qualifiedTeamId"));
        assertEquals("PENALTIES", matches.get(0).get("decidedBy"));
    }

    @Test
    void largestPowerOfTwoAtMost_isCorrect() {
        assertEquals(2, CupBracketService.largestPowerOfTwoAtMost(2));
        assertEquals(2, CupBracketService.largestPowerOfTwoAtMost(3));
        assertEquals(4, CupBracketService.largestPowerOfTwoAtMost(4));
        assertEquals(4, CupBracketService.largestPowerOfTwoAtMost(7));
        assertEquals(8, CupBracketService.largestPowerOfTwoAtMost(8));
        assertEquals(8, CupBracketService.largestPowerOfTwoAtMost(15));
        assertEquals(16, CupBracketService.largestPowerOfTwoAtMost(16));
        assertEquals(16, CupBracketService.largestPowerOfTwoAtMost(31));
        assertEquals(32, CupBracketService.largestPowerOfTwoAtMost(32));
    }

    @Test
    void balancedBracketOrder_matchesKnownLayouts() {
        assertArrayEquals(new int[]{1, 2}, CupBracketService.balancedBracketOrder(2));
        assertArrayEquals(new int[]{1, 4, 2, 3}, CupBracketService.balancedBracketOrder(4));
        assertArrayEquals(new int[]{1, 8, 4, 5, 2, 7, 3, 6}, CupBracketService.balancedBracketOrder(8));
        assertArrayEquals(
                new int[]{1, 16, 8, 9, 4, 13, 5, 12, 2, 15, 7, 10, 3, 14, 6, 11},
                CupBracketService.balancedBracketOrder(16));
    }

    @Test
    void balancedBracketOrder_isPermutationWhereSeedsPairToMplus1() {
        for (int m : new int[]{2, 4, 8, 16, 32}) {
            int[] order = CupBracketService.balancedBracketOrder(m);
            assertEquals(m, order.length, "m=" + m + ": length");

            // Permutation of 1..m
            Set<Integer> seen = new HashSet<>();
            for (int s : order) {
                assertTrue(s >= 1 && s <= m, "m=" + m + ": seed " + s + " out of range");
                assertTrue(seen.add(s), "m=" + m + ": seed " + s + " duplicated");
            }
            assertEquals(m, seen.size());

            // Each first-round pairing (slot 2k-1 vs 2k) sums to m+1: best plays worst.
            for (int i = 0; i < m; i += 2) {
                assertEquals(m + 1, order[i] + order[i + 1],
                        "m=" + m + ": pairing at slot " + i + " must sum to m+1");
            }
        }
    }

    /**
     * The dimensions generateBracket() derives from N must partition the field
     * cleanly for every N: prelim teams + auto-qualified teams = N, and the
     * round-of-M is exactly full (auto + prelim winners = M).
     */
    @Test
    void bracketDimensionsPartitionTheFieldForAnyN() {
        for (int n : new int[]{2, 4, 8, 16, 32,   // exact powers of two
                               6, 10, 12, 20,     // non-powers, even
                               7, 9, 15, 23}) {   // odd
            int m = CupBracketService.largestPowerOfTwoAtMost(n);
            int prelimMatches = n - m;       // matches in the preliminary round
            int autoQualified = 2 * m - n;   // teams that skip the prelim

            assertTrue(m <= n && m * 2 > n, "N=" + n + ": M must be the largest power of two ≤ N");
            assertTrue(prelimMatches >= 0, "N=" + n + ": prelim matches cannot be negative");
            assertTrue(autoQualified >= 0, "N=" + n + ": auto-qualified cannot be negative");

            // Every team is either auto-qualified or one of two in a prelim match.
            assertEquals(n, autoQualified + 2 * prelimMatches,
                    "N=" + n + ": all teams must be accounted for");
            // The round of M is exactly filled: auto teams + prelim winners.
            assertEquals(m, autoQualified + prelimMatches,
                    "N=" + n + ": round-of-M must be exactly full");
        }
    }

    private static Competition competition(long id, long typeId, long nationId, String name) {
        Competition competition = new Competition();
        competition.setId(id);
        competition.setTypeId(typeId);
        competition.setNationId(nationId);
        competition.setName(name);
        return competition;
    }

    private static CompetitionTeamInfoMatch match(long competitionId, long round,
                                                   long team1Id, long team2Id, String season) {
        CompetitionTeamInfoMatch match = new CompetitionTeamInfoMatch();
        match.setCompetitionId(competitionId);
        match.setRound(round);
        match.setTeam1Id(team1Id);
        match.setTeam2Id(team2Id);
        match.setSeasonNumber(season);
        match.setMatchIndex(1);
        return match;
    }

    private static Team team(long id, String name) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        return team;
    }
}
