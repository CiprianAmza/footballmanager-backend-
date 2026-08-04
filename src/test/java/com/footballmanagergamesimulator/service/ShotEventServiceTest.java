package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.ShotEvent;
import com.footballmanagergamesimulator.repository.ShotEventRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ShotEventServiceTest {

    private final ShotEventService service = new ShotEventService(mock(ShotEventRepository.class));

    @Test
    void producesACompleteDeterministicShotLedgerConsistentWithMatchStats() {
        MatchStats match = matchStats();

        List<ShotEvent> first = service.generate(match);
        List<ShotEvent> second = service.generate(match);
        List<ShotEvent> home = first.stream().filter(shot -> shot.getTeamId() == 10).toList();

        assertThat(first).hasSize(18);
        assertThat(home).hasSize(10);
        assertThat(home).filteredOn(shot -> "GOAL".equals(shot.getOutcome())).hasSize(2);
        assertThat(home).filteredOn(ShotEvent::isOnTarget).hasSize(5);
        assertThat(home).filteredOn(ShotEvent::isBigChance).hasSize(3);
        assertThat(home).allSatisfy(shot -> {
            assertThat(shot.getOriginX()).isBetween(0.0, 100.0);
            assertThat(shot.getOriginY()).isBetween(0.0, 100.0);
            assertThat(shot.getDistanceMeters()).isPositive();
            assertThat(shot.getDataQuality()).isEqualTo("MODELED");
            if (!shot.isOnTarget()) assertThat(shot.getXgot()).isZero();
        });
        assertThat(home.stream().mapToInt(ShotEvent::getXg).sum()).isEqualTo(15_000);
        assertThat(second).usingRecursiveFieldByFieldElementComparatorIgnoringFields("id").containsExactlyElementsOf(first);
    }

    private MatchStats matchStats() {
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
        return match;
    }
}
