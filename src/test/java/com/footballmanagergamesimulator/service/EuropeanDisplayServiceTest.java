package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EuropeanDisplayServiceTest {

    @Test
    void historicalGroupTableExcludesQualifiersAndKnockoutMatches() {
        CompetitionRepository competitions = mock(CompetitionRepository.class);
        CompetitionTeamInfoRepository entries = mock(CompetitionTeamInfoRepository.class);
        CompetitionTeamInfoDetailRepository results = mock(CompetitionTeamInfoDetailRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        EuropeanDisplayService service = new EuropeanDisplayService();
        ReflectionTestUtils.setField(service, "competitionRepository", competitions);
        ReflectionTestUtils.setField(service, "competitionTeamInfoRepository", entries);
        ReflectionTestUtils.setField(service, "competitionTeamInfoDetailRepository", results);
        ReflectionTestUtils.setField(service, "teamRepository", teams);
        ReflectionTestUtils.setField(service, "competitionFormatConfig", new CompetitionFormatConfig());

        Competition loc = new Competition();
        loc.setId(10); loc.setTypeId(4); loc.setName("League of Champions");
        when(competitions.findById(10L)).thenReturn(Optional.of(loc));

        List<CompetitionTeamInfo> group = List.of(
                entry(1, 1), entry(2, 1), entry(3, 1), entry(4, 1));
        when(entries.findAllBySeasonNumber(2)).thenReturn(group);
        for (long teamId = 1; teamId <= 4; teamId++) {
            Team team = new Team(); team.setId(teamId); team.setName("Team " + teamId);
            when(teams.findById(teamId)).thenReturn(Optional.of(team));
        }

        List<CompetitionTeamInfoDetail> played = new ArrayList<>();
        // These two matches belong to other stages and must not enter P/W/D/L/GF/GA.
        played.add(match(1, 99, 1, "0 - 1"));
        played.add(match(8, 1, 88, "2 - 0"));
        // Six group matchdays: team 1 plays exactly six games.
        played.add(match(2, 1, 2, "1 - 0"));
        played.add(match(3, 3, 1, "1 - 1"));
        played.add(match(4, 1, 4, "0 - 2"));
        played.add(match(5, 2, 1, "0 - 3"));
        played.add(match(6, 1, 3, "2 - 2"));
        played.add(match(7, 4, 1, "1 - 0"));
        when(results.findAllByCompetitionIdAndSeasonNumber(10, 2)).thenReturn(played);

        Map<String, Object> teamOne = service.getEuropeanGroups(10, 2).stream()
                .filter(row -> ((Number) row.get("teamId")).longValue() == 1)
                .findFirst().orElseThrow();

        assertEquals(6, teamOne.get("games"));
        assertEquals(2, teamOne.get("wins"));
        assertEquals(2, teamOne.get("draws"));
        assertEquals(2, teamOne.get("loses"));
        assertEquals(7, teamOne.get("goalsFor"));
        assertEquals(6, teamOne.get("goalsAgainst"));
        assertEquals(8, teamOne.get("points"));
    }

    private CompetitionTeamInfo entry(long teamId, int group) {
        CompetitionTeamInfo entry = new CompetitionTeamInfo();
        entry.setTeamId(teamId); entry.setCompetitionId(10); entry.setSeasonNumber(2);
        entry.setRound(2); entry.setGroupNumber(group); entry.setPotNumber((int) teamId);
        return entry;
    }

    private CompetitionTeamInfoDetail match(long round, long home, long away, String score) {
        CompetitionTeamInfoDetail match = new CompetitionTeamInfoDetail();
        match.setCompetitionId(10); match.setSeasonNumber(2); match.setRoundId(round);
        match.setTeam1Id(home); match.setTeam2Id(away); match.setScore(score);
        return match;
    }
}
