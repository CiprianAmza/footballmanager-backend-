package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompetitionFormatConfig;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.TacticalScoreService.TacticVector;
import com.footballmanagergamesimulator.service.TacticalScoreService.TeamProfile;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

/**
 * Production counterpart of the {@code Team442SeasonPointsFuzzIT} harness: ACTUALLY simulates a
 * team's league campaign (real Poisson scorelines from {@link TacticalScoreService#score}) to rank
 * its 900 tactic settings by average season points for a fixed formation, and to play a custom
 * round-robin competition among chosen teams. Deterministic (seeded RNG), read-only.
 */
@Service
public class TacticSimulationService {

    private static final long BASE_SEED = 20260528L;
    private static final String DEFAULT_FORMATION = "442";
    private static final int MAX_SEASONS = 10_000;

    @Autowired private CompetitionTeamInfoRepository ctiRepo;
    @Autowired private TeamRepository teamRepo;
    @Autowired private HumanRepository humanRepo;
    @Autowired private GameStateService gameState;
    @Autowired private CompetitionFormatConfig competitionFormat;
    @Autowired private TacticController tacticController;
    @Autowired private PlayerValueService playerValueService;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;
    @Autowired private ManagerTacticService managerTacticService;
    @Autowired private TacticalScoreService tacticalScoreService;

    public record TacticPointsRow(String mentality, String tempo, String passingType,
                                  String inPossession, String timeWasting,
                                  String defensiveLine, String pressing, String width,
                                  String dribbling, String foulFrequency, String foulHardness,
                                  String tempoFragmentation, String widePlay, String transition,
                                  double avgPoints, int minPoints, int maxPoints) {}

    public record TacticPointsResult(long teamId, String teamName, String formation,
                                     int seasons, int opponentCount, List<TacticPointsRow> rows) {}

    public record StandingRow(long teamId, String teamName, int played, int wins, int draws,
                              int losses, int goalsFor, int goalsAgainst, int points) {}

    public record CompetitionResult(List<StandingRow> standings) {}

    /** Rank the 900 tactic settings for {@code teamId} by average simulated season points. */
    public TacticPointsResult simulateTacticPoints(long teamId, String formation, int seasons,
                                                   List<Long> opponentIds) {
        String form = (formation == null || formation.isBlank()) ? DEFAULT_FORMATION : formation;
        int n = Math.max(1, Math.min(MAX_SEASONS, seasons <= 0 ? 10 : seasons));
        int season = gameState.currentSeason();

        List<Long> ids = resolveOpponents(teamId, season, opponentIds);
        // Build the team's coached profile (fixed formation) + each opponent's coached profile.
        TeamProfile teamProfile = coachedTeamProfile(teamId, form);
        int oppCount = ids.size();
        TeamProfile[] oppProfiles = new TeamProfile[oppCount];
        for (int i = 0; i < oppCount; i++) oppProfiles[i] = coachedTeamProfile(ids.get(i), DEFAULT_FORMATION);

        // Representative opponent = average coached profile across the team + opponents.
        double avgAtt = teamProfile.attack(), avgDef = teamProfile.defense();
        for (TeamProfile p : oppProfiles) { avgAtt += p.attack(); avgDef += p.defense(); }
        int total = oppCount + 1;
        TeamProfile avgProfile = new TeamProfile(avgAtt / total, avgDef / total);

        // Each opponent picks its tactic ONCE (its manager's ability), fixed across all candidates.
        TacticVector[] oppTactics = new TacticVector[oppCount];
        for (int i = 0; i < oppCount; i++) {
            Coach c = coachFor(ids.get(i));
            oppTactics[i] = tacticalScoreService.vector(
                    managerTacticService.chooseTactic(oppProfiles[i], avgProfile, c.pickAbility()));
        }

        // Each swept base setting is completed with its OWN best Strat-2 + Faza-2 axes (greedy
        // per-axis), so every axis is chosen individually and folds into the simulated score.
        double teamAbility = coachFor(teamId).pickAbility();
        double teamWs = teamWideShare(teamId, form);

        List<TacticPointsRow> rows = new ArrayList<>(900);
        for (PersonalizedTactic t : managerTacticService.candidateTactics()) {
            managerTacticService.applyNewAxes(t, teamProfile, teamAbility, teamWs);
            TacticVector teamVec = tacticalScoreService.vector(t);
            int[] minMax = {Integer.MAX_VALUE, Integer.MIN_VALUE};
            long sum = 0;
            Random rng = new Random(BASE_SEED);
            for (int s = 0; s < n; s++) {
                int pts = 0;
                for (int o = 0; o < oppCount; o++) {
                    pts += teamPoints(teamProfile, teamVec, oppProfiles[o], oppTactics[o], rng); // home
                    pts += awayPoints(oppProfiles[o], oppTactics[o], teamProfile, teamVec, rng);  // away
                }
                sum += pts;
                if (pts < minMax[0]) minMax[0] = pts;
                if (pts > minMax[1]) minMax[1] = pts;
            }
            rows.add(new TacticPointsRow(t.getMentality(), t.getTempo(), t.getPassingType(),
                    t.getInPossession(), t.getTimeWasting(),
                    t.getDefensiveLine(), t.getPressing(), t.getWidth(),
                    t.getDribbling(), t.getFoulFrequency(), t.getFoulHardness(),
                    t.getTempoFragmentation(), t.getWidePlay(), t.getTransition(),
                    sum / (double) n, minMax[0], minMax[1]));
        }
        rows.sort(Comparator.comparingDouble(TacticPointsRow::avgPoints).reversed());

        // Keep only the distinct average-points values (as displayed, 2 decimals); on a tie, prefer
        // the row with the highest MINIMUM points (best worst-case). Many tactics score identically,
        // so this collapses the list to the genuinely different outcomes.
        Map<String, TacticPointsRow> byAvg = new LinkedHashMap<>();
        for (TacticPointsRow r : rows) {
            String key = String.format("%.2f", r.avgPoints());
            TacticPointsRow cur = byAvg.get(key);
            if (cur == null || r.minPoints() > cur.minPoints()) byAvg.put(key, r);
        }
        List<TacticPointsRow> distinct = new ArrayList<>(byAvg.values());
        distinct.sort(Comparator.comparingDouble(TacticPointsRow::avgPoints).reversed());

        String name = teamRepo.findNameById(teamId);
        return new TacticPointsResult(teamId, name == null ? "Team#" + teamId : name,
                form, n, oppCount, distinct);
    }

    /** Double round-robin among {@code teamIds}: each team uses its manager's formation + chosen
     *  tactic; award 3/1/0 over {@code seasons}; return standings sorted by points desc. */
    public CompetitionResult simulateCompetition(List<Long> teamIds, int seasons) {
        if (teamIds == null || teamIds.size() < 2)
            throw new IllegalArgumentException("simulateCompetition needs at least 2 teams");
        int n = teamIds.size();
        int nSeasons = Math.max(1, Math.min(MAX_SEASONS, seasons <= 0 ? 10 : seasons));

        TeamProfile[] profiles = new TeamProfile[n];
        TeamProfile avg;
        double avgAtt = 0, avgDef = 0;
        for (int i = 0; i < n; i++) {
            profiles[i] = coachedTeamProfile(teamIds.get(i), formationFor(teamIds.get(i)));
            avgAtt += profiles[i].attack();
            avgDef += profiles[i].defense();
        }
        avg = new TeamProfile(avgAtt / n, avgDef / n);

        TacticVector[] tactics = new TacticVector[n];
        for (int i = 0; i < n; i++) {
            Coach c = coachFor(teamIds.get(i));
            PersonalizedTactic chosen = managerTacticService.chooseTactic(profiles[i], avg, c.pickAbility());
            // chooseTactic sets line/press; width is a squad-shape identity (set here for parity with prod).
            chosen.setWidth(managerTacticService.widthIdentity(teamWideShare(teamIds.get(i), formationFor(teamIds.get(i)))));
            tactics[i] = tacticalScoreService.vector(chosen);
        }

        int[] played = new int[n], wins = new int[n], draws = new int[n], losses = new int[n];
        int[] gf = new int[n], ga = new int[n], pts = new int[n];

        Random rng = new Random(BASE_SEED);
        for (int s = 0; s < nSeasons; s++) {
            for (int h = 0; h < n; h++) {
                for (int a = 0; a < n; a++) {
                    if (h == a) continue; // each ordered pair once => full double round-robin
                    List<Integer> sc = tacticalScoreService.score(
                            profiles[h], tactics[h], profiles[a], tactics[a], rng);
                    int gH = sc.get(0), gA = sc.get(1);
                    played[h]++; played[a]++;
                    gf[h] += gH; ga[h] += gA;
                    gf[a] += gA; ga[a] += gH;
                    if (gH > gA) { wins[h]++; losses[a]++; pts[h] += 3; }
                    else if (gH < gA) { wins[a]++; losses[h]++; pts[a] += 3; }
                    else { draws[h]++; draws[a]++; pts[h] += 1; pts[a] += 1; }
                }
            }
        }

        List<StandingRow> standings = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long id = teamIds.get(i);
            String name = teamRepo.findNameById(id);
            standings.add(new StandingRow(id, name == null ? "Team#" + id : name,
                    played[i], wins[i], draws[i], losses[i], gf[i], ga[i], pts[i]));
        }
        standings.sort(Comparator.comparingInt(StandingRow::points)
                .thenComparingInt(r -> r.goalsFor() - r.goalsAgainst())
                .reversed());
        return new CompetitionResult(standings);
    }

    /** League team ids for a team (excluding itself) when no explicit opponents are given. */
    private List<Long> resolveOpponents(long teamId, int season, List<Long> opponentIds) {
        if (opponentIds != null && !opponentIds.isEmpty()) {
            List<Long> ids = new ArrayList<>();
            for (Long id : opponentIds) if (id != null && id != teamId) ids.add(id);
            return ids;
        }
        long compId = findLeagueForTeam(teamId, season);
        List<Long> ids = distinctSortedTeamIds(compId, season);
        ids.remove(teamId);
        return ids;
    }

    private int teamPoints(TeamProfile home, TacticVector homeT, TeamProfile away, TacticVector awayT, Random rng) {
        List<Integer> sc = tacticalScoreService.score(home, homeT, away, awayT, rng);
        int diff = sc.get(0) - sc.get(1);
        return diff > 0 ? 3 : diff == 0 ? 1 : 0; // points for the HOME (our) side
    }

    private int awayPoints(TeamProfile home, TacticVector homeT, TeamProfile away, TacticVector awayT, Random rng) {
        List<Integer> sc = tacticalScoreService.score(home, homeT, away, awayT, rng);
        int diff = sc.get(1) - sc.get(0);
        return diff > 0 ? 3 : diff == 0 ? 1 : 0; // points for the AWAY (our) side
    }

    private String formationFor(long teamId) {
        return humanRepo.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE).stream()
                .filter(m -> !m.isRetired())
                .map(Human::getTacticStyle)
                .filter(f -> f != null && !f.isBlank())
                .findFirst()
                .orElse(DEFAULT_FORMATION);
    }

    private TeamProfile coachedTeamProfile(long teamId, String formation) {
        Coach c = coachFor(teamId);
        return tacticalScoreService.coachedProfile(teamProfile(teamId, formation), c.off(), c.def());
    }

    private static final java.util.Set<String> WIDE_POSITIONS = java.util.Set.of("ML", "MR", "DL", "DR");

    /** Share of the XI's match value in wide positions (for the AI/advisor width identity). */
    private double teamWideShare(long teamId, String formation) {
        List<TacticController.StarterSlot> slots =
                tacticController.getBestElevenWithSlots(String.valueOf(teamId), formation);
        List<Long> ids = slots.stream().map(s -> s.player().getId()).toList();
        Map<Long, PlayerSkills> skills = new HashMap<>();
        for (PlayerSkills s : playerSkillsRepository.findAllByPlayerIdIn(ids)) skills.put(s.getPlayerId(), s);
        double total = 0, wide = 0;
        for (TacticController.StarterSlot slot : slots) {
            PlayerView pv = slot.player();
            String used = slot.usedPosition();
            PlayerSkills sk = skills.get(pv.getId());
            double value = sk != null
                    ? playerValueService.evaluatePlayer(sk, pv.getPosition(), used, pv.getMorale(), pv.getFitness())
                    : playerValueService.evaluatePlayer(pv.getRating(), pv.getPosition(), used, pv.getMorale(), pv.getFitness());
            total += value;
            if (WIDE_POSITIONS.contains(used)) wide += value;
        }
        return total <= 0 ? 0 : wide / total;
    }

    private TeamProfile teamProfile(long teamId, String formation) {
        List<TacticController.StarterSlot> slots =
                tacticController.getBestElevenWithSlots(String.valueOf(teamId), formation);
        List<Long> ids = slots.stream().map(s -> s.player().getId()).toList();
        Map<Long, PlayerSkills> skills = new HashMap<>();
        for (PlayerSkills s : playerSkillsRepository.findAllByPlayerIdIn(ids)) skills.put(s.getPlayerId(), s);
        List<TacticalScoreService.StarterValue> starters = new ArrayList<>(slots.size());
        for (TacticController.StarterSlot slot : slots) {
            PlayerView pv = slot.player();
            String natural = pv.getPosition(), used = slot.usedPosition();
            PlayerSkills sk = skills.get(pv.getId());
            double value = sk != null
                    ? playerValueService.evaluatePlayer(sk, natural, used, pv.getMorale(), pv.getFitness())
                    : playerValueService.evaluatePlayer(pv.getRating(), natural, used, pv.getMorale(), pv.getFitness());
            starters.add(new TacticalScoreService.StarterValue(used, value));
        }
        return tacticalScoreService.profile(starters);
    }

    private record Coach(double off, double def) {
        double pickAbility() { return (off + def) / 2.0; }
    }

    private Coach coachFor(long teamId) {
        return humanRepo.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE).stream()
                .filter(m -> !m.isRetired())
                .findFirst()
                .map(m -> new Coach(m.getOffensiveAbility(), m.getDefensiveAbility()))
                .orElse(new Coach(50.0, 50.0));
    }

    private long findLeagueForTeam(long teamId, int season) {
        for (long compId : gameState.getLeagueCompetitionIdsCached().stream().sorted().toList())
            for (CompetitionTeamInfo cti : ctiRepo.findAllByCompetitionIdAndSeasonNumber(compId, season))
                if (cti.getTeamId() == teamId) return compId;
        throw new IllegalArgumentException("Team " + teamId + " not in any top-tier league for season " + season);
    }

    private List<Long> distinctSortedTeamIds(long compId, int season) {
        TreeSet<Long> ids = new TreeSet<>();
        for (CompetitionTeamInfo cti : ctiRepo.findAllByCompetitionIdAndSeasonNumber(compId, season))
            if (cti.getTeamId() > 0) ids.add(cti.getTeamId());
        return new ArrayList<>(ids);
    }
}
