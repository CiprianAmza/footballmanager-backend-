package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.EuropeanCompetitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end validation of the configurable League of Champions (typeId 4) entry
 * + preliminary trim. Seeds a 40-team field at round 0 and runs the real
 * preliminary dispatch (seeded draw with byes + match simulation + winner
 * propagation), asserting the field is trimmed to exactly the 16 group-stage
 * slots and that the group draw forms 4 groups of 4 — i.e. the variable-format
 * pipeline wired in Increment 1 actually runs through the live engine.
 */
@SpringBootTest
@DisplayName("Configurable LoC — 40 entrants trim through preliminaries to 16 group slots")
class LeagueOfChampionsConfigurableIT {

    @Autowired private EuropeanCompetitionService europeanCompetitionService;
    @Autowired private CompetitionController competitionController;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoRepository ctiRepository;
    @Autowired private CompetitionTeamInfoMatchRepository ctimRepository;
    @Autowired private CompetitionTeamInfoDetailRepository ctidRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private com.footballmanagergamesimulator.config.CompetitionFormatConfig competitionFormat;

    private long locId;

    @BeforeEach
    void resetAndSeed() {
        locId = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 4).map(Competition::getId).findFirst().orElseThrow();

        Round round = roundRepository.findById(1L).orElseThrow();
        round.setSeason(1);
        round.setRound(1);
        roundRepository.save(round);

        // Wipe any LoC state so the run is self-contained.
        ctiRepository.deleteAll(ctiRepository.findAll().stream()
                .filter(c -> c.getCompetitionId() == locId).toList());
        ctimRepository.deleteAll(ctimRepository.findAll().stream()
                .filter(m -> m.getCompetitionId() == locId).toList());
        ctidRepository.deleteAll(ctidRepository.findAll().stream()
                .filter(d -> d.getCompetitionId() == locId).toList());
    }

    @Test
    void fortyEntrantsTrimToSixteenThroughPreliminaries() {
        // Seed 40 distinct teams at the first preliminary round (round 0).
        List<Long> teamIds = teamRepository.findAll().stream()
                .sorted(Comparator.comparingLong(Team::getId))
                .map(Team::getId).limit(40).toList();
        assertEquals(40, teamIds.size(), "bootstrap must provide at least 40 teams");
        for (long teamId : teamIds) seedLocEntry(teamId, 0L);

        // Group-stage size from the production LoC format (single source of truth).
        var locFmt = competitionFormat.get(4);
        int slots = locFmt.groupCount() * locFmt.groupSize();

        // Round 0: 40 teams, eliminate min(24,20)=20 → no byes, 20 ties → 20 winners.
        europeanCompetitionService.drawEuropeanPreliminarySeeded(locId, 0L, slots);
        competitionController.simulateRound(String.valueOf(locId), "0");
        assertEquals(20, participantsAtRound(1L),
                "after prelim round 0, exactly 20 teams should reach round 1");

        // Round 1: 20 teams, eliminate min(4,10)=4 → 12 byes + 8 play → 16 reach the group draw.
        europeanCompetitionService.drawEuropeanPreliminarySeeded(locId, 1L, slots);
        competitionController.simulateRound(String.valueOf(locId), "1");
        assertEquals(16, participantsAtRound(2L),
                "after prelim round 1, exactly 16 teams should reach the group-draw round");

        // Group draw at round 2 → 4 groups of 4.
        europeanCompetitionService.drawEuropeanGroups(locId, 2);
        List<CompetitionTeamInfo> grouped = ctiRepository.findAllBySeasonNumber(1L).stream()
                .filter(c -> c.getCompetitionId() == locId && c.getGroupNumber() > 0).toList();
        assertEquals(16, grouped.stream().map(CompetitionTeamInfo::getTeamId).distinct().count(),
                "group stage must contain exactly 16 distinct teams");
        Set<Integer> groupNumbers = grouped.stream().map(CompetitionTeamInfo::getGroupNumber)
                .collect(Collectors.toSet());
        assertEquals(4, groupNumbers.size(), "there must be 4 groups");
        for (int g : groupNumbers) {
            long inGroup = grouped.stream().filter(c -> c.getGroupNumber() == g)
                    .map(CompetitionTeamInfo::getTeamId).distinct().count();
            assertEquals(4, inGroup, "group " + g + " must have 4 teams");
        }
    }

    private long participantsAtRound(long round) {
        return ctiRepository.findAllBySeasonNumber(1L).stream()
                .filter(c -> c.getCompetitionId() == locId && c.getRound() == round)
                .map(CompetitionTeamInfo::getTeamId).distinct().count();
    }

    private void seedLocEntry(long teamId, long round) {
        CompetitionTeamInfo cti = new CompetitionTeamInfo();
        cti.setTeamId(teamId);
        cti.setCompetitionId(locId);
        cti.setSeasonNumber(1);
        cti.setRound(round);
        cti.setGroupNumber(0);
        cti.setPotNumber(0);
        ctiRepository.save(cti);
    }
}
