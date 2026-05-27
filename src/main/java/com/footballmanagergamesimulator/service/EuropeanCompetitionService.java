package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.algorithms.RoundRobin;
import com.footballmanagergamesimulator.controller.CompetitionController;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * European competition mechanics — LoC + Stars Cup group draws, group-stage
 * fixture generation, post-group qualification (LoC top-2 → QF, 3rd → SC
 * playoff; SC winner → QF, runner-up → playoff), seeded knockout draws, and
 * the LoC-losers-drop-to-SC handoff. Lifted out of
 * {@link CompetitionController} as the first slice of the controller-shrink
 * effort (~530 lines moved); the controller now delegates via thin wrappers
 * so its callers (the {@code play()} orchestrator + the simulate-round
 * dispatch) need no changes.
 *
 * <p>Cross-cutting helpers (current season, club coefficient) are accessed
 * back through {@link CompetitionController} via a {@code @Lazy} reference —
 * intentional pragmatic shortcut; a future slice can extract those helpers
 * into their own service to break the back-reference.
 */
@Service
public class EuropeanCompetitionService {

    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired private CompetitionTeamInfoMatchRepository competitionTeamInfoMatchRepository;
    @Autowired private CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;
    @Autowired private TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private RoundRobin roundRobin;

    /** Back-reference used only for {@code getClubCoefficientRolling} (the
     *  season-rolling coefficient lookup still lives on the controller).
     *  {@code @Lazy} breaks the otherwise-circular bean wiring. */
    @Lazy @Autowired private CompetitionController competitionController;

    // ============================================================
    //  Round classification
    // ============================================================

    /**
     * Whether a given (competition, round) is a knockout-style fixture (single
     * tie, winner advances) vs. a group-stage / league fixture.
     *
     * <p>Rules: cups (typeId 2) are always knockout. Stars Cup (typeId 5) is
     * groups for rounds 1-6 and knockout from round 7 (playoff, QF, SF, Final).
     * LoC (typeId 4) is knockout for rounds 0-1 (preliminary, qualifying) and
     * rounds 8-10 (QF, SF, Final); groups for rounds 2-7.
     */
    public boolean isKnockoutRound(long competitionId, long roundId) {
        Set<Long> cupIds = competitionIdsByType(2);
        if (cupIds.contains(competitionId)) return true;

        Set<Long> starsCupIds = competitionIdsByType(5);
        if (starsCupIds.contains(competitionId) && roundId >= 7) return true;

        Set<Long> locIds = competitionIdsByType(4);
        if (locIds.contains(competitionId) && (roundId <= 1 || roundId >= 8)) return true;

        return false;
    }

    // ============================================================
    //  Helpers (shared between methods)
    // ============================================================

    public long getTeamNationId(long teamId) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) return -1;
        Competition comp = competitionRepository.findById(team.getCompetitionId()).orElse(null);
        return comp != null ? comp.getNationId() : -1;
    }

    /** Loads the current season number from the singleton Round row. */
    private String currentSeason() {
        return String.valueOf(roundRepository.findById(1L).map(Round::getSeason).orElse(1L));
    }

    private Set<Long> competitionIdsByType(int typeId) {
        return competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == typeId)
                .map(Competition::getId)
                .collect(Collectors.toSet());
    }

    // ============================================================
    //  Group draws + fixture generation
    // ============================================================

    /**
     * Draw a European group stage: pots seeded by club coefficient
     * (descending, prior seasons only, reputation as tiebreaker), one team
     * per pot per group, with same-nation conflict avoidance.
     */
    public void drawEuropeanGroups(long competitionId, int groupStageRound) {
        long currentSeason = Long.parseLong(currentSeason());
        int coeffSeason = (int) currentSeason - 1;
        List<CompetitionTeamInfo> entries = competitionTeamInfoRepository
                .findAllBySeasonNumber(currentSeason).stream()
                .filter(cti -> cti.getCompetitionId() == competitionId && cti.getRound() == groupStageRound)
                .collect(Collectors.toList());

        if (entries.size() < 4) return;

        entries.sort((a, b) -> {
            double coeffA = competitionController.getClubCoefficientRolling(a.getTeamId(), coeffSeason);
            double coeffB = competitionController.getClubCoefficientRolling(b.getTeamId(), coeffSeason);
            if (coeffA != coeffB) return Double.compare(coeffB, coeffA);
            Team teamA = teamRepository.findById(a.getTeamId()).orElse(new Team());
            Team teamB = teamRepository.findById(b.getTeamId()).orElse(new Team());
            return Integer.compare(teamB.getReputation(), teamA.getReputation());
        });

        int numGroups = Math.min(4, Math.max(1, entries.size() / 4));
        int totalSlots = numGroups * 4;
        if (entries.size() > totalSlots) {
            System.out.println("=== drawEuropeanGroups: trimming " + (entries.size() - totalSlots)
                    + " lowest-seeded teams from " + entries.size() + " to fit " + numGroups + " groups of 4");
            entries = new ArrayList<>(entries.subList(0, totalSlots));
        }
        int potSize = totalSlots / numGroups;

        List<List<CompetitionTeamInfo>> pots = new ArrayList<>();
        for (int p = 0; p < numGroups; p++) {
            List<CompetitionTeamInfo> pot = new ArrayList<>(entries.subList(p * potSize, (p + 1) * potSize));
            Collections.shuffle(pot);
            pots.add(pot);
        }

        List<List<CompetitionTeamInfo>> groups = new ArrayList<>();
        for (int g = 0; g < numGroups; g++) groups.add(new ArrayList<>());

        for (int potIndex = 0; potIndex < pots.size(); potIndex++) {
            List<CompetitionTeamInfo> pot = pots.get(potIndex);
            for (int g = 0; g < numGroups; g++) {
                CompetitionTeamInfo candidate = pot.get(g);
                long candidateNation = getTeamNationId(candidate.getTeamId());

                boolean conflict = groups.get(g).stream()
                        .anyMatch(existing -> getTeamNationId(existing.getTeamId()) == candidateNation);

                if (conflict) {
                    boolean swapped = false;
                    for (int s = g + 1; s < numGroups; s++) {
                        CompetitionTeamInfo swapCandidate = pot.get(s);
                        long swapNation = getTeamNationId(swapCandidate.getTeamId());
                        boolean swapConflictInTargetGroup = groups.get(g).stream()
                                .anyMatch(ex -> getTeamNationId(ex.getTeamId()) == swapNation);
                        boolean originalConflictInSwapGroup = groups.get(s).stream()
                                .anyMatch(ex -> getTeamNationId(ex.getTeamId()) == candidateNation);
                        if (!swapConflictInTargetGroup && !originalConflictInSwapGroup) {
                            pot.set(g, swapCandidate);
                            pot.set(s, candidate);
                            candidate = swapCandidate;
                            swapped = true;
                            break;
                        }
                    }
                    if (!swapped) {
                        System.out.println("=== drawEuropeanGroups: could not avoid same-nation conflict for team "
                                + candidate.getTeamId() + " in group " + (g + 1));
                    }
                }

                candidate.setGroupNumber(g + 1);
                candidate.setPotNumber(potIndex + 1);
                groups.get(g).add(candidate);
                competitionTeamInfoRepository.save(candidate);
            }
        }

        for (int g = 0; g < numGroups; g++) {
            System.out.println("  Group " + (g + 1) + ": " + groups.get(g).stream()
                    .map(cti -> teamRepository.findById(cti.getTeamId()).map(Team::getName).orElse("?")
                            + "(P" + cti.getPotNumber() + ")")
                    .collect(Collectors.joining(", ")));
        }
    }

    /**
     * Seeded knockout draw for European competitions. Splits by club
     * coefficient into seeded (Pot 1) + unseeded (Pot 2), pairs them with
     * same-nation avoidance. Seeded team is home (team1).
     */
    public void drawEuropeanKnockoutSeeded(long competitionId, long roundId, List<Long> participants) {
        int coeffSeason = Integer.parseInt(currentSeason()) - 1;

        participants.sort((a, b) -> {
            double coeffA = competitionController.getClubCoefficientRolling(a, coeffSeason);
            double coeffB = competitionController.getClubCoefficientRolling(b, coeffSeason);
            if (coeffA != coeffB) return Double.compare(coeffB, coeffA);
            Team teamA = teamRepository.findById(a).orElse(new Team());
            Team teamB = teamRepository.findById(b).orElse(new Team());
            return Integer.compare(teamB.getReputation(), teamA.getReputation());
        });

        int half = participants.size() / 2;
        List<Long> seeded = new ArrayList<>(participants.subList(0, half));
        List<Long> unseeded = new ArrayList<>(participants.subList(half, participants.size()));
        Collections.shuffle(seeded);
        Collections.shuffle(unseeded);

        for (int i = 0; i < seeded.size() && i < unseeded.size(); i++) {
            long seededNation = getTeamNationId(seeded.get(i));
            long unseededNation = getTeamNationId(unseeded.get(i));
            if (seededNation == unseededNation && seededNation != -1) {
                for (int j = i + 1; j < unseeded.size(); j++) {
                    long swapNation = getTeamNationId(unseeded.get(j));
                    long seededAtJ = (j < seeded.size()) ? getTeamNationId(seeded.get(j)) : -1;
                    if (swapNation != seededNation && unseededNation != seededAtJ) {
                        Collections.swap(unseeded, i, j);
                        break;
                    }
                }
            }
        }

        System.out.println("=== drawEuropeanKnockoutSeeded: comp=" + competitionId + " round=" + roundId);
        for (int i = 0; i < seeded.size() && i < unseeded.size(); i++) {
            long homeId = seeded.get(i);
            long awayId = unseeded.get(i);

            CompetitionTeamInfoMatch match = new CompetitionTeamInfoMatch();
            match.setCompetitionId(competitionId);
            match.setRound(roundId);
            match.setTeam1Id(homeId);
            match.setTeam2Id(awayId);
            match.setSeasonNumber(currentSeason());
            competitionTeamInfoMatchRepository.save(match);

            String homeName = teamRepository.findById(homeId).map(Team::getName).orElse("?");
            String awayName = teamRepository.findById(awayId).map(Team::getName).orElse("?");
            System.out.println("  " + homeName + " (seeded) vs " + awayName);
        }
    }

    /**
     * Stars Cup playoff seeded draw: LoC 3rd-place teams (Pot 1, seeded) vs
     * SC group runners-up (Pot 2, unseeded). LoC 3rd-place teams are
     * identified by having a {@code groupNumber > 0} entry in the LoC comp.
     */
    public void drawStarsCupPlayoffSeeded(long starsCupCompetitionId, long roundId, List<Long> participants) {
        long currentSeason = Long.parseLong(currentSeason());

        long locCompetitionId = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 4).findFirst()
                .map(Competition::getId).orElse(-1L);

        // LoC 3rd-place teams only — preliminary/qualifying losers entered
        // Stars Cup at round 1 (groups), not round 7 (playoff).
        Set<Long> locGroupTeams = competitionTeamInfoRepository.findAllBySeasonNumber(currentSeason).stream()
                .filter(cti -> cti.getCompetitionId() == locCompetitionId && cti.getGroupNumber() > 0)
                .map(CompetitionTeamInfo::getTeamId)
                .collect(Collectors.toSet());

        List<Long> seeded = new ArrayList<>();
        List<Long> unseeded = new ArrayList<>();
        for (Long teamId : participants) {
            if (locGroupTeams.contains(teamId)) seeded.add(teamId);
            else unseeded.add(teamId);
        }

        Collections.shuffle(seeded);
        Collections.shuffle(unseeded);

        while (seeded.size() > unseeded.size() && seeded.size() > 0) {
            unseeded.add(seeded.remove(seeded.size() - 1));
        }
        while (unseeded.size() > seeded.size() && unseeded.size() > 0) {
            seeded.add(unseeded.remove(unseeded.size() - 1));
        }

        for (int i = 0; i < seeded.size() && i < unseeded.size(); i++) {
            long seededNation = getTeamNationId(seeded.get(i));
            long unseededNation = getTeamNationId(unseeded.get(i));
            if (seededNation == unseededNation && seededNation != -1) {
                for (int j = i + 1; j < unseeded.size(); j++) {
                    long swapNation = getTeamNationId(unseeded.get(j));
                    long seededAtJ = (j < seeded.size()) ? getTeamNationId(seeded.get(j)) : -1;
                    if (swapNation != seededNation && unseededNation != seededAtJ) {
                        Collections.swap(unseeded, i, j);
                        break;
                    }
                }
            }
        }

        System.out.println("=== drawStarsCupPlayoffSeeded: LoC 3rd (seeded) vs SC runners-up (unseeded)");
        for (int i = 0; i < seeded.size() && i < unseeded.size(); i++) {
            long homeId = seeded.get(i);
            long awayId = unseeded.get(i);

            CompetitionTeamInfoMatch match = new CompetitionTeamInfoMatch();
            match.setCompetitionId(starsCupCompetitionId);
            match.setRound(roundId);
            match.setTeam1Id(homeId);
            match.setTeam2Id(awayId);
            match.setSeasonNumber(currentSeason());
            competitionTeamInfoMatchRepository.save(match);

            String homeName = teamRepository.findById(homeId).map(Team::getName).orElse("?");
            String awayName = teamRepository.findById(awayId).map(Team::getName).orElse("?");
            System.out.println("  " + homeName + " (LoC 3rd, seeded) vs " + awayName + " (SC runner-up)");
        }
    }

    /** Reset all TeamCompetitionDetail stats for the given competition's
     *  current-season participants — used before the group stage begins. */
    public void resetEuropeanStats(long competitionId) {
        long currentSeason = Long.parseLong(currentSeason());
        List<CompetitionTeamInfo> entries = competitionTeamInfoRepository
                .findAllBySeasonNumber(currentSeason).stream()
                .filter(cti -> cti.getCompetitionId() == competitionId)
                .toList();
        for (CompetitionTeamInfo cti : entries) {
            TeamCompetitionDetail detail = teamCompetitionDetailRepository
                    .findFirstByTeamIdAndCompetitionId(cti.getTeamId(), competitionId);
            if (detail != null) {
                detail.setGames(0);
                detail.setWins(0);
                detail.setDraws(0);
                detail.setLoses(0);
                detail.setGoalsFor(0);
                detail.setGoalsAgainst(0);
                detail.setGoalDifference(0);
                detail.setPoints(0);
                detail.setForm("");
                teamCompetitionDetailRepository.save(detail);
            }
        }
    }

    /** Build round-robin fixtures for every group of the given European
     *  competition. LoC groups start at round 2; Stars Cup at round 1. */
    public void generateGroupStageFixtures(long competitionId) {
        Competition comp = competitionRepository.findById(competitionId).orElse(null);
        int roundOffset = (comp != null && comp.getTypeId() == 5) ? 0 : 1; // Stars Cup: 0, LoC: 1

        long currentSeason = Long.parseLong(currentSeason());
        List<CompetitionTeamInfo> entries = competitionTeamInfoRepository
                .findAllBySeasonNumber(currentSeason).stream()
                .filter(cti -> cti.getCompetitionId() == competitionId && cti.getGroupNumber() > 0)
                .toList();

        Map<Integer, List<Long>> groups = new HashMap<>();
        for (CompetitionTeamInfo cti : entries) {
            groups.computeIfAbsent(cti.getGroupNumber(), k -> new ArrayList<>()).add(cti.getTeamId());
        }

        for (Map.Entry<Integer, List<Long>> group : groups.entrySet()) {
            List<Long> teams = group.getValue();
            if (teams.size() < 2) continue;

            List<List<List<Long>>> fullSchedule = roundRobin.getSchedule(teams);
            List<List<List<Long>>> firstLegRounds = fullSchedule.subList(0, fullSchedule.size() / 2);
            int matchday = 1;

            for (int leg = 0; leg < 2; leg++) {
                for (List<List<Long>> matchdayMatches : firstLegRounds) {
                    for (List<Long> match : matchdayMatches) {
                        long home = leg == 0 ? match.get(0) : match.get(1);
                        long away = leg == 0 ? match.get(1) : match.get(0);

                        CompetitionTeamInfoMatch fixture = new CompetitionTeamInfoMatch();
                        fixture.setCompetitionId(competitionId);
                        fixture.setRound(matchday + roundOffset);
                        fixture.setTeam1Id(home);
                        fixture.setTeam2Id(away);
                        fixture.setSeasonNumber(currentSeason());
                        competitionTeamInfoMatchRepository.save(fixture);
                    }
                    matchday++;
                }
            }
            int startRound = 1 + roundOffset;
            System.out.println("  Group " + group.getKey() + ": " + teams.size() + " teams, " + matchday + " matchdays (rounds " + startRound + "-" + (matchday - 1 + roundOffset) + ")");
        }
    }

    // ============================================================
    //  Post-group qualification
    // ============================================================

    /** LoC group stage → QF (top 2) + Stars Cup playoff (3rd). */
    public void qualifyFromGroupStage(long locCompetitionId) {
        long currentSeason = Long.parseLong(currentSeason());
        List<CompetitionTeamInfo> entries = competitionTeamInfoRepository
                .findAllBySeasonNumber(currentSeason).stream()
                .filter(cti -> cti.getCompetitionId() == locCompetitionId && cti.getGroupNumber() > 0)
                .toList();

        Map<Integer, List<Long>> groups = new HashMap<>();
        for (CompetitionTeamInfo cti : entries) {
            groups.computeIfAbsent(cti.getGroupNumber(), k -> new ArrayList<>()).add(cti.getTeamId());
        }

        System.out.println("=== qualifyFromGroupStage: " + groups.size() + " groups, entries=" + entries.size());

        Set<Long> alreadyQualified = new HashSet<>();
        for (Map.Entry<Integer, List<Long>> group : groups.entrySet()) {
            List<Long> teamIds = group.getValue().stream().distinct().toList();
            List<TeamCompetitionDetail> standings = teamIds.stream()
                    .map(tid -> teamCompetitionDetailRepository.findFirstByTeamIdAndCompetitionId(tid, locCompetitionId))
                    .filter(Objects::nonNull)
                    .sorted((a, b) -> {
                        if (a.getPoints() != b.getPoints()) return b.getPoints() - a.getPoints();
                        if (a.getGoalDifference() != b.getGoalDifference()) return b.getGoalDifference() - a.getGoalDifference();
                        return b.getGoalsFor() - a.getGoalsFor();
                    })
                    .toList();

            System.out.println("  Group " + group.getKey() + ": " + teamIds.size() + " teams, standings=" + standings.size());

            for (int i = 0; i < Math.min(2, standings.size()); i++) {
                long teamId = standings.get(i).getTeamId();
                if (alreadyQualified.contains(teamId)) {
                    System.out.println("  DUPLICATE team " + teamId + " skipped!");
                    continue;
                }
                alreadyQualified.add(teamId);
                CompetitionTeamInfo cti = new CompetitionTeamInfo();
                cti.setTeamId(teamId);
                cti.setCompetitionId(locCompetitionId);
                cti.setSeasonNumber(currentSeason);
                cti.setRound(8);
                competitionTeamInfoRepository.save(cti);
                String teamName = teamRepository.findById(teamId).map(t -> t.getName()).orElse("?");
                System.out.println("  -> Qualified for LoC QF: " + teamName + " (id=" + teamId + ") from group " + group.getKey());
            }

            if (standings.size() >= 3) {
                long thirdPlaceTeamId = standings.get(2).getTeamId();
                long starsCupCompetitionId = competitionRepository.findAll().stream()
                        .filter(c -> c.getTypeId() == 5).findFirst()
                        .map(Competition::getId).orElse(-1L);
                if (starsCupCompetitionId > 0) {
                    CompetitionTeamInfo scCti = new CompetitionTeamInfo();
                    scCti.setTeamId(thirdPlaceTeamId);
                    scCti.setCompetitionId(starsCupCompetitionId);
                    scCti.setSeasonNumber(currentSeason);
                    scCti.setRound(7);
                    competitionTeamInfoRepository.save(scCti);
                    String teamName = teamRepository.findById(thirdPlaceTeamId).map(t -> t.getName()).orElse("?");
                    System.out.println("  -> LoC 3rd place to Stars Cup: " + teamName + " (id=" + thirdPlaceTeamId + ")");
                }
            }
        }
        System.out.println("=== Total qualified for LoC QF: " + alreadyQualified.size());
    }

    /** After a LoC qualifying/preliminary round, drop the losing teams into
     *  Stars Cup group stage (round 1). */
    public void assignLocLosersToStarsCup(long locCompetitionId, int locRound) {
        long currentSeason = Long.parseLong(currentSeason());
        long starsCupCompetitionId = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 5).findFirst()
                .map(Competition::getId).orElse(-1L);
        if (starsCupCompetitionId <= 0) return;

        List<CompetitionTeamInfoDetail> results = competitionTeamInfoDetailRepository
                .findAllByCompetitionIdAndRoundIdAndSeasonNumber(locCompetitionId, locRound, currentSeason);

        int nextRound = locRound + 1;
        Set<Long> winners = competitionTeamInfoRepository.findAllBySeasonNumber(currentSeason).stream()
                .filter(cti -> cti.getCompetitionId() == locCompetitionId && cti.getRound() == nextRound)
                .map(CompetitionTeamInfo::getTeamId)
                .collect(Collectors.toSet());

        Set<Long> participants = competitionTeamInfoRepository.findAllBySeasonNumber(currentSeason).stream()
                .filter(cti -> cti.getCompetitionId() == locCompetitionId && cti.getRound() == locRound)
                .map(CompetitionTeamInfo::getTeamId)
                .collect(Collectors.toSet());

        for (long teamId : participants) {
            if (!winners.contains(teamId)) {
                boolean alreadyInStarsCup = competitionTeamInfoRepository.findAllBySeasonNumber(currentSeason).stream()
                        .anyMatch(cti -> cti.getTeamId() == teamId && cti.getCompetitionId() == starsCupCompetitionId);
                if (!alreadyInStarsCup) {
                    CompetitionTeamInfo cti = new CompetitionTeamInfo();
                    cti.setTeamId(teamId);
                    cti.setCompetitionId(starsCupCompetitionId);
                    cti.setSeasonNumber(currentSeason);
                    cti.setRound(1);
                    competitionTeamInfoRepository.save(cti);
                    String teamName = teamRepository.findById(teamId).map(t -> t.getName()).orElse("?");
                    System.out.println("=== LoC round " + locRound + " loser to Stars Cup: " + teamName + " ===");
                }
            }
        }
    }

    /** Stars Cup group stage → QF (group winners) + playoff (runners-up
     *  meet LoC 3rd place). */
    public void qualifyFromStarsCupGroupStage(long starsCupCompetitionId) {
        long currentSeason = Long.parseLong(currentSeason());
        List<CompetitionTeamInfo> entries = competitionTeamInfoRepository
                .findAllBySeasonNumber(currentSeason).stream()
                .filter(cti -> cti.getCompetitionId() == starsCupCompetitionId && cti.getGroupNumber() > 0)
                .toList();

        Map<Integer, List<Long>> groups = new HashMap<>();
        for (CompetitionTeamInfo cti : entries) {
            groups.computeIfAbsent(cti.getGroupNumber(), k -> new ArrayList<>()).add(cti.getTeamId());
        }

        System.out.println("=== Stars Cup qualifyFromGroupStage: " + groups.size() + " groups");

        for (Map.Entry<Integer, List<Long>> group : groups.entrySet()) {
            List<Long> teamIds = group.getValue().stream().distinct().toList();
            List<TeamCompetitionDetail> standings = teamIds.stream()
                    .map(tid -> teamCompetitionDetailRepository.findFirstByTeamIdAndCompetitionId(tid, starsCupCompetitionId))
                    .filter(Objects::nonNull)
                    .sorted((a, b) -> {
                        if (a.getPoints() != b.getPoints()) return b.getPoints() - a.getPoints();
                        if (a.getGoalDifference() != b.getGoalDifference()) return b.getGoalDifference() - a.getGoalDifference();
                        return b.getGoalsFor() - a.getGoalsFor();
                    })
                    .toList();

            if (!standings.isEmpty()) {
                long winnerId = standings.get(0).getTeamId();
                CompetitionTeamInfo cti = new CompetitionTeamInfo();
                cti.setTeamId(winnerId);
                cti.setCompetitionId(starsCupCompetitionId);
                cti.setSeasonNumber(currentSeason);
                cti.setRound(8);
                competitionTeamInfoRepository.save(cti);
                String teamName = teamRepository.findById(winnerId).map(t -> t.getName()).orElse("?");
                System.out.println("  -> Stars Cup group winner to QF: " + teamName);
            }

            if (standings.size() >= 2) {
                long runnerUpId = standings.get(1).getTeamId();
                CompetitionTeamInfo cti = new CompetitionTeamInfo();
                cti.setTeamId(runnerUpId);
                cti.setCompetitionId(starsCupCompetitionId);
                cti.setSeasonNumber(currentSeason);
                cti.setRound(7);
                competitionTeamInfoRepository.save(cti);
                String teamName = teamRepository.findById(runnerUpId).map(t -> t.getName()).orElse("?");
                System.out.println("  -> Stars Cup runner-up to playoff: " + teamName);
            }
        }
    }
}
