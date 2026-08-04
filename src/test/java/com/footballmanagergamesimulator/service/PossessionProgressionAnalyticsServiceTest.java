package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.PlayerSeasonStat;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.PlayerSeasonStatRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.PossessionProgressionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PossessionProgressionAnalyticsServiceTest {
    private final MatchStatsRepository matches = mock(MatchStatsRepository.class);
    private final PossessionProgressionRepository progressionRows = mock(PossessionProgressionRepository.class);
    private final PlayerSeasonStatRepository seasonStats = mock(PlayerSeasonStatRepository.class);
    private final HumanRepository humans = mock(HumanRepository.class);
    private final PlayerSkillsRepository skills = mock(PlayerSkillsRepository.class);
    private final PossessionProgressionAnalyticsService service = new PossessionProgressionAnalyticsService(
            matches, new PossessionProgressionLedgerService(progressionRows), seasonStats, humans, skills);

    @Test
    void aggregatesUsefulPossessionAndAllocatesPlayerProgression() {
        MatchStats match = PossessionProgressionLedgerServiceTest.match();
        when(matches.findAllByTeam1IdAndSeasonNumber(10, 2)).thenReturn(List.of(match));
        when(matches.findAllByTeam2IdAndSeasonNumber(10, 2)).thenReturn(List.of());
        when(progressionRows.findAllByMatchStatsIdOrderByTeamIdAsc(99)).thenReturn(List.of());
        when(seasonStats.findAllByTeamIdAndSeasonNumber(10, 2))
                .thenReturn(List.of(stat(101, 10, 620, 20, 12), stat(102, 10, 410, 9, 22)));
        when(humans.findAllById(any())).thenReturn(List.of(human(101, "Playmaker", "MC"), human(102, "Runner", "AML")));
        when(skills.findAllByPlayerIdIn(any())).thenReturn(List.of(skill(101, 18, 17, 12), skill(102, 13, 14, 18)));

        PossessionProgressionAnalyticsService.PossessionProgressionAnalytics result = service.analytics(10, 2);

        assertThat(result.matches()).isEqualTo(1);
        assertThat(result.totals().progressivePasses()).isPositive();
        assertThat(result.totals().progressiveCarries()).isPositive();
        assertThat(result.totals().penaltyAreaEntries()).isLessThanOrEqualTo(result.totals().finalThirdEntries());
        assertThat(result.averages().fieldTiltPercentage()).isBetween(0.0, 100.0);
        assertThat(result.funnel().shots()).isEqualTo(15);
        assertThat(result.playerContributions()).hasSize(2);
        assertThat(result.playerContributions()).extracting(PossessionProgressionAnalyticsService.PlayerContribution::name)
                .containsExactlyInAnyOrder("Playmaker", "Runner");
        assertThat(result.playerContributions().stream().mapToDouble(
                PossessionProgressionAnalyticsService.PlayerContribution::progressivePasses).sum())
                .isCloseTo(result.totals().progressivePasses(), org.assertj.core.data.Offset.offset(.02));
        assertThat(result.dataQuality().teamMetrics()).isEqualTo("MODELED_FROM_MATCH_STATS");
    }

    private PlayerSeasonStat stat(long playerId, long teamId, double passes, double chances, double dribbles) {
        PlayerSeasonStat stat = new PlayerSeasonStat();
        stat.setPlayerId(playerId); stat.setTeamId(teamId); stat.setSeasonNumber(2);
        stat.setAppearances(10); stat.setMinutes(900); stat.setPassesCompleted(passes);
        stat.setChancesCreated(chances); stat.setDribblesCompleted(dribbles);
        return stat;
    }
    private Human human(long id, String name, String position) {
        Human human = new Human(); human.setId(id); human.setName(name); human.setPosition(position); return human;
    }
    private PlayerSkills skill(long id, int passing, int vision, int dribbling) {
        PlayerSkills skill = new PlayerSkills(); skill.setPlayerId(id); skill.setPassing(passing); skill.setVision(vision);
        skill.setDecisions(15); skill.setTechnique(16); skill.setDribbling(dribbling); skill.setPace(15);
        skill.setAcceleration(15); skill.setOffTheBall(15); skill.setFirstTouch(16); return skill;
    }
}
