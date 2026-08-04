package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.ShotEventRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShotCreationAnalyticsServiceTest {

    private final MatchStatsRepository matchStatsRepository = mock(MatchStatsRepository.class);
    private final ShotEventRepository shotEventRepository = mock(ShotEventRepository.class);
    private final ShotEventService shotEventService = new ShotEventService(shotEventRepository);
    private final ShotCreationAnalyticsService service = new ShotCreationAnalyticsService(
            matchStatsRepository, shotEventService);

    @Test
    void aggregatesShotMapSourcesFunnelAndPostShotExecution() {
        MatchStats match = match();
        when(matchStatsRepository.findAllByTeam1IdAndSeasonNumber(10, 2)).thenReturn(List.of(match));
        when(matchStatsRepository.findAllByTeam2IdAndSeasonNumber(10, 2)).thenReturn(List.of());
        when(shotEventRepository.findAllByMatchStatsIdOrderByShotIndexAsc(99)).thenReturn(List.of());

        ShotCreationAnalyticsService.ShotCreationAnalytics result = service.analytics(10, 2);

        assertThat(result.matches()).isEqualTo(1);
        assertThat(result.shots()).isEqualTo(10);
        assertThat(result.goals()).isEqualTo(2);
        assertThat(result.xg()).isEqualTo(1.5);
        assertThat(result.xgPerShot()).isEqualTo(.15);
        assertThat(result.shotsInsideBox() + result.shotsOutsideBox()).isEqualTo(10);
        assertThat(result.shotMap()).hasSize(10);
        assertThat(result.xgBySituation()).isNotEmpty();
        assertThat(result.shotsByCreationType()).isNotEmpty();
        assertThat(result.chancesByChannel()).isNotEmpty();
        assertThat(result.topShotSequences()).isNotEmpty();
        assertThat(result.funnel().possessions()).isGreaterThanOrEqualTo(result.funnel().finalThirdEntries());
        assertThat(result.funnel().finalThirdEntries()).isGreaterThanOrEqualTo(result.funnel().boxEntries());
        assertThat(result.funnel().boxEntries()).isGreaterThanOrEqualTo(result.funnel().shots());
        assertThat(result.execution().xgot()).isPositive();
        assertThat(result.dataQuality().modeledShots()).isEqualTo(10);
    }

    private MatchStats match() {
        MatchStats match = new MatchStats();
        match.setId(99);
        match.setCompetitionId(1);
        match.setSeasonNumber(2);
        match.setRoundNumber(4);
        match.setTeam1Id(10);
        match.setTeam2Id(20);
        match.setHomeGoals(2);
        match.setAwayGoals(1);
        match.setHomeShots(10);
        match.setAwayShots(8);
        match.setHomeShotsOnTarget(5);
        match.setAwayShotsOnTarget(3);
        match.setHomeShotsBlocked(2);
        match.setAwayShotsBlocked(2);
        match.setHomeBigChances(3);
        match.setAwayBigChances(2);
        match.setHomeXg(150);
        match.setAwayXg(90);
        match.setHomeCorners(5);
        match.setAwayCorners(3);
        match.setHomeFreeKicks(10);
        match.setAwayFreeKicks(8);
        match.setHomePasses(480);
        match.setAwayPasses(410);
        match.setHomeCrossesAccurate(6);
        match.setAwayCrossesAccurate(4);
        return match;
    }
}
