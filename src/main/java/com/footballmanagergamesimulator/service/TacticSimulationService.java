package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.CompartmentEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluation;
import com.footballmanagergamesimulator.compartment.match.CanonicalMatchEvaluationAdapter;
import com.footballmanagergamesimulator.compartment.match.MatchVenue;
import com.footballmanagergamesimulator.compartment.adapter.CanonicalTeamEvaluation;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeInputFactory;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalRuntimeTeamInput;
import com.footballmanagergamesimulator.compartment.runtime.CanonicalScoreSampler;
import com.footballmanagergamesimulator.compartment.runtime.RuntimeLineupSlot;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.frontend.PersonalizedTacticView;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
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
 * Tactics Advisor and custom competition simulator.
 *
 * <p>The advisor paths use the authoritative Compartment Engine pipeline. The legacy tactical
 * service remains only for the separate historical custom-competition endpoint and for mirroring
 * the AI manager's current tactic selection until that selector is migrated independently.
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
    @Autowired private TacticController tacticController;
    @Autowired private PlayerValueService playerValueService;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;
    @Autowired private PersonalizedTacticRepository personalizedTacticRepository;
    @Autowired private ManagerTacticService managerTacticService;
    @Autowired private TacticalScoreService tacticalScoreService;
    @Autowired private CanonicalRuntimeInputFactory canonicalRuntimeInputFactory;
    @Autowired private CanonicalScoreSampler canonicalScoreSampler;
    @Autowired private CompartmentEngineConfig compartmentEngineConfig;
    @Autowired private MatchEngineConfig matchEngineConfig;

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

    /** Fully loaded canonical inputs; player capabilities are read once, then tactic contexts vary in memory. */
    private record CanonicalAdvisorMatchup(String formation, CanonicalRuntimeTeamInput team,
                                           CanonicalRuntimeTeamInput[] opponents, int oppCount) {}

    private CanonicalAdvisorMatchup buildCanonicalAdvisorMatchup(long teamId, String formation,
                                                                  List<Long> opponentIds) {
        String form = (formation == null || formation.isBlank()) ? DEFAULT_FORMATION : formation;
        List<Long> ids = resolveOpponents(teamId, gameState.currentSeason(), opponentIds);
        if (ids.isEmpty()) throw new IllegalArgumentException("Tactics Advisor needs at least one opponent");
        CanonicalRuntimeTeamInput team = canonicalTeamInput(teamId, form, neutralCanonicalTactic());

        // Runtime currently derives an unsaved AI manager's axes through ManagerTacticService. Keep
        // that opponent identity for parity, but all matchup evaluation below is canonical.
        TeamProfile legacyTeam = coachedTeamProfile(teamId, form);
        TeamProfile[] legacyOpponents = new TeamProfile[ids.size()];
        double averageAttack = legacyTeam.attack();
        double averageDefense = legacyTeam.defense();
        for (int i = 0; i < ids.size(); i++) {
            legacyOpponents[i] = coachedTeamProfile(ids.get(i), advisorFormationFor(ids.get(i)));
            averageAttack += legacyOpponents[i].attack();
            averageDefense += legacyOpponents[i].defense();
        }
        TeamProfile average = new TeamProfile(averageAttack / (ids.size() + 1), averageDefense / (ids.size() + 1));

        CanonicalRuntimeTeamInput[] opponents = new CanonicalRuntimeTeamInput[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            long opponentId = ids.get(i);
            TeamProfile opponentProfile = legacyOpponents[i];
            String opponentFormation = advisorFormationFor(opponentId);
            PersonalizedTactic tactic = personalizedTacticRepository.findPersonalizedTacticByTeamId(opponentId)
                    .map(TacticSimulationService::copyTacticAxes)
                    .orElseGet(() -> managerTacticService.chooseTactic(
                            opponentProfile, average, coachFor(opponentId).pickAbility()));
            if (tactic.getWidth() == null || tactic.getWidth().isBlank()) {
                tactic.setWidth(managerTacticService.widthIdentity(
                        teamWideShare(opponentId, opponentFormation)));
            }
            opponents[i] = canonicalTeamInput(opponentId, opponentFormation, tactic);
        }
        return new CanonicalAdvisorMatchup(form, team, opponents, opponents.length);
    }

    /** Rank all canonical tactic-axis combinations by deterministic Compartment score sampling. */
    public TacticPointsResult simulateTacticPoints(long teamId, String formation, int seasons,
                                                   List<Long> opponentIds) {
        int n = Math.max(1, Math.min(MAX_SEASONS, seasons <= 0 ? 10 : seasons));
        CanonicalAdvisorMatchup m = buildCanonicalAdvisorMatchup(teamId, formation, opponentIds);
        CanonicalMatchEvaluationAdapter adapter = canonicalAdapter();
        CanonicalTeamEvaluation[] opponentEvaluations = canonicalOpponentEvaluations(adapter, m);

        List<TacticPointsRow> rows = new ArrayList<>();
        for (PersonalizedTactic t : canonicalAdvisorTactics()) {
            CanonicalRuntimeTeamInput candidate = canonicalRuntimeInputFactory.withTactic(m.team(), t);
            CanonicalTeamEvaluation candidateEvaluation = adapter.evaluateTeam(candidate);
            CanonicalMatchEvaluation[] homeLegs = new CanonicalMatchEvaluation[m.oppCount()];
            CanonicalMatchEvaluation[] awayLegs = new CanonicalMatchEvaluation[m.oppCount()];
            for (int o = 0; o < m.oppCount(); o++) {
                homeLegs[o] = adapter.evaluate(candidateEvaluation, opponentEvaluations[o], MatchVenue.HOME);
                awayLegs[o] = adapter.evaluate(opponentEvaluations[o], candidateEvaluation, MatchVenue.HOME);
            }
            int[] minMax = {Integer.MAX_VALUE, Integer.MIN_VALUE};
            long sum = 0;
            for (int s = 0; s < n; s++) {
                int pts = 0;
                for (int o = 0; o < m.oppCount(); o++) {
                    long pairedSeed = BASE_SEED + (long) s * Math.max(1, m.oppCount()) * 2L + o * 2L;
                    CanonicalScoreSampler.GoalSample home = canonicalScoreSampler.sample(homeLegs[o], pairedSeed);
                    CanonicalScoreSampler.GoalSample away = canonicalScoreSampler.sample(awayLegs[o], pairedSeed + 1L);
                    pts += points(home.homeGoals(), home.awayGoals());
                    pts += points(away.awayGoals(), away.homeGoals());
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
                m.formation(), n, m.oppCount(), distinct);
    }

    public record AnalyticalRow(String mentality, String tempo, String passingType, String inPossession,
                                String timeWasting, String defensiveLine, String pressing, String width,
                                String dribbling, String foulFrequency, String foulHardness,
                                String tempoFragmentation, String widePlay, String transition,
                                double expectedPoints, double expectedGoalDifference) {}

    public record AnalyticalResult(long teamId, String teamName, String formation, int opponentCount,
                                   List<AnalyticalRow> rows) {}

    /**
     * Analytical advisor ranking against the team's real league opponents through the authoritative
     * canonical evaluation (player attributes, position/role familiarity, morale, fitness, tactical
     * context, compartments and goal PMFs). Same opponents and tactics as the simulated tab.
     * Distinct expected-points values only (tie → higher goal difference); capped at {@code topN}.
     */
    public AnalyticalResult analyticalTacticPoints(long teamId, String formation, int topN,
                                                   List<Long> opponentIds) {
        CanonicalAdvisorMatchup m = buildCanonicalAdvisorMatchup(teamId, formation, opponentIds);
        CanonicalMatchEvaluationAdapter adapter = canonicalAdapter();
        CanonicalTeamEvaluation[] opponentEvaluations = canonicalOpponentEvaluations(adapter, m);
        double games = Math.max(1, 2 * m.oppCount());

        List<AnalyticalRow> rows = new ArrayList<>();
        for (PersonalizedTactic t : canonicalAdvisorTactics()) {
            CanonicalRuntimeTeamInput candidate = canonicalRuntimeInputFactory.withTactic(m.team(), t);
            CanonicalTeamEvaluation candidateEvaluation = adapter.evaluateTeam(candidate);
            double pts = 0, xgd = 0;
            for (int o = 0; o < m.oppCount(); o++) {
                CanonicalMatchEvaluation home = adapter.evaluate(candidateEvaluation, opponentEvaluations[o], MatchVenue.HOME);
                CanonicalMatchEvaluation away = adapter.evaluate(opponentEvaluations[o], candidateEvaluation, MatchVenue.HOME);
                pts += expectedHomePoints(home) + expectedAwayPoints(away);
                xgd += home.probability().homeXg() - home.probability().awayXg();
                xgd += away.probability().awayXg() - away.probability().homeXg();
            }
            rows.add(new AnalyticalRow(t.getMentality(), t.getTempo(), t.getPassingType(), t.getInPossession(),
                    t.getTimeWasting(), t.getDefensiveLine(), t.getPressing(), t.getWidth(), t.getDribbling(),
                    t.getFoulFrequency(), t.getFoulHardness(), t.getTempoFragmentation(), t.getWidePlay(),
                    t.getTransition(), pts, xgd / games));
        }
        rows.sort(Comparator.comparingDouble(AnalyticalRow::expectedPoints).reversed());

        // Distinct expected-points values (2dp); on a tie, prefer the higher goal difference.
        Map<String, AnalyticalRow> byPts = new LinkedHashMap<>();
        for (AnalyticalRow r : rows) {
            String key = String.format("%.2f", r.expectedPoints());
            AnalyticalRow cur = byPts.get(key);
            if (cur == null || r.expectedGoalDifference() > cur.expectedGoalDifference()) byPts.put(key, r);
        }
        List<AnalyticalRow> distinct = new ArrayList<>(byPts.values());
        distinct.sort(Comparator.comparingDouble(AnalyticalRow::expectedPoints).reversed());
        if (topN > 0 && distinct.size() > topN) distinct = new ArrayList<>(distinct.subList(0, topN));

        String name = teamRepo.findNameById(teamId);
        return new AnalyticalResult(teamId, name == null ? "Team#" + teamId : name,
                m.formation(), m.oppCount(), distinct);
    }

    private CanonicalMatchEvaluationAdapter canonicalAdapter() {
        return new CanonicalMatchEvaluationAdapter(compartmentEngineConfig, matchEngineConfig);
    }

    private static CanonicalTeamEvaluation[] canonicalOpponentEvaluations(
            CanonicalMatchEvaluationAdapter adapter, CanonicalAdvisorMatchup matchup) {
        CanonicalTeamEvaluation[] result = new CanonicalTeamEvaluation[matchup.oppCount()];
        for (int i = 0; i < result.length; i++) result[i] = adapter.evaluateTeam(matchup.opponents()[i]);
        return result;
    }

    private static double expectedHomePoints(CanonicalMatchEvaluation evaluation) {
        return 3.0 * evaluation.outcome().homeWin() + evaluation.outcome().draw();
    }

    private static double expectedAwayPoints(CanonicalMatchEvaluation evaluation) {
        return 3.0 * evaluation.outcome().awayWin() + evaluation.outcome().draw();
    }

    private static int points(int goalsFor, int goalsAgainst) {
        return goalsFor > goalsAgainst ? 3 : goalsFor == goalsAgainst ? 1 : 0;
    }

    /** Exactly the six team axes consumed by CanonicalRuntimeInputFactory: 2,025 combinations. */
    private static List<PersonalizedTactic> canonicalAdvisorTactics() {
        List<PersonalizedTactic> result = new ArrayList<>(2_025);
        for (String mentality : MatchEngineConfig.TacticalModel.MENTALITY_OPTIONS)
            for (String tempo : MatchEngineConfig.TacticalModel.TEMPO_OPTIONS)
                for (String passing : MatchEngineConfig.TacticalModel.PASSING_OPTIONS)
                    for (String line : MatchEngineConfig.TacticalModel.DEFENSIVE_LINE_OPTIONS)
                        for (String pressing : MatchEngineConfig.TacticalModel.PRESSING_OPTIONS)
                            for (String width : MatchEngineConfig.TacticalModel.WIDTH_OPTIONS) {
                                PersonalizedTactic tactic = neutralCanonicalTactic();
                                tactic.setMentality(mentality);
                                tactic.setTempo(tempo);
                                tactic.setPassingType(passing);
                                tactic.setDefensiveLine(line);
                                tactic.setPressing(pressing);
                                tactic.setWidth(width);
                                result.add(tactic);
                            }
        return result;
    }

    private static PersonalizedTactic neutralCanonicalTactic() {
        PersonalizedTactic tactic = new PersonalizedTactic();
        tactic.setMentality("Balanced");
        tactic.setTempo("Standard");
        tactic.setPassingType("Normal");
        tactic.setDefensiveLine("Standard");
        tactic.setPressing("Standard");
        tactic.setWidth("Balanced");
        // Kept for the existing DTO/UI only; these fields are intentionally not scored by V1.
        tactic.setInPossession("Standard");
        tactic.setTimeWasting("Never");
        tactic.setDribbling("Standard");
        tactic.setFoulFrequency("Normal");
        tactic.setFoulHardness("Medium");
        tactic.setTempoFragmentation("Normal");
        tactic.setWidePlay("Shoot");
        tactic.setTransition("Balanced");
        return tactic;
    }

    private static PersonalizedTactic copyTacticAxes(PersonalizedTactic source) {
        PersonalizedTactic copy = neutralCanonicalTactic();
        copy.setMentality(source.getMentality());
        copy.setTempo(source.getTempo());
        copy.setPassingType(source.getPassingType());
        copy.setDefensiveLine(source.getDefensiveLine());
        copy.setPressing(source.getPressing());
        copy.setWidth(source.getWidth());
        return copy;
    }

    private CanonicalRuntimeTeamInput canonicalTeamInput(long teamId, String formation,
                                                          PersonalizedTactic tactic) {
        List<TacticController.StarterSlot> selected =
                tacticController.getBestElevenWithSlots(String.valueOf(teamId), formation);
        if (selected.size() != 11) {
            throw new IllegalArgumentException("Team " + teamId + " does not have a complete XI for " + formation);
        }
        List<Long> ids = selected.stream().map(slot -> slot.player().getId()).toList();
        Map<Long, Human> humans = new HashMap<>();
        humanRepo.findAllById(ids).forEach(human -> humans.put(human.getId(), human));
        Map<Long, PlayerSkills> skills = new HashMap<>();
        playerSkillsRepository.findAllByPlayerIdIn(ids).forEach(row -> skills.put(row.getPlayerId(), row));

        Map<Long, FormationData> savedData = savedFormationData(teamId, formation);
        Map<PlayerPosition, Integer> occurrences = new java.util.EnumMap<>(PlayerPosition.class);
        List<RuntimeLineupSlot> slots = new ArrayList<>(11);
        for (TacticController.StarterSlot selectedSlot : selected) {
            long playerId = selectedSlot.player().getId();
            Human human = humans.get(playerId);
            PlayerSkills playerSkills = skills.get(playerId);
            if (human == null || playerSkills == null) {
                throw new IllegalArgumentException("Missing canonical player data for " + playerId);
            }
            PlayerPosition position = PlayerPosition.require(selectedSlot.usedPosition());
            int occurrence = occurrences.merge(position, 1, Integer::sum);
            slots.add(new RuntimeLineupSlot(human, playerSkills, savedData.get(playerId), position, occurrence));
        }
        return canonicalRuntimeInputFactory.build(tactic, slots);
    }

    private Map<Long, FormationData> savedFormationData(long teamId, String formation) {
        PersonalizedTacticView saved = tacticController.getFormation(String.valueOf(teamId));
        if (saved == null || saved.getTactic() == null || !saved.getTactic().equalsIgnoreCase(formation)
                || saved.getFormationDataList() == null) return Map.of();
        Map<Long, FormationData> result = new HashMap<>();
        saved.getFormationDataList().stream()
                .filter(row -> row != null && row.getPlayerId() > 0 && row.getPositionIndex() < 30)
                .forEach(row -> result.put(row.getPlayerId(), row));
        return result;
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

    private String formationFor(long teamId) {
        return humanRepo.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE).stream()
                .filter(m -> !m.isRetired())
                .map(Human::getTacticStyle)
                .filter(f -> f != null && !f.isBlank())
                .findFirst()
                .orElse(DEFAULT_FORMATION);
    }

    private String advisorFormationFor(long teamId) {
        return personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId)
                .map(PersonalizedTactic::getTactic)
                .filter(value -> value != null && !value.isBlank())
                .orElseGet(() -> formationFor(teamId));
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
            double[] apt = TacticalScoreService.playerAptitudes(sk, pv.getFitness());
            starters.add(new TacticalScoreService.StarterValue(used, value, apt[0], apt[1], apt[2]));
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
