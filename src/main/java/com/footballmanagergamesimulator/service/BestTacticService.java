package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.model.PlayerSkills;
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
import java.util.List;
import java.util.Map;

/**
 * Read-only advisory search that recommends the best formation + tactic-setting combination for a
 * team using its CURRENT squad straight from the database (live morale, fitness, injuries, ratings,
 * attributes). It rebuilds the coached attack/defense {@link TeamProfile} per formation exactly the
 * way {@link MatchRoundSimulator} does for live matches (best XI by current value, position
 * familiarity, manager coaching), then ranks the 900 setting combinations from
 * {@link ManagerTacticService#candidateTactics()} by the cheap
 * {@link TacticalScoreService#expectedGoalDifference} proxy against a representative opponent (a
 * mirror of the team's own coached profile — the same neutral matchup the AI manager optimizes
 * against). Does not mutate any state.
 */
@Service
public class BestTacticService {

    @Autowired private TacticController tacticController;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;
    @Autowired private PlayerValueService playerValueService;
    @Autowired private HumanRepository humanRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TacticService tacticService;
    @Autowired private TacticalScoreService tacticalScoreService;
    @Autowired private ManagerTacticService managerTacticService;

    public record TacticRow(String formation, String mentality, String tempo, String passingType,
                            String inPossession, String timeWasting, double expectedGoalDifference) {}

    /** A fully-scored tactic row for the rank-all report: expected points + W/D/L vs an equal opponent + xGD. */
    public record FullTacticRow(String formation, String mentality, String tempo, String passingType,
                                String inPossession, String timeWasting, double expectedPoints,
                                double winProb, double drawProb, double lossProb,
                                double expectedGoalDifference) {}

    public record RankAllResult(long teamId, String teamName, double baseSquadValue,
                                FullTacticRow recommended, List<FullTacticRow> rows) {}

    public record BestTacticResult(long teamId, String teamName, String recommendedFormation,
                                   String recommendedMentality, String recommendedTempo,
                                   String recommendedPassingType, String recommendedInPossession,
                                   String recommendedTimeWasting, double expectedGoalDifference,
                                   double baseSquadValue, List<TacticRow> top) {}

    /** Search every formation × the 900 tactic settings for {@code teamId}, all on its live squad. */
    public BestTacticResult findBestTactic(long teamId) {
        double[] coach = coachAbilities(teamId);
        List<PersonalizedTactic> candidates = managerTacticService.candidateTactics();
        TacticVector neutralOpp = tacticalScoreService.vector(new PersonalizedTactic());

        List<TacticRow> all = new ArrayList<>();
        double bestBaseValue = -1;
        String bestFormation = "442";
        for (String formation : tacticService.getAllExistingTactics()) {
            TeamProfile profile = tacticalScoreService.coachedProfile(
                    tacticalScoreService.profile(starterValues(teamId, formation)), coach[0], coach[1]);
            double baseValue = profile.attack() + profile.defense();
            if (baseValue > bestBaseValue) { bestBaseValue = baseValue; bestFormation = formation; }
            // Representative opponent = a mirror of this coached profile (an even, neutral matchup).
            for (PersonalizedTactic t : candidates) {
                double egd = tacticalScoreService.expectedGoalDifference(
                        profile, tacticalScoreService.vector(t), profile, neutralOpp);
                all.add(new TacticRow(formation, t.getMentality(), t.getTempo(), t.getPassingType(),
                        t.getInPossession(), t.getTimeWasting(), egd));
            }
        }
        all.sort(Comparator.comparingDouble(TacticRow::expectedGoalDifference).reversed());

        TacticRow best = all.get(0);
        List<TacticRow> top = all.subList(0, Math.min(15, all.size()));
        String name = teamRepository.findNameById(teamId);
        return new BestTacticResult(teamId, name == null ? "Team#" + teamId : name,
                best.formation(), best.mentality(), best.tempo(), best.passingType(),
                best.inPossession(), best.timeWasting(), best.expectedGoalDifference(),
                bestBaseValue, new ArrayList<>(top));
    }

    /**
     * Score EVERY formation × the 900 tactic settings ({@code 15 × 900 = 13,500}) for {@code teamId}
     * on its live squad, sorted descending by {@link TacticalScoreService#expectedPoints} against an
     * equal opponent (a mirror of the team's own coached profile on a neutral tactic). Same coached
     * profile + best-XI value logic the AI/advisor uses; nothing is mutated.
     */
    public RankAllResult rankAllTactics(long teamId) {
        double[] coach = coachAbilities(teamId);
        List<PersonalizedTactic> candidates = managerTacticService.candidateTactics();
        TacticVector neutralOpp = tacticalScoreService.vector(new PersonalizedTactic());

        List<FullTacticRow> rows = new ArrayList<>(candidates.size() * 15);
        double bestBaseValue = -1;
        for (String formation : tacticService.getAllExistingTactics()) {
            TeamProfile profile = tacticalScoreService.coachedProfile(
                    tacticalScoreService.profile(starterValues(teamId, formation)), coach[0], coach[1]);
            double baseValue = profile.attack() + profile.defense();
            if (baseValue > bestBaseValue) bestBaseValue = baseValue;
            // Representative opponent = a mirror of this coached profile (an even, neutral matchup).
            for (PersonalizedTactic t : candidates) {
                TacticVector vec = tacticalScoreService.vector(t);
                double ep = tacticalScoreService.expectedPoints(profile, vec, profile, neutralOpp);
                TacticalScoreService.Outcome o = tacticalScoreService.outcomeProbabilities(profile, vec, profile, neutralOpp);
                double egd = tacticalScoreService.expectedGoalDifference(profile, vec, profile, neutralOpp);
                rows.add(new FullTacticRow(formation, t.getMentality(), t.getTempo(), t.getPassingType(),
                        t.getInPossession(), t.getTimeWasting(), ep, o.win(), o.draw(), o.loss(), egd));
            }
        }
        rows.sort(Comparator.comparingDouble(FullTacticRow::expectedPoints).reversed()
                .thenComparing(Comparator.comparingDouble(FullTacticRow::expectedGoalDifference).reversed()));

        String name = teamRepository.findNameById(teamId);
        return new RankAllResult(teamId, name == null ? "Team#" + teamId : name,
                bestBaseValue, rows.get(0), rows);
    }

    /** Live best-eleven match values for a formation (used position kept for position familiarity),
     *  mirroring {@link MatchRoundSimulator#starterValues}. */
    private List<TacticalScoreService.StarterValue> starterValues(long teamId, String formation) {
        List<TacticController.StarterSlot> starters =
                tacticController.getBestElevenWithSlots(String.valueOf(teamId), formation);
        List<Long> ids = starters.stream().map(s -> s.player().getId()).toList();
        Map<Long, PlayerSkills> skillsById = new HashMap<>();
        for (PlayerSkills s : playerSkillsRepository.findAllByPlayerIdIn(ids)) skillsById.put(s.getPlayerId(), s);

        List<TacticalScoreService.StarterValue> values = new ArrayList<>();
        for (TacticController.StarterSlot slot : starters) {
            PlayerView pv = slot.player();
            String natural = pv.getPosition(), used = slot.usedPosition();
            PlayerSkills sk = skillsById.get(pv.getId());
            double v = sk != null
                    ? playerValueService.evaluatePlayer(sk, natural, used, pv.getMorale(), pv.getFitness())
                    : playerValueService.evaluatePlayer(pv.getRating(), natural, used, pv.getMorale(), pv.getFitness());
            values.add(new TacticalScoreService.StarterValue(used, v));
        }
        return values;
    }

    private double[] coachAbilities(long teamId) {
        return humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE).stream()
                .filter(m -> !m.isRetired())
                .findFirst()
                .map(m -> new double[]{m.getOffensiveAbility(), m.getDefensiveAbility()})
                .orElse(new double[]{50.0, 50.0});
    }
}
