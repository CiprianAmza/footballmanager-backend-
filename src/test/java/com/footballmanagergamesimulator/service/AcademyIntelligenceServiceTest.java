package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamFacilities;
import com.footballmanagergamesimulator.model.YouthPlayer;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamFacilitiesRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.YouthPlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AcademyIntelligenceServiceTest {

    private YouthPlayerRepository youthPlayers;
    private TeamRepository teams;
    private TeamFacilitiesRepository facilities;
    private HumanRepository humans;
    private RoundRepository rounds;
    private FacilityUpgradeService upgrades;
    private AcademyIntelligenceService service;

    @BeforeEach
    void setUp() {
        youthPlayers = mock(YouthPlayerRepository.class);
        teams = mock(TeamRepository.class);
        facilities = mock(TeamFacilitiesRepository.class);
        humans = mock(HumanRepository.class);
        rounds = mock(RoundRepository.class);
        upgrades = mock(FacilityUpgradeService.class);
        service = new AcademyIntelligenceService(youthPlayers, teams, facilities, humans, rounds, upgrades);
        Round round = new Round();
        round.setSeason(4);
        when(rounds.findById(1L)).thenReturn(Optional.of(round));
    }

    @Test
    void combinesProspectReadinessFacilitiesAndRealClubBenchmarking() {
        Team club = team(1, "Academy FC", 8, 3_000_000);
        Team rival = team(2, "Rival FC", 8, 2_000_000);
        TeamFacilities clubFacilities = facilities(1, 8, 7);
        TeamFacilities rivalFacilities = facilities(2, 3, 2);
        YouthPlayer ready = prospect(10, 1, "Ready Talent", 17, "MC", 72, 80, "IN_ACADEMY");
        YouthPlayer developing = prospect(11, 1, "Young Talent", 15, "ST", 45, 85, "IN_ACADEMY");
        YouthPlayer graduate = prospect(12, 1, "Graduate", 18, "DC", 70, 75, "PROMOTED");
        YouthPlayer rivalProspect = prospect(20, 2, "Rival Prospect", 17, "GK", 40, 60, "IN_ACADEMY");
        Human hoyd = new Human();
        hoyd.setTeamId(1L);
        hoyd.setWorkingWithYoungsters(18);

        when(teams.findById(1L)).thenReturn(Optional.of(club));
        when(teams.findAll()).thenReturn(List.of(club, rival));
        when(facilities.findByTeamId(1L)).thenReturn(clubFacilities);
        when(facilities.findAllByTeamIdIn(any())).thenReturn(List.of(clubFacilities, rivalFacilities));
        when(youthPlayers.findAllByTeamId(1L)).thenReturn(List.of(ready, developing, graduate));
        when(youthPlayers.findAll()).thenReturn(List.of(ready, developing, graduate, rivalProspect));
        when(humans.findAllByTeamIdAndTypeId(1L, 10L)).thenReturn(List.of(hoyd));
        when(humans.findAllByTeamIdInAndTypeIdIn(any(), any())).thenReturn(List.of(hoyd));
        when(upgrades.getFullFacilityOverview(1L)).thenReturn(Map.of(
                "upgradesInProgress", List.of(),
                "availableUpgrades", List.of(
                        option("YOUTH_ACADEMY", 8, 9, 3_600_000L, 70),
                        option("YOUTH_TRAINING", 7, 8, 2_400_000L, 60),
                        option("PARKING", 2, 3, 1_200_000L, 30))));

        AcademyIntelligenceService.AcademyOverview result = service.overview(1);

        assertEquals(2, result.pipeline().active());
        assertEquals(1, result.pipeline().ready());
        assertEquals(2, result.pipeline().elitePotential());
        assertEquals(1, result.pipeline().graduates());
        assertEquals(2, result.facilities().size());
        assertEquals("READY", result.prospects().get(1).recommendation());
        assertTrue(result.prospects().stream().allMatch(player -> player.projectedWage() >= 500));
        assertEquals(1, result.leagueRank());
        assertTrue(result.ranking().get(0).score() > result.ranking().get(1).score());
    }

    private Map<String, Object> option(String type, int current, int target, long cost, int duration) {
        return Map.of("type", type, "name", type, "description", "Description", "currentLevel", current,
                "maxLevel", 10, "canUpgrade", true, "upgradeCost", cost, "upgradeDuration", duration);
    }

    private Team team(long id, String name, long competitionId, long money) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        team.setCompetitionId(competitionId);
        team.setTotalFinances(money);
        return team;
    }

    private TeamFacilities facilities(long teamId, long academy, long training) {
        TeamFacilities value = new TeamFacilities();
        value.setTeamId(teamId);
        value.setYouthAcademyLevel(academy);
        value.setYouthTrainingLevel(training);
        return value;
    }

    private YouthPlayer prospect(long id, long teamId, String name, int age, String position,
                                 int ability, int potential, String status) {
        YouthPlayer player = new YouthPlayer();
        player.setId(id);
        player.setTeamId(teamId);
        player.setName(name);
        player.setAge(age);
        player.setPosition(position);
        player.setCurrentAbility(ability);
        player.setPotentialAbility(potential);
        player.setPotential(potential >= 75 ? "STAR" : "AVERAGE");
        player.setStatus(status);
        player.setSeasonJoined(4);
        return player;
    }
}
