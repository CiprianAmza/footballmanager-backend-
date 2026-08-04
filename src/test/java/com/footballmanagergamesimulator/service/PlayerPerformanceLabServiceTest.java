package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.PlayerSeasonStat;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.PlayerSeasonStatRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerPerformanceLabServiceTest {
    @Test
    void exposesGoalkeeperShotStoppingDistributionAndLineMetrics() {
        HumanRepository humans = mock(HumanRepository.class);
        PlayerSkillsRepository skillsRepository = mock(PlayerSkillsRepository.class);
        PlayerSeasonStatRepository seasonStats = mock(PlayerSeasonStatRepository.class);
        ScorerRepository scorers = mock(ScorerRepository.class);
        MatchStatsRepository matches = mock(MatchStatsRepository.class);
        Human player = new Human(); player.setId(1); player.setName("Keeper"); player.setTeamId(10L); player.setPosition("GK");
        PlayerSkills skills = new PlayerSkills(); skills.setPlayerId(1); skills.setPosition("GK"); skills.setHandling(15); skills.setReflexes(16); skills.setCommandOfArea(14);
        when(humans.findById(1L)).thenReturn(Optional.of(player));
        when(skillsRepository.findPlayerSkillsByPlayerId(1)).thenReturn(Optional.of(skills));
        when(scorers.findByPlayerIdAndSeasonNumber(1, 3)).thenReturn(List.of());
        when(matches.findAllBySeasonNumber(3)).thenReturn(List.of());
        when(seasonStats.findAllBySeasonNumber(3)).thenReturn(List.of());
        PlayerPerformanceLabService service = new PlayerPerformanceLabService(humans, skillsRepository, seasonStats,
                scorers, matches, mock(ShotEventService.class), mock(DefensivePressureLedgerService.class));

        PlayerPerformanceLabService.GoalkeeperHub result = service.goalkeeper(1, 3);

        assertThat(result.eligible()).isTrue();
        assertThat(result.metrics()).extracting(PlayerPerformanceLabService.GkMetric::label)
                .contains("Save percentage", "xGOT faced", "Goals prevented per 90", "Penalty saves",
                        "Crosses claimed", "Sweeping actions", "Short distribution", "Long distribution",
                        "Average position height", "Distance to defensive line");
    }

    @Test
    void exposesPer90ContextsAndNonApplicableGoalkeeperState() {
        HumanRepository humans = mock(HumanRepository.class);
        PlayerSkillsRepository skillsRepository = mock(PlayerSkillsRepository.class);
        PlayerSeasonStatRepository seasonStats = mock(PlayerSeasonStatRepository.class);
        ScorerRepository scorers = mock(ScorerRepository.class);
        MatchStatsRepository matches = mock(MatchStatsRepository.class);

        Human player = new Human(); player.setId(7); player.setName("Lab Player"); player.setTeamId(10L); player.setPosition("CM");
        PlayerSkills skills = new PlayerSkills(); skills.setPlayerId(7); skills.setPosition("CM"); skills.setPassing(16); skills.setVision(15); skills.setWorkRate(14);
        PlayerSeasonStat stat = new PlayerSeasonStat(); stat.setPlayerId(7); stat.setTeamId(10); stat.setSeasonNumber(3);
        stat.setAppearances(2); stat.setMinutes(180); stat.setShots(1.4); stat.setPassesAttempted(100); stat.setPassesCompleted(84);
        stat.setChancesCreated(5); stat.setDribblesCompleted(4); stat.setPressures(22); stat.setCounterpressures(8); stat.setTackles(5); stat.setDefensiveActions(12);
        Scorer app = new Scorer(); app.setId(1); app.setPlayerId(7); app.setTeamId(10); app.setOpponentTeamId(20); app.setCompetitionId(1);
        app.setSeasonNumber(3); app.setRoundNumber(1); app.setTeamScore(2); app.setOpponentScore(1); app.setGoals(1); app.setAssists(1); app.setRating(8.1); app.setPosition("CM");
        MatchStats match = new MatchStats(); match.setId(99); match.setCompetitionId(1); match.setSeasonNumber(3); match.setRoundNumber(1); match.setTeam1Id(10); match.setTeam2Id(20);

        when(humans.findById(7L)).thenReturn(Optional.of(player));
        when(humans.findAllById(any())).thenReturn(List.of(player));
        when(humans.findAllByTeamIdAndTypeId(10, 1)).thenReturn(List.of(player));
        when(humans.findAllByTypeIdAndRetiredFalseAndTeamIdIsNotNullAndTeamIdNotAndPosition(anyLong(), anyLong(), anyString(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(skillsRepository.findPlayerSkillsByPlayerId(7)).thenReturn(Optional.of(skills));
        when(skillsRepository.findAllByPlayerIdIn(any())).thenReturn(List.of(skills));
        when(seasonStats.findAllBySeasonNumber(3)).thenReturn(List.of(stat));
        when(scorers.findByPlayerIdAndSeasonNumber(7, 3)).thenReturn(List.of(app));
        when(scorers.findAllByTeamIdAndSeasonNumber(10, 3)).thenReturn(List.of(app));
        when(matches.findAllBySeasonNumber(3)).thenReturn(List.of(match));

        PlayerPerformanceLabService service = new PlayerPerformanceLabService(humans, skillsRepository, seasonStats,
                scorers, matches, mock(ShotEventService.class), mock(DefensivePressureLedgerService.class));

        PlayerPerformanceLabService.PerformanceLab lab = service.performance(7, 3);
        PlayerPerformanceLabService.GoalkeeperHub goalkeeper = service.goalkeeper(7, 3);

        assertThat(lab.appearances()).isEqualTo(2);
        assertThat(lab.metrics()).extracting(PlayerPerformanceLabService.Metric::label)
                .contains("xG", "Non-penalty xG", "xA", "Progressive passes", "Possession value");
        assertThat(lab.contexts().home().appearances()).isEqualTo(1);
        assertThat(lab.contexts().starter().averageRating()).isEqualTo(8.1);
        assertThat(lab.roleSuitability()).isNotEmpty();
        assertThat(goalkeeper.eligible()).isFalse();
        assertThat(goalkeeper.message()).contains("not applicable");
    }
}
