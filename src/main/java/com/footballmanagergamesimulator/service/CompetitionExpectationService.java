package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Captures predictions once, before a competition has played a match. */
@Service
public class CompetitionExpectationService {
    private final CompetitionSeasonExpectationRepository expectations;
    private final CompetitionRepository competitions;
    private final CompetitionTeamInfoRepository entries;
    private final CompetitionTeamInfoMatchRepository fixtures;
    private final TeamCompetitionDetailRepository standings;
    private final CompetitionTeamInfoDetailRepository results;
    private final TeamRepository teams;
    private final HumanRepository people;

    public CompetitionExpectationService(CompetitionSeasonExpectationRepository expectations,
            CompetitionRepository competitions, CompetitionTeamInfoRepository entries,
            CompetitionTeamInfoMatchRepository fixtures,
            TeamCompetitionDetailRepository standings, CompetitionTeamInfoDetailRepository results,
            TeamRepository teams, HumanRepository people) {
        this.expectations = expectations;
        this.competitions = competitions;
        this.entries = entries;
        this.fixtures = fixtures;
        this.standings = standings;
        this.results = results;
        this.teams = teams;
        this.people = people;
    }

    @Transactional(readOnly = true)
    public Optional<CompetitionSeasonExpectation> openingPrediction(int season, long competitionId, long teamId) {
        return expectations.findBySeasonNumberAndCompetitionIdAndTeamId(season, competitionId, teamId);
    }

    @Transactional
    public void captureMissingBeforeMatches(int season) {
        Set<Long> captured = expectations.findAllBySeasonNumber(season).stream()
                .map(CompetitionSeasonExpectation::getCompetitionId).collect(Collectors.toSet());
        Set<Long> started = results.findAllBySeasonNumber(season).stream()
                .map(CompetitionTeamInfoDetail::getCompetitionId).collect(Collectors.toSet());
        Map<Long, Competition> competitionById = competitions.findAll().stream()
                .collect(Collectors.toMap(Competition::getId, c -> c));
        Map<Long, Set<Long>> participants = new HashMap<>();
        for (CompetitionTeamInfo entry : entries.findAllBySeasonNumber(season)) {
            participants.computeIfAbsent(entry.getCompetitionId(), ignored -> new HashSet<>()).add(entry.getTeamId());
        }
        for (CompetitionTeamInfoMatch fixture : fixtures.findAllBySeasonNumber(String.valueOf(season))) {
            if (fixture.getTeam1Id() > 0) participants.computeIfAbsent(fixture.getCompetitionId(),
                    ignored -> new HashSet<>()).add(fixture.getTeam1Id());
            if (fixture.getTeam2Id() > 0) participants.computeIfAbsent(fixture.getCompetitionId(),
                    ignored -> new HashSet<>()).add(fixture.getTeam2Id());
        }
        // League standings exist before round one; some cup entries are generated at the draw.
        for (TeamCompetitionDetail detail : standings.findAll()) {
            Competition competition = competitionById.get((long) detail.getCompetitionId());
            if (competition != null && (competition.isLeague() || competition.getTypeId() == 3)) {
                participants.computeIfAbsent(competition.getId(), ignored -> new HashSet<>()).add(detail.getTeamId());
            }
        }
        participants.keySet().removeIf(id -> captured.contains(id) || started.contains(id)
                || !competitionById.containsKey(id));
        if (participants.isEmpty()) return;

        Set<Long> teamIds = participants.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
        Map<Long, Team> teamById = teams.findAllById(teamIds).stream()
                .collect(Collectors.toMap(Team::getId, t -> t));
        Map<Long, List<Double>> ratings = people.findAllByTeamIdInAndTypeId(teamIds, TypeNames.PLAYER_TYPE).stream()
                .filter(p -> !p.isRetired())
                .collect(Collectors.groupingBy(Human::getTeamId,
                        Collectors.mapping(Human::getRating, Collectors.toList())));
        Map<Long, Double> strength = new HashMap<>();
        for (long teamId : teamIds) {
            strength.put(teamId, ratings.getOrDefault(teamId, List.of()).stream()
                    .sorted(Comparator.reverseOrder()).limit(11)
                    .mapToDouble(Double::doubleValue).average().orElse(0));
        }
        List<CompetitionSeasonExpectation> batch = new ArrayList<>();
        for (Map.Entry<Long, Set<Long>> item : participants.entrySet()) {
            Competition competition = competitionById.get(item.getKey());
            List<Long> ranked = item.getValue().stream().filter(teamById::containsKey)
                    .sorted(Comparator.<Long>comparingDouble(id -> strength.getOrDefault(id, 0.0)).reversed()
                            .thenComparing(Comparator.<Long>comparingInt(
                                    id -> teamById.get(id).getReputation()).reversed())
                            .thenComparingLong(Long::longValue))
                    .toList();
            if (ranked.size() < 2) continue;
            double prestige = CompetitionReputationService.reputationOf(competition) / 10_000.0;
            for (int i = 0; i < ranked.size(); i++) {
                long teamId = ranked.get(i);
                CompetitionSeasonExpectation row = new CompetitionSeasonExpectation();
                row.setSeasonNumber(season);
                row.setCompetitionId(competition.getId());
                row.setTeamId(teamId);
                row.setPredictedPosition(i + 1);
                row.setFieldSize(ranked.size());
                row.setTopElevenRating(strength.get(teamId));
                row.setTeamReputation(teamById.get(teamId).getReputation());
                row.setCompetitionPrestige(prestige);
                row.setCapturedAt(Instant.now());
                batch.add(row);
            }
        }
        expectations.saveAll(batch);
    }
}
