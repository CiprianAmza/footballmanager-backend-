package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.CompetitionHistory;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamPlayerHistoricalRelation;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.CompetitionHistoryRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.repository.TeamPlayerHistoricalRelationRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatsServiceCareerEntriesTest {
    @Mock private HumanRepository humans;
    @Mock private ScorerRepository scorers;
    @Mock private TeamPlayerHistoricalRelationRepository career;
    @Mock private TeamRepository teams;
    @Mock private CompetitionHistoryRepository competitionHistory;
    @InjectMocks private StatsService service;

    @Test
    void keepsEveryRosterSeasonWithoutInventingMatchAppearances() {
        Human player = new Human();
        player.setId(2450);
        player.setName("Aelaelle");
        when(humans.findById(2450L)).thenReturn(Optional.of(player));
        when(scorers.findAllByPlayerId(2450)).thenReturn(List.of(match(2450, 1, 79), match(2450, 13, 70)));
        when(career.findByPlayerId(2450)).thenReturn(List.of(
                roster(1, 2450, 1, 79), roster(2, 2450, 2, 54),
                roster(3, 2450, 3, 20), roster(4, 2450, 3, 54), roster(5, 2450, 13, 70)));
        when(teams.findAllById(Set.of(54L))).thenReturn(List.of(team(54, "Tutucea Team")));
        when(competitionHistory.findAllByTeamIdIn(Set.of(54L))).thenReturn(List.of(
                competition(2, 54, 8, 1, "FootieCup League"),
                competition(2, 54, 9, 2, "FootieCup Cup"),
                competition(3, 54, 8, 1, "FootieCup League"),
                competition(3, 54, 9, 2, "FootieCup Cup")));

        var rows = service.getPlayerCareerEntries(2450);

        assertEquals(Set.of(1, 2, 3, 13), rows.keySet());
        assertEquals(1, rows.get(1).getTotalGames());
        assertEquals(1, rows.get(13).getTotalGames());
        assertEquals("Tutucea Team", rows.get(2).getTeamName());
        assertEquals("Tutucea Team", rows.get(3).getTeamName());
        assertEquals("FootieCup League", rows.get(2).getSeasonLeagueName());
        assertEquals("FootieCup Cup", rows.get(3).getSeasonCupName());
        assertEquals(0, rows.get(2).getTotalGames());
        assertEquals(0, rows.get(3).getTotalGoals());
        assertTrue(rows.get(2).getCompetitionEntries().isEmpty());
    }

    private static Scorer match(long playerId, int season, long teamId) {
        Scorer scorer = new Scorer();
        scorer.setPlayerId(playerId);
        scorer.setSeasonNumber(season);
        scorer.setTeamId(teamId);
        scorer.setTeamName("Match club");
        scorer.setCompetitionId(14);
        scorer.setCompetitionName("Literature League");
        scorer.setCompetitionTypeId(1);
        scorer.setTeamScore(1);
        scorer.setOpponentTeamId(5);
        return scorer;
    }

    private static TeamPlayerHistoricalRelation roster(long id, long playerId, long season, long teamId) {
        TeamPlayerHistoricalRelation relation = new TeamPlayerHistoricalRelation();
        relation.setId(id);
        relation.setPlayerId(playerId);
        relation.setSeasonNumber(season);
        relation.setTeamId(teamId);
        return relation;
    }

    private static Team team(long id, String name) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        return team;
    }

    private static CompetitionHistory competition(long season, long teamId, long competitionId,
                                                  long typeId, String name) {
        CompetitionHistory history = new CompetitionHistory();
        history.setSeasonNumber(season);
        history.setTeamId(teamId);
        history.setCompetitionId(competitionId);
        history.setCompetitionTypeId(typeId);
        history.setCompetitionName(name);
        return history;
    }
}
