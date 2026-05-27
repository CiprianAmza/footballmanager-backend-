package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.algorithms.RoundRobin;
import com.footballmanagergamesimulator.model.ClubCoefficient;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import com.footballmanagergamesimulator.repository.ClubCoefficientRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoDetailRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamCompetitionDetailRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    @Autowired private ClubCoefficientRepository clubCoefficientRepository;
    @Autowired private ManagerInboxRepository managerInboxRepository;
    @Autowired private UserContext userContext;
    /** Lazy because FinanceService → CompetitionController in some wiring paths.
     *  Used by the per-match European prize payouts. */
    @Lazy @Autowired private FinanceService financeService;

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
            double coeffA = getClubCoefficientRolling(a.getTeamId(), coeffSeason);
            double coeffB = getClubCoefficientRolling(b.getTeamId(), coeffSeason);
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
            double coeffA = getClubCoefficientRolling(a, coeffSeason);
            double coeffB = getClubCoefficientRolling(b, coeffSeason);
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

    // ============================================================
    //  Season-end European qualification (called from play() end of season)
    // ============================================================

    public void qualifyTeamsForEuropeanCompetitions() {
        long nextSeason = Long.parseLong(currentSeason()) + 1;
        System.out.println("=== qualifyTeamsForEuropeanCompetitions: qualifying for season " + nextSeason + " ===");

        // Find European competition IDs dynamically
        Optional<Competition> locOpt = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 4).findFirst();
        Optional<Competition> starsCupOpt = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 5).findFirst();
        if (locOpt.isEmpty() || starsCupOpt.isEmpty()) return;

        long locCompetitionId = locOpt.get().getId();
        long starsCupCompetitionId = starsCupOpt.get().getId();

        // Sort leagues by country coefficient (with reputation fallback for early seasons)
        List<Long> sortedLeagueIds = getLeagueIdsSortedByCoefficient();

        int numLeagues = sortedLeagueIds.size();
        if (numLeagues == 0) return;

        // Build standings for each league
        List<TeamCompetitionDetail> allDetails = teamCompetitionDetailRepository.findAll();
        Map<Long, List<TeamCompetitionDetail>> standingsByLeague = new HashMap<>();
        for (Long leagueId : sortedLeagueIds) {
            long lid = leagueId;
            List<TeamCompetitionDetail> standings = allDetails.stream()
                    .filter(d -> d.getCompetitionId() == lid)
                    .sorted((a, b) -> {
                        if (a.getPoints() != b.getPoints()) return b.getPoints() - a.getPoints();
                        if (a.getGoalDifference() != b.getGoalDifference()) return b.getGoalDifference() - a.getGoalDifference();
                        if (a.getGoalsFor() != b.getGoalsFor()) return b.getGoalsFor() - a.getGoalsFor();
                        // Fallback: sort by team reputation (for when no matches were played)
                        Team teamA = teamRepository.findById(a.getTeamId()).orElse(null);
                        Team teamB = teamRepository.findById(b.getTeamId()).orElse(null);
                        int repA = teamA != null ? teamA.getReputation() : 0;
                        int repB = teamB != null ? teamB.getReputation() : 0;
                        return Integer.compare(repB, repA);
                    })
                    .toList();
            standingsByLeague.put(leagueId, standings);
        }

        // LoC allocation — spots decrease with rank (non-increasing):
        // Rank 1: 4 (3 direct + 1 qualifying)
        // Rank 2: 4 (3 direct + 1 qualifying)
        // Rank 3: 3 (2 direct + 1 qualifying)
        // Rank 4: 3 (2 direct + 1 qualifying)
        // Rank 5: 3 (1 direct + 2 qualifying)
        // Rank 6: 2 (1 direct + 1 qualifying)
        // Rank 7: 2 (2 preliminary)
        // Flow: 2 preliminary → 1 winner joins qualifying (7+1=8) → 4 winners → groups (12+4=16)
        int[] directSpots =      {3, 3, 2, 2, 1, 1, 0};
        int[] qualifyingSpots =  {1, 1, 1, 1, 2, 1, 0};
        int[] preliminarySpots = {0, 0, 0, 0, 0, 0, 2};

        for (int rank = 1; rank <= Math.min(numLeagues, 7); rank++) {
            List<TeamCompetitionDetail> standings = standingsByLeague.get(sortedLeagueIds.get(rank - 1));
            if (standings == null || standings.isEmpty()) continue;
            int idx = rank - 1;

            // Direct to group stage (round 2)
            for (int i = 0; i < directSpots[idx]; i++) {
                if (standings.size() > i) {
                    saveLocQualifier(standings.get(i).getTeamId(), locCompetitionId, nextSeason, 2);
                }
            }

            // Qualifying round (round 1)
            int qStart = directSpots[idx];
            for (int i = 0; i < qualifyingSpots[idx]; i++) {
                int pos = qStart + i;
                if (standings.size() > pos) {
                    saveLocQualifier(standings.get(pos).getTeamId(), locCompetitionId, nextSeason, 1);
                }
            }

            // Preliminary qualifying (round 0)
            for (int i = 0; i < preliminarySpots[idx]; i++) {
                if (standings.size() > i) {
                    saveLocQualifier(standings.get(i).getTeamId(), locCompetitionId, nextSeason, 0);
                }
            }
        }

        // Collect all already-qualified team IDs (LoC + Stars Cup from league positions)
        Set<Long> alreadyQualified = new HashSet<>();
        List<CompetitionTeamInfo> nextSeasonEntries = competitionTeamInfoRepository.findAllBySeasonNumber(nextSeason);
        for (CompetitionTeamInfo cti : nextSeasonEntries) {
            if (cti.getCompetitionId() == locCompetitionId || cti.getCompetitionId() == starsCupCompetitionId) {
                alreadyQualified.add(cti.getTeamId());
            }
        }

        // Stars Cup allocation — spots decrease with rank (non-increasing):
        // 1 spot per nation is ALWAYS reserved for cup winner.
        // League-based spots:
        // Rank 1: 5th (1 spot) + 1 cup = 2
        // Rank 2: 5th (1 spot) + 1 cup = 2
        // Rank 3: 4th (1 spot) + 1 cup = 2
        // Rank 4: 4th (1 spot) + 1 cup = 2
        // Rank 5: (0 spots) + 1 cup = 1
        // Rank 6: (0 spots) + 1 cup = 1
        // Rank 7: (0 spots) + 1 cup = 1 (LoC losers add extra spots)
        int[][] starsCupPositions = {
            {4},        // Rank 1: 5th (0-based: 4)
            {4},        // Rank 2: 5th
            {3},        // Rank 3: 4th
            {3},        // Rank 4: 4th
            {},         // Rank 5: none (cup spot only)
            {},         // Rank 6: none (cup spot only)
            {}          // Rank 7: none (cup + LoC losers cover spots)
        };

        for (int rank = 1; rank <= Math.min(numLeagues, 7); rank++) {
            List<TeamCompetitionDetail> standings = standingsByLeague.get(sortedLeagueIds.get(rank - 1));
            int[] positions = starsCupPositions[rank - 1];
            for (int pos : positions) {
                if (standings.size() > pos) {
                    saveStarsCupQualifier(standings.get(pos).getTeamId(), starsCupCompetitionId, nextSeason);
                    alreadyQualified.add(standings.get(pos).getTeamId());
                }
            }
        }

        // Cup winner qualification for Stars Cup (1 reserved spot per nation)
        // The spot is always reserved. Rules:
        // 1. Cup winner already in LoC or Stars Cup → reserved spot goes to first non-qualified league team
        // 2. Cup winner NOT qualified → cup winner gets the reserved spot directly (no one removed)
        List<Competition> allComps = competitionRepository.findAll();

        for (int rank = 1; rank <= Math.min(numLeagues, 7); rank++) {
            long leagueId = sortedLeagueIds.get(rank - 1);
            Competition league = competitionRepository.findById(leagueId).orElse(null);
            if (league == null) continue;
            long nationId = league.getNationId();

            // Find the cup for this nation
            Optional<Competition> cupOpt = allComps.stream()
                    .filter(c -> c.getTypeId() == 2 && c.getNationId() == nationId)
                    .findFirst();
            if (cupOpt.isEmpty()) continue;

            long cupCompId = cupOpt.get().getId();

            // Find cup winner from TeamCompetitionDetail (sorted by points, the winner has most wins)
            List<TeamCompetitionDetail> cupStandings = allDetails.stream()
                    .filter(d -> d.getCompetitionId() == cupCompId)
                    .sorted((a, b) -> {
                        if (a.getPoints() != b.getPoints()) return b.getPoints() - a.getPoints();
                        if (a.getGoalDifference() != b.getGoalDifference()) return b.getGoalDifference() - a.getGoalDifference();
                        return b.getGoalsFor() - a.getGoalsFor();
                    })
                    .toList();

            if (cupStandings.isEmpty()) continue;

            long cupWinnerTeamId = cupStandings.get(0).getTeamId();
            System.out.println("=== Cup winner for nation " + nationId + ": team " + cupWinnerTeamId + " ===");

            // Check if cup winner is already qualified for European competition
            boolean alreadyInEurope = alreadyQualified.contains(cupWinnerTeamId);

            List<TeamCompetitionDetail> leagueStandings = standingsByLeague.get(leagueId);

            if (alreadyInEurope) {
                // Cup winner already qualified (LoC or Stars Cup from league)
                // Reserved cup spot goes to first non-qualified league team
                if (leagueStandings != null) {
                    for (TeamCompetitionDetail tcd : leagueStandings) {
                        if (!alreadyQualified.contains(tcd.getTeamId())) {
                            saveStarsCupQualifier(tcd.getTeamId(), starsCupCompetitionId, nextSeason);
                            alreadyQualified.add(tcd.getTeamId());
                            System.out.println("=== Cup winner already in Europe. Reserved spot to team " + tcd.getTeamId() + " ===");
                            break;
                        }
                    }
                }
            } else {
                // Cup winner NOT qualified — gets the reserved Stars Cup spot directly
                saveStarsCupQualifier(cupWinnerTeamId, starsCupCompetitionId, nextSeason);
                alreadyQualified.add(cupWinnerTeamId);
                System.out.println("=== Cup winner team " + cupWinnerTeamId + " qualified for Stars Cup (reserved cup spot) ===");
            }
        }
    }

    private void saveLocQualifier(long teamId, long locCompetitionId, long season, int round) {
        CompetitionTeamInfo cti = new CompetitionTeamInfo();
        cti.setTeamId(teamId);
        cti.setCompetitionId(locCompetitionId);
        cti.setSeasonNumber(season);
        cti.setRound(round);
        competitionTeamInfoRepository.save(cti);
        String teamName = teamRepository.findById(teamId).map(t -> t.getName()).orElse("?");
        System.out.println("=== LoC qualifier: " + teamName + " (team " + teamId + ") → round " + round + " season " + season + " ===");
    }

    private void saveStarsCupQualifier(long teamId, long starsCupCompetitionId, long season) {
        CompetitionTeamInfo cti = new CompetitionTeamInfo();
        cti.setTeamId(teamId);
        cti.setCompetitionId(starsCupCompetitionId);
        cti.setSeasonNumber(season);
        cti.setRound(1);
        competitionTeamInfoRepository.save(cti);
    }

    // ============================================================
    //  Coefficient + prize money per European match
    // ============================================================

    public void awardCoefficientPoints(long competitionId, long roundId, long team1Id, long team2Id, int score1, int score2) {
        Set<Long> locIds = competitionIdsByType(4);
        Set<Long> starsCupIds = competitionIdsByType(5);

        if (!locIds.contains(competitionId) && !starsCupIds.contains(competitionId)) return;

        boolean isLoC = locIds.contains(competitionId);
        int season = Integer.parseInt(currentSeason());

        double winPoints;
        double drawPoints;

        if (isLoC) {
            if (roundId <= 1) { winPoints = 1.0; drawPoints = 0; }           // Preliminary/QR (knockout)
            else if (roundId <= 7) { winPoints = 2.0; drawPoints = 1.0; }    // Group stage
            else if (roundId == 8) { winPoints = 3.0; drawPoints = 0; }      // QF
            else if (roundId == 9) { winPoints = 4.0; drawPoints = 0; }      // SF
            else { winPoints = 5.0; drawPoints = 0; }                        // Final
        } else {
            // Stars Cup
            if (roundId <= 6) { winPoints = 1.0; drawPoints = 0.5; }         // Group stage
            else if (roundId == 7) { winPoints = 1.0; drawPoints = 0; }      // Playoff
            else if (roundId == 8) { winPoints = 1.5; drawPoints = 0; }      // QF
            else if (roundId == 9) { winPoints = 2.0; drawPoints = 0; }      // SF
            else { winPoints = 2.5; drawPoints = 0; }                        // Final
        }

        if (score1 > score2) {
            addClubCoefficient(team1Id, season, winPoints);
        } else if (score2 > score1) {
            addClubCoefficient(team2Id, season, winPoints);
        } else {
            // Draw (only possible in LoC group stage)
            addClubCoefficient(team1Id, season, drawPoints);
            addClubCoefficient(team2Id, season, drawPoints);
        }

        // Award per-match European prize money
        awardEuropeanMatchPrizeMoney(competitionId, roundId, team1Id, team2Id, score1, score2, isLoC, season);
    }

    private void awardEuropeanMatchPrizeMoney(long competitionId, long roundId, long team1Id, long team2Id,
                                               int score1, int score2, boolean isLoC, int season) {
        Round currentRound = roundRepository.findById(1L).orElse(new Round());
        int roundNumber = (int) currentRound.getRound();
        String compName = isLoC ? "League of Champions" : "Stars Cup";

        if (isLoC) {
            // LoC Group Stage participation bonus (awarded once at round 2, first group match)
            if (roundId == 2) {
                awardPrizeMoney(team1Id, 20_000_000L, season, roundNumber,
                        compName + " Group Stage Qualification", "european_prize");
                awardPrizeMoney(team2Id, 20_000_000L, season, roundNumber,
                        compName + " Group Stage Qualification", "european_prize");
            }

            // LoC per-match results
            if (roundId >= 2 && roundId <= 7) {
                // Group stage: win = 5M, draw = 1.5M
                if (score1 > score2) {
                    awardPrizeMoney(team1Id, 5_000_000L, season, roundNumber,
                            compName + " Group Stage Win", "european_prize");
                } else if (score2 > score1) {
                    awardPrizeMoney(team2Id, 5_000_000L, season, roundNumber,
                            compName + " Group Stage Win", "european_prize");
                } else {
                    awardPrizeMoney(team1Id, 1_500_000L, season, roundNumber,
                            compName + " Group Stage Draw", "european_prize");
                    awardPrizeMoney(team2Id, 1_500_000L, season, roundNumber,
                            compName + " Group Stage Draw", "european_prize");
                }
            } else if (roundId == 8) {
                // QF qualification bonus (both teams qualified to play QF)
                awardPrizeMoney(team1Id, 15_000_000L, season, roundNumber,
                        compName + " Quarter-Final Qualification", "european_prize");
                awardPrizeMoney(team2Id, 15_000_000L, season, roundNumber,
                        compName + " Quarter-Final Qualification", "european_prize");
            } else if (roundId == 9) {
                // SF qualification bonus
                awardPrizeMoney(team1Id, 40_000_000L, season, roundNumber,
                        compName + " Semi-Final Qualification", "european_prize");
                awardPrizeMoney(team2Id, 40_000_000L, season, roundNumber,
                        compName + " Semi-Final Qualification", "european_prize");
            } else if (roundId == 10) {
                // Final: winner gets 100M, runner-up gets 50M
                if (score1 > score2) {
                    awardPrizeMoney(team1Id, 100_000_000L, season, roundNumber,
                            compName + " Winner", "european_prize");
                    awardPrizeMoney(team2Id, 50_000_000L, season, roundNumber,
                            compName + " Runner-Up", "european_prize");
                } else {
                    awardPrizeMoney(team2Id, 100_000_000L, season, roundNumber,
                            compName + " Winner", "european_prize");
                    awardPrizeMoney(team1Id, 50_000_000L, season, roundNumber,
                            compName + " Runner-Up", "european_prize");
                }
            }
        } else {
            // Stars Cup prizes (with group stage)
            if (roundId == 1) {
                // Group stage participation bonus (first matchday only)
                awardPrizeMoney(team1Id, 5_000_000L, season, roundNumber,
                        compName + " Group Stage Qualification", "european_prize");
                awardPrizeMoney(team2Id, 5_000_000L, season, roundNumber,
                        compName + " Group Stage Qualification", "european_prize");
            }
            if (roundId >= 1 && roundId <= 6) {
                // Group stage: win = 1.5M, draw = 500K
                if (score1 > score2) {
                    awardPrizeMoney(team1Id, 1_500_000L, season, roundNumber,
                            compName + " Group Stage Win", "european_prize");
                } else if (score2 > score1) {
                    awardPrizeMoney(team2Id, 1_500_000L, season, roundNumber,
                            compName + " Group Stage Win", "european_prize");
                } else {
                    awardPrizeMoney(team1Id, 500_000L, season, roundNumber,
                            compName + " Group Stage Draw", "european_prize");
                    awardPrizeMoney(team2Id, 500_000L, season, roundNumber,
                            compName + " Group Stage Draw", "european_prize");
                }
            } else if (roundId == 8) {
                // QF qualification
                awardPrizeMoney(team1Id, 5_000_000L, season, roundNumber,
                        compName + " Quarter-Final Qualification", "european_prize");
                awardPrizeMoney(team2Id, 5_000_000L, season, roundNumber,
                        compName + " Quarter-Final Qualification", "european_prize");
            } else if (roundId == 9) {
                // SF qualification
                awardPrizeMoney(team1Id, 10_000_000L, season, roundNumber,
                        compName + " Semi-Final Qualification", "european_prize");
                awardPrizeMoney(team2Id, 10_000_000L, season, roundNumber,
                        compName + " Semi-Final Qualification", "european_prize");
            } else if (roundId == 10) {
                // Final: winner 15M, runner-up 8M
                if (score1 > score2) {
                    awardPrizeMoney(team1Id, 15_000_000L, season, roundNumber,
                            compName + " Winner", "european_prize");
                    awardPrizeMoney(team2Id, 8_000_000L, season, roundNumber,
                            compName + " Runner-Up", "european_prize");
                } else {
                    awardPrizeMoney(team2Id, 15_000_000L, season, roundNumber,
                            compName + " Winner", "european_prize");
                    awardPrizeMoney(team1Id, 8_000_000L, season, roundNumber,
                            compName + " Runner-Up", "european_prize");
                }
            }
        }
    }

    private void awardPrizeMoney(long teamId, long amount, int season, int roundNumber, String reason, String category) {
        financeService.recordTransaction(teamId, season, roundNumber, "PRIZE_MONEY", reason, amount);

        // Send inbox notification only for human teams
        if (userContext.isHumanTeam(teamId)) {
            String formattedAmount = formatPrizeMoney(amount);
            ManagerInbox inbox = new ManagerInbox();
            inbox.setTeamId(teamId);
            inbox.setSeasonNumber(season);
            inbox.setRoundNumber(roundNumber);
            inbox.setTitle(reason);
            inbox.setContent("Your club has received " + formattedAmount + " for " + reason + ".");
            inbox.setCategory(category);
            inbox.setRead(false);
            inbox.setCreatedAt(System.currentTimeMillis());
            managerInboxRepository.save(inbox);
        }
    }

    private String formatPrizeMoney(long amount) {
        if (amount >= 1_000_000L) {
            double millions = amount / 1_000_000.0;
            if (millions == (long) millions) return (long) millions + "M";
            return String.format("%.1fM", millions);
        } else if (amount >= 1_000L) {
            return (amount / 1_000) + "K";
        }
        return String.valueOf(amount);
    }

    private void addClubCoefficient(long teamId, int season, double points) {
        if (points <= 0) return;
        Optional<ClubCoefficient> existing = clubCoefficientRepository.findByTeamIdAndSeasonNumber(teamId, season);
        if (existing.isPresent()) {
            ClubCoefficient cc = existing.get();
            cc.setPoints(cc.getPoints() + points);
            clubCoefficientRepository.save(cc);
        } else {
            ClubCoefficient cc = new ClubCoefficient();
            cc.setTeamId(teamId);
            cc.setSeasonNumber(season);
            cc.setPoints(points);
            clubCoefficientRepository.save(cc);
        }
    }

    /** Rolling 5-season sum of a club's European coefficient points. */
    public double getClubCoefficientRolling(long teamId, int currentSeason) {
        double total = 0;
        for (int s = Math.max(1, currentSeason - 4); s <= currentSeason; s++) {
            Optional<ClubCoefficient> cc = clubCoefficientRepository.findByTeamIdAndSeasonNumber(teamId, s);
            if (cc.isPresent()) total += cc.get().getPoints();
        }
        return total;
    }

    // ============================================================
    //  Summary + allocation display (used by REST endpoints)
    // ============================================================

    public Map<String, Object> getEuropeanSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();

        // LoC allocation totals
        int[] directSpots =      {3, 3, 2, 2, 1, 1, 0};
        int[] qualifyingSpots =  {1, 1, 1, 1, 2, 1, 0};
        int[] preliminarySpots = {0, 0, 0, 0, 0, 0, 2};
        int numLeagues = (int) competitionRepository.findAll().stream().filter(c -> c.getTypeId() == 1).count();
        int totalRanks = Math.min(numLeagues, 7);

        int totalDirect = 0, totalQualifying = 0, totalPreliminary = 0;
        for (int i = 0; i < totalRanks; i++) {
            totalDirect += directSpots[i];
            totalQualifying += qualifyingSpots[i];
            totalPreliminary += preliminarySpots[i];
        }
        int locTotal = totalDirect + totalQualifying + totalPreliminary;

        Map<String, Object> loc = new LinkedHashMap<>();
        loc.put("totalTeams", locTotal);
        loc.put("directToGroups", totalDirect);
        loc.put("qualifyingTeams", totalQualifying);
        loc.put("preliminaryTeams", totalPreliminary);
        loc.put("groupStageTeams", 16);
        loc.put("groups", 4);
        loc.put("teamsPerGroup", 4);
        loc.put("advancePerGroup", 2);
        summary.put("loc", loc);

        Map<String, Object> sc = new LinkedHashMap<>();
        sc.put("totalTeams", 16);
        sc.put("groups", 4);
        sc.put("teamsPerGroup", 4);
        sc.put("format", "Group winners to QF, runners-up play LoC 3rd place in Playoff, then QF → SF → Final");
        summary.put("starsCup", sc);

        // Points system
        Map<String, Object> locPoints = new LinkedHashMap<>();
        locPoints.put("Preliminary/Qualifying win", "1 pt");
        locPoints.put("Group stage win", "2 pts");
        locPoints.put("Group stage draw", "1 pt");
        locPoints.put("Quarter-Final win", "3 pts");
        locPoints.put("Semi-Final win", "4 pts");
        locPoints.put("Final win", "5 pts");
        summary.put("locPoints", locPoints);

        Map<String, Object> scPoints = new LinkedHashMap<>();
        scPoints.put("Group stage win", "1 pt");
        scPoints.put("Group stage draw", "0.5 pts");
        scPoints.put("Playoff win", "1 pt");
        scPoints.put("Quarter-Final win", "1.5 pts");
        scPoints.put("Semi-Final win", "2 pts");
        scPoints.put("Final win", "2.5 pts");
        summary.put("starsCupPoints", scPoints);

        return summary;
    }

    public void assignEuropeanAllocation(Map<String, Object> entry, int rank) {
        // LoC direct + qualifying + preliminary spots per rank (non-increasing)
        int[] directSpots =      {3, 3, 2, 2, 1, 1, 0};
        int[] qualifyingSpots =  {1, 1, 1, 1, 2, 1, 0};
        int[] preliminarySpots = {0, 0, 0, 0, 0, 0, 2};
        // Stars Cup: league spots + 1 cup reserved per nation (non-increasing)
        int[] scLeagueSpots = {1, 1, 1, 1, 0, 0, 0};
        int[] scCupSpots =    {1, 1, 1, 1, 1, 1, 1};

        if (rank >= 1 && rank <= 7) {
            int idx = rank - 1;
            int locTotal = directSpots[idx] + qualifyingSpots[idx] + preliminarySpots[idx];
            entry.put("locSpots", locTotal);

            String locEntry;
            if (directSpots[idx] > 0 && qualifyingSpots[idx] > 0) {
                locEntry = directSpots[idx] + " Group Stage + " + qualifyingSpots[idx] + " Qualifying";
            } else if (preliminarySpots[idx] > 0) {
                locEntry = preliminarySpots[idx] + " Preliminary Qualifying";
            } else if (directSpots[idx] > 0) {
                locEntry = directSpots[idx] + " Group Stage";
            } else {
                locEntry = "None";
            }
            entry.put("locEntry", locEntry);
            entry.put("starsCupSpots", scLeagueSpots[idx] + scCupSpots[idx]);
        } else {
            entry.put("locSpots", 0);
            entry.put("locEntry", "None");
            entry.put("starsCupSpots", 0);
        }
    }

    public List<Long> getLeagueIdsSortedByCoefficient() {
        int currentSeason = Integer.parseInt(currentSeason());
        List<Competition> firstLeagues = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == 1).toList();

        Map<Long, Double> leagueCoefficients = new HashMap<>();
        for (Competition league : firstLeagues) {
            long leagueId = league.getId();
            long nationId = league.getNationId();
            double countryCoefficient = 0;

            for (int s = Math.max(1, currentSeason - 4); s <= currentSeason; s++) {
                int sFinal = s;
                List<CompetitionTeamInfo> europeanEntries = competitionTeamInfoRepository
                        .findAllBySeasonNumber(sFinal).stream()
                        .filter(cti -> {
                            Competition comp = competitionRepository.findById(cti.getCompetitionId()).orElse(null);
                            return comp != null && (comp.getTypeId() == 4 || comp.getTypeId() == 5);
                        })
                        .toList();

                Set<Long> clubsFromNation = new HashSet<>();
                double seasonPoints = 0;
                for (CompetitionTeamInfo cti : europeanEntries) {
                    Team team = teamRepository.findById(cti.getTeamId()).orElse(null);
                    if (team == null) continue;
                    Competition teamLeague = competitionRepository.findById(team.getCompetitionId()).orElse(null);
                    if (teamLeague != null && teamLeague.getNationId() == nationId) {
                        clubsFromNation.add(cti.getTeamId());
                    }
                }
                for (long clubId : clubsFromNation) {
                    Optional<ClubCoefficient> cc = clubCoefficientRepository.findByTeamIdAndSeasonNumber(clubId, sFinal);
                    if (cc.isPresent()) seasonPoints += cc.get().getPoints();
                }
                double seasonRatio = clubsFromNation.isEmpty() ? 0 : seasonPoints / clubsFromNation.size();
                countryCoefficient += seasonRatio;
            }

            // Fallback: if no coefficient data yet, use avg reputation
            if (countryCoefficient == 0) {
                List<Team> teams = teamRepository.findAll().stream()
                        .filter(t -> t.getCompetitionId() == leagueId).toList();
                countryCoefficient = teams.stream().mapToInt(Team::getReputation).average().orElse(0) / 100.0;
            }

            leagueCoefficients.put(leagueId, countryCoefficient);
        }

        List<Long> sorted = new ArrayList<>(leagueCoefficients.keySet());
        sorted.sort((a, b) -> Double.compare(leagueCoefficients.get(b), leagueCoefficients.get(a)));
        return sorted;
    }
}
