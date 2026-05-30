package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.algorithms.RoundRobin;
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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
 * European competition lifecycle — LoC + Stars Cup group draws, group-stage
 * fixture generation, post-group qualification (LoC top-2 → QF, 3rd → SC
 * playoff; SC winner → QF, runner-up → playoff), seeded knockout draws, and
 * the LoC-losers-drop-to-SC handoff.
 *
 * <p>Originally lifted out of {@link CompetitionController} (sesiunile 1-5);
 * in sesiunea 6 (§6.1) the coefficient/prize-money math was split out to
 * {@link EuropeanCoefficientService} and the read-only display surface to
 * {@link EuropeanDisplayService}. This class is now strictly the in-season
 * European lifecycle — anything that creates or mutates CTI/CTIM/TCD entries
 * during European matchdays.
 *
 * <p>Dependency: this service calls {@link EuropeanCoefficientService} for
 * seeded-draw ranking ({@code getClubCoefficientRolling}) and end-of-season
 * league allocation ({@code getLeagueIdsSortedByCoefficient}). The reverse
 * direction does not exist.
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
    @Autowired private EuropeanCoefficientService coefficientService;
    @Autowired private com.footballmanagergamesimulator.config.CompetitionFormatConfig competitionFormat;
    @Autowired private com.footballmanagergamesimulator.service.tournament.TournamentEngine tournamentEngine;

    /** Resolve the {@link com.footballmanagergamesimulator.config.CompetitionFormat}
     *  for a competition by looking up its type id. */
    private com.footballmanagergamesimulator.config.CompetitionFormat formatOf(long competitionId) {
        int typeId = competitionRepository.findById(competitionId)
                .map(Competition::getTypeId).orElse(0L).intValue();
        return competitionFormat.get(typeId);
    }

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
        if (starsCupIds.contains(competitionId)) {
            // Everything from the playoff onward (playoff, QF, SF, Final) is knockout.
            return roundId >= formatOf(competitionId).playoffRound();
        }

        Set<Long> locIds = competitionIdsByType(4);
        if (locIds.contains(competitionId)) {
            com.footballmanagergamesimulator.config.CompetitionFormat fmt = formatOf(competitionId);
            // Preliminary/qualifying rounds and the main knockout are knockout;
            // the group stage in between is not.
            return fmt.isPreliminaryRound(roundId) || roundId >= fmt.knockoutStartRound();
        }

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

        com.footballmanagergamesimulator.config.CompetitionFormat fmt = formatOf(competitionId);
        int groupSize = fmt.groupSize();
        int maxGroups = fmt.groupCount();
        if (entries.size() < groupSize) return;

        entries.sort((a, b) -> {
            double coeffA = coefficientService.getClubCoefficientRolling(a.getTeamId(), coeffSeason);
            double coeffB = coefficientService.getClubCoefficientRolling(b.getTeamId(), coeffSeason);
            if (coeffA != coeffB) return Double.compare(coeffB, coeffA);
            Team teamA = teamRepository.findById(a.getTeamId()).orElse(new Team());
            Team teamB = teamRepository.findById(b.getTeamId()).orElse(new Team());
            return Integer.compare(teamB.getReputation(), teamA.getReputation());
        });

        // Preserve the CONFIGURED group count (the format's shape). If there are more
        // entrants than slots, trim the lowest-seeded; if fewer, the surplus slots stay
        // empty — real teams are still spread one-per-pot across all groups, so short
        // groups simply have fewer members (no placeholder rows are persisted).
        int numGroups = maxGroups;
        int totalSlots = numGroups * groupSize;
        if (entries.size() > totalSlots) {
            System.out.println("=== drawEuropeanGroups: trimming " + (entries.size() - totalSlots)
                    + " lowest-seeded teams from " + entries.size() + " to fit " + numGroups + " groups of " + groupSize);
            entries = new ArrayList<>(entries.subList(0, totalSlots));
        }
        // Pot-seed via the shared TournamentEngine. The field is already ranked by
        // club coefficient; the engine splits into pots, shuffles, places one team
        // per pot per group, and avoids same-nation clashes (best effort). Indices at
        // or beyond the real entrant count are "empty slots" — they get a group
        // position from the engine but are not persisted.
        final List<CompetitionTeamInfo> seededEntries = entries;
        final int realCount = seededEntries.size();
        List<Integer> seededIdx = new ArrayList<>(totalSlots);
        for (int i = 0; i < totalSlots; i++) seededIdx.add(i);

        List<List<Integer>> groups = tournamentEngine.potSeededGroups(
                seededIdx, numGroups, groupSize, new java.util.Random(),
                i -> i < realCount ? getTeamNationId(seededEntries.get(i).getTeamId()) : 0L);

        for (int g = 0; g < groups.size(); g++) {
            for (int idx : groups.get(g)) {
                if (idx >= realCount) continue; // empty slot — nothing to persist
                CompetitionTeamInfo candidate = seededEntries.get(idx);
                candidate.setGroupNumber(g + 1);
                candidate.setPotNumber(idx / numGroups + 1); // pot = seed-rank band
                competitionTeamInfoRepository.save(candidate);
            }
        }

        for (int g = 0; g < groups.size(); g++) {
            System.out.println("  Group " + (g + 1) + ": " + groups.get(g).stream()
                    .map(idx -> {
                        if (idx >= realCount) return "(empty)";
                        CompetitionTeamInfo cti = seededEntries.get(idx);
                        return teamRepository.findById(cti.getTeamId()).map(Team::getName).orElse("?")
                                + "(P" + cti.getPotNumber() + ")";
                    })
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
            double coeffA = coefficientService.getClubCoefficientRolling(a, coeffSeason);
            double coeffB = coefficientService.getClubCoefficientRolling(b, coeffSeason);
            if (coeffA != coeffB) return Double.compare(coeffB, coeffA);
            Team teamA = teamRepository.findById(a).orElse(new Team());
            Team teamB = teamRepository.findById(b).orElse(new Team());
            return Integer.compare(teamB.getReputation(), teamA.getReputation());
        });

        int half = participants.size() / 2;
        List<Long> seeded = new ArrayList<>(participants.subList(0, half));
        List<Long> unseeded = new ArrayList<>(participants.subList(half, participants.size()));

        List<long[]> pairings = tournamentEngine.pairSeededVsUnseeded(
                seeded, unseeded, new java.util.Random(), this::getTeamNationId);

        System.out.println("=== drawEuropeanKnockoutSeeded: comp=" + competitionId + " round=" + roundId);
        for (int i = 0; i < pairings.size(); i++) {
            long homeId = pairings.get(i)[0];
            long awayId = pairings.get(i)[1];
            saveKnockoutPairing(competitionId, roundId, homeId, awayId, i);
            String homeName = teamRepository.findById(homeId).map(Team::getName).orElse("?");
            String awayName = teamRepository.findById(awayId).map(Team::getName).orElse("?");
            System.out.println("  " + homeName + " (seeded) vs " + awayName);
        }
    }

    /**
     * Draw one preliminary round that trims the field toward the group stage,
     * mirroring {@code TournamentEngine.trimToSize}: the strongest seeds (by club
     * coefficient) get byes and advance untouched to the next round; the weakest
     * {@code 2*eliminate} are paired seeded-vs-unseeded and play. Winners propagate
     * to the next round via the normal knockout progression in simulateRound.
     *
     * @param slots the group-stage size this competition trims down to
     */
    public void drawEuropeanPreliminarySeeded(long competitionId, long roundId, int slots) {
        long season = Long.parseLong(currentSeason());
        List<Long> participants = competitionTeamInfoRepository.findAllBySeasonNumber(season).stream()
                .filter(cti -> cti.getCompetitionId() == competitionId && cti.getRound() == roundId)
                .map(CompetitionTeamInfo::getTeamId)
                .distinct()
                .collect(Collectors.toList());

        int f = participants.size();
        if (f <= slots || f < 2) return; // nothing to trim

        int eliminate = Math.min(f - slots, f / 2);
        int coeffSeason = Integer.parseInt(currentSeason()) - 1;
        participants.sort((a, b) -> {
            double coeffA = coefficientService.getClubCoefficientRolling(a, coeffSeason);
            double coeffB = coefficientService.getClubCoefficientRolling(b, coeffSeason);
            if (coeffA != coeffB) return Double.compare(coeffB, coeffA);
            Team teamA = teamRepository.findById(a).orElse(new Team());
            Team teamB = teamRepository.findById(b).orElse(new Team());
            return Integer.compare(teamB.getReputation(), teamA.getReputation());
        });

        int byeCount = f - 2 * eliminate;
        List<Long> byes = new ArrayList<>(participants.subList(0, byeCount));
        List<Long> playing = new ArrayList<>(participants.subList(byeCount, f));

        // Byes auto-advance to the next round.
        long nextRound = roundId + 1;
        for (long teamId : byes) {
            CompetitionTeamInfo cti = new CompetitionTeamInfo();
            cti.setTeamId(teamId);
            cti.setCompetitionId(competitionId);
            cti.setSeasonNumber(season);
            cti.setRound(nextRound);
            competitionTeamInfoRepository.save(cti);
        }

        // The weakest 2*eliminate teams play, seeded vs unseeded.
        int half = playing.size() / 2;
        List<Long> seeded = new ArrayList<>(playing.subList(0, half));
        List<Long> unseeded = new ArrayList<>(playing.subList(half, playing.size()));
        List<long[]> pairings = tournamentEngine.pairSeededVsUnseeded(
                seeded, unseeded, new java.util.Random(), this::getTeamNationId);
        for (int i = 0; i < pairings.size(); i++) {
            saveKnockoutPairing(competitionId, roundId, pairings.get(i)[0], pairings.get(i)[1], i);
        }
        System.out.println("=== drawEuropeanPreliminarySeeded: comp=" + competitionId + " round=" + roundId
                + " field=" + f + " byes=" + byeCount + " ties=" + eliminate);
    }

    /**
     * Persist a knockout pairing as either a single match or two legs
     * (home-and-away), depending on the {@link com.footballmanagergamesimulator.config.CompetitionFormat}
     * two-leg rounds for this competition's type + round. Two-leg ties share a {@code tieId};
     * leg 1 has {@code homeId} at home, leg 2 swaps venues. {@code pairingIndex}
     * makes the tieId unique within the round's draw.
     */
    public void saveKnockoutPairing(long competitionId, long roundId, long homeId, long awayId, int pairingIndex) {
        int typeId = competitionRepository.findById(competitionId).map(Competition::getTypeId).orElse(0L).intValue();
        String season = currentSeason();
        boolean twoLeg = competitionFormat.isTwoLeg(typeId, roundId);

        if (!twoLeg) {
            CompetitionTeamInfoMatch match = new CompetitionTeamInfoMatch();
            match.setCompetitionId(competitionId);
            match.setRound(roundId);
            match.setTeam1Id(homeId);
            match.setTeam2Id(awayId);
            match.setSeasonNumber(season);
            competitionTeamInfoMatchRepository.save(match);
            return;
        }

        long tieId = (competitionId * 100_000L) + (roundId * 1_000L) + pairingIndex + 1;

        CompetitionTeamInfoMatch leg1 = new CompetitionTeamInfoMatch();
        leg1.setCompetitionId(competitionId);
        leg1.setRound(roundId);
        leg1.setTeam1Id(homeId);
        leg1.setTeam2Id(awayId);
        leg1.setSeasonNumber(season);
        leg1.setLegNumber(1);
        leg1.setTieId(tieId);
        competitionTeamInfoMatchRepository.save(leg1);

        CompetitionTeamInfoMatch leg2 = new CompetitionTeamInfoMatch();
        leg2.setCompetitionId(competitionId);
        leg2.setRound(roundId);
        leg2.setTeam1Id(awayId); // venues swap for the second leg
        leg2.setTeam2Id(homeId);
        leg2.setSeasonNumber(season);
        leg2.setLegNumber(2);
        leg2.setTieId(tieId);
        competitionTeamInfoMatchRepository.save(leg2);
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

        // Balance the two pots (unequal LoC-3rd vs SC-runner-up counts) before pairing.
        while (seeded.size() > unseeded.size() && seeded.size() > 0) {
            unseeded.add(seeded.remove(seeded.size() - 1));
        }
        while (unseeded.size() > seeded.size() && unseeded.size() > 0) {
            seeded.add(unseeded.remove(unseeded.size() - 1));
        }

        List<long[]> pairings = tournamentEngine.pairSeededVsUnseeded(
                seeded, unseeded, new java.util.Random(), this::getTeamNationId);

        System.out.println("=== drawStarsCupPlayoffSeeded: LoC 3rd (seeded) vs SC runners-up (unseeded)");
        for (int i = 0; i < pairings.size(); i++) {
            long homeId = pairings.get(i)[0];
            long awayId = pairings.get(i)[1];
            saveKnockoutPairing(starsCupCompetitionId, roundId, homeId, awayId, i);
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
        int roundOffset = formatOf(competitionId).groupFixtureRoundOffset(); // Stars Cup: 0, LoC: 1

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

    /**
     * Assign each entrant the preliminary round at which it FIRST plays, mirroring
     * {@code TournamentEngine.trimToSize}'s byes: the weakest teams play round 0,
     * stronger ones enter later, and the strongest {@code slots} skip the prelims
     * entirely and enter at the group-draw round ({@code P}, where {@code P} is the
     * number of trim rounds). Deterministic under the "stronger seed advances"
     * assumption used for the draw (actual winners come from the match engine).
     *
     * @param seededBestFirst entrants ordered strongest → weakest (by coefficient)
     * @param slots           group-stage size (groupCount * groupSize)
     * @return team id → 0-based starting round (P = group-draw round)
     */
    static Map<Long, Integer> assignEntrantsToPrelimRounds(List<Long> seededBestFirst, int slots) {
        Map<Long, Integer> startRound = new LinkedHashMap<>();
        // Work weakest-first so the bottom of the field plays the earliest rounds.
        List<Long> current = new ArrayList<>(seededBestFirst);
        java.util.Collections.reverse(current);

        int round = 0;
        while (current.size() > slots) {
            int f = current.size();
            int eliminate = Math.min(f - slots, f / 2);
            int playing = 2 * eliminate;
            for (int i = 0; i < playing; i++) {
                startRound.putIfAbsent(current.get(i), round);
            }
            // Under seed-advance the weakest `eliminate` lose and drop out.
            current = new ArrayList<>(current.subList(eliminate, f));
            round++;
        }
        // Whatever remains never had to play a prelim — it enters at the group draw.
        for (Long teamId : current) {
            startRound.putIfAbsent(teamId, round);
        }
        return startRound;
    }

    // ============================================================
    //  Post-group qualification
    // ============================================================

    /** LoC group stage → QF (top 2) + Stars Cup playoff (3rd). */
    public void qualifyFromGroupStage(long locCompetitionId) {
        com.footballmanagergamesimulator.config.CompetitionFormat fmt = formatOf(locCompetitionId);
        int qualifyCount = fmt.qualifyPerGroupToKnockout();
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

            for (int i = 0; i < Math.min(qualifyCount, standings.size()); i++) {
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
                cti.setRound(fmt.qualifyTargetRound());
                competitionTeamInfoRepository.save(cti);
                String teamName = teamRepository.findById(teamId).map(t -> t.getName()).orElse("?");
                System.out.println("  -> Qualified for LoC QF: " + teamName + " (id=" + teamId + ") from group " + group.getKey());
            }

            if (standings.size() >= qualifyCount + 1 && fmt.thirdPlaceDropTypeId() > 0) {
                long thirdPlaceTeamId = standings.get(qualifyCount).getTeamId();
                final int dropTypeId = fmt.thirdPlaceDropTypeId();
                long starsCupCompetitionId = competitionRepository.findAll().stream()
                        .filter(c -> c.getTypeId() == dropTypeId).findFirst()
                        .map(Competition::getId).orElse(-1L);
                if (starsCupCompetitionId > 0) {
                    CompetitionTeamInfo scCti = new CompetitionTeamInfo();
                    scCti.setTeamId(thirdPlaceTeamId);
                    scCti.setCompetitionId(starsCupCompetitionId);
                    scCti.setSeasonNumber(currentSeason);
                    scCti.setRound(fmt.thirdPlaceDropRound());
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
        com.footballmanagergamesimulator.config.CompetitionFormat fmt = formatOf(locCompetitionId);
        long currentSeason = Long.parseLong(currentSeason());
        final int dropTypeId = fmt.losersDropTypeId();
        if (dropTypeId <= 0) return;
        long starsCupCompetitionId = competitionRepository.findAll().stream()
                .filter(c -> c.getTypeId() == dropTypeId).findFirst()
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

        // Every eliminated team drops to the Stars Cup group stage. No hard slot cap
        // here — drawEuropeanGroups already seeds the SC field and trims the lowest
        // coefficients fairly if there are more entrants than group slots, so capping
        // by iteration order here would silently (and arbitrarily) eliminate eligible
        // droppers instead of letting the seeded draw decide.
        for (long teamId : participants) {
            if (!winners.contains(teamId)) {
                boolean alreadyInStarsCup = competitionTeamInfoRepository.findAllBySeasonNumber(currentSeason).stream()
                        .anyMatch(cti -> cti.getTeamId() == teamId && cti.getCompetitionId() == starsCupCompetitionId);
                if (!alreadyInStarsCup) {
                    CompetitionTeamInfo cti = new CompetitionTeamInfo();
                    cti.setTeamId(teamId);
                    cti.setCompetitionId(starsCupCompetitionId);
                    cti.setSeasonNumber(currentSeason);
                    cti.setRound(fmt.losersDropRound());
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
        com.footballmanagergamesimulator.config.CompetitionFormat fmt = formatOf(starsCupCompetitionId);
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

            // Config-driven routing: the top `directQualify` finishers go straight to
            // the knockout (QF), the next `playoffQualify` go to the playoff, the rest
            // are eliminated.
            int directQualify = fmt.qualifyPerGroupToKnockout();
            int playoffQualify = fmt.playoffQualifyPerGroup();
            for (int pos = 0; pos < standings.size(); pos++) {
                long teamId = standings.get(pos).getTeamId();
                long targetRound;
                String label;
                if (pos < directQualify) {
                    targetRound = fmt.qualifyTargetRound();
                    label = "group qualifier to QF";
                } else if (pos < directQualify + playoffQualify) {
                    targetRound = fmt.playoffRound();
                    label = "group team to playoff";
                } else {
                    break; // remaining positions are eliminated
                }
                CompetitionTeamInfo cti = new CompetitionTeamInfo();
                cti.setTeamId(teamId);
                cti.setCompetitionId(starsCupCompetitionId);
                cti.setSeasonNumber(currentSeason);
                cti.setRound(targetRound);
                competitionTeamInfoRepository.save(cti);
                String teamName = teamRepository.findById(teamId).map(t -> t.getName()).orElse("?");
                System.out.println("  -> Stars Cup " + label + ": " + teamName);
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
        List<Long> sortedLeagueIds = coefficientService.getLeagueIdsSortedByCoefficient();

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

        // LoC entry (configurable): the competition is sized for a fixed number of
        // entrants (CompetitionFormat.totalTeams). Slots are allocated to leagues by
        // coefficient rank (layered halving), each league sends its top-K teams, and
        // the seeded field is assigned to preliminary rounds — the strongest enter at
        // the group draw, the weakest play the earliest prelim (see EuropeanFormatPlan).
        com.footballmanagergamesimulator.config.CompetitionFormat locFmt = competitionFormat.get(4);
        com.footballmanagergamesimulator.config.EuropeanFormatPlan locPlan = locFmt.europeanPlan();
        if (locPlan != null) {
            Map<Long, Integer> allocation =
                    coefficientService.allocateTeamsPerLeague(locPlan.totalTeams(), java.util.Map.of());
            List<Long> seededEntrants = new ArrayList<>();
            for (Map.Entry<Long, Integer> alloc : allocation.entrySet()) {
                List<TeamCompetitionDetail> standings = standingsByLeague.get(alloc.getKey());
                if (standings == null) continue;
                for (int i = 0; i < alloc.getValue() && i < standings.size(); i++) {
                    seededEntrants.add(standings.get(i).getTeamId());
                }
            }
            Map<Long, Integer> startRounds = assignEntrantsToPrelimRounds(seededEntrants, locPlan.slots());
            for (Map.Entry<Long, Integer> entry : startRounds.entrySet()) {
                saveLocQualifier(entry.getKey(), locCompetitionId, nextSeason, entry.getValue());
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

        // Stars Cup league spots — ranks 1-4 each contribute one team, ranks 5-7 none
        // (they get the reserved cup spot only). Because LoC now takes a variable,
        // coefficient-based number of top teams per league, a fixed league position
        // (old "5th/4th place") would collide with LoC; instead we take the BEST-placed
        // league team that LoC hasn't already qualified.
        int[] starsCupLeagueSpots = {1, 1, 1, 1, 0, 0, 0};
        for (int rank = 1; rank <= Math.min(numLeagues, 7); rank++) {
            List<TeamCompetitionDetail> standings = standingsByLeague.get(sortedLeagueIds.get(rank - 1));
            if (standings == null) continue;
            int spots = starsCupLeagueSpots[rank - 1];
            int given = 0;
            for (TeamCompetitionDetail d : standings) {
                if (given >= spots) break;
                long teamId = d.getTeamId();
                if (alreadyQualified.contains(teamId)) continue;
                saveStarsCupQualifier(teamId, starsCupCompetitionId, nextSeason);
                alreadyQualified.add(teamId);
                given++;
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

}
