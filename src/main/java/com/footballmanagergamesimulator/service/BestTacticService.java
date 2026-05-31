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
 * {@link ManagerTacticService#candidateTactics()} by average expected POINTS across an opponent
 * panel ({@link TacticalScoreService#panelExpectedPoints} — weaker/equal/stronger), the same metric
 * the AI manager optimizes against. Does not mutate any state.
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
                            String inPossession, String timeWasting, double expectedGoalDifference,
                            double expectedPoints) {}

    public record BestTacticResult(long teamId, String teamName, String recommendedFormation,
                                   String recommendedMentality, String recommendedTempo,
                                   String recommendedPassingType, String recommendedInPossession,
                                   String recommendedTimeWasting, double expectedGoalDifference,
                                   double expectedPoints, double winProbability, double drawProbability,
                                   double lossProbability, double baseSquadValue, List<TacticRow> top) {}

    /** Search every formation × the 900 tactic settings for {@code teamId}, all on its live squad. */
    public BestTacticResult findBestTactic(long teamId) {
        double[] coach = coachAbilities(teamId);
        List<PersonalizedTactic> candidates = managerTacticService.candidateTactics();
        TacticVector neutralOpp = tacticalScoreService.vector(new PersonalizedTactic());

        List<TacticRow> all = new ArrayList<>();
        Map<String, TeamProfile> profileByFormation = new HashMap<>();
        double bestBaseValue = -1;
        String bestFormation = "442";
        for (String formation : tacticService.getAllExistingTactics()) {
            TeamProfile profile = tacticalScoreService.coachedProfile(
                    tacticalScoreService.profile(starterValues(teamId, formation)), coach[0], coach[1]);
            profileByFormation.put(formation, profile);
            double baseValue = profile.attack() + profile.defense();
            if (baseValue > bestBaseValue) { bestBaseValue = baseValue; bestFormation = formation; }
            // Rank by average expected POINTS across an opponent panel (weaker/equal/stronger),
            // not self-mirror xGD — so tempo/passing differentiate and coaching skill matters.
            for (PersonalizedTactic t : candidates) {
                TacticVector mv = tacticalScoreService.vector(t);
                double egd = tacticalScoreService.expectedGoalDifference(profile, mv, profile, neutralOpp);
                double ep = tacticalScoreService.panelExpectedPoints(profile, mv);
                all.add(new TacticRow(formation, t.getMentality(), t.getTempo(), t.getPassingType(),
                        t.getInPossession(), t.getTimeWasting(), egd, ep));
            }
        }
        all.sort(Comparator.comparingDouble(TacticRow::expectedPoints).reversed());

        TacticRow best = all.get(0);
        List<TacticRow> top = all.subList(0, Math.min(15, all.size()));

        // Win/draw/loss for the recommended tactic vs the EQUAL (1.0×) opponent playing neutral.
        TeamProfile bestProfile = profileByFormation.get(best.formation());
        TacticVector bestVector = tacticalScoreService.vector(reconstruct(best));
        double[] xgEqual = tacticalScoreService.expectedGoalsForRanking(bestProfile, bestVector,
                tacticalScoreService.scaled(bestProfile, 1.0), neutralOpp);
        double[] wdl = tacticalScoreService.outcomeProbabilities(xgEqual[0], xgEqual[1]);

        String name = teamRepository.findNameById(teamId);
        return new BestTacticResult(teamId, name == null ? "Team#" + teamId : name,
                best.formation(), best.mentality(), best.tempo(), best.passingType(),
                best.inPossession(), best.timeWasting(), best.expectedGoalDifference(),
                best.expectedPoints(), wdl[0], wdl[1], wdl[2],
                bestBaseValue, new ArrayList<>(top));
    }

    /** Every formation × settings combination (all 13,500) for a team, sorted by panel expected
     *  points descending — the full ranked list (findBestTactic returns only the top 15). Same
     *  live-DB profiles + metric as the advisor, so the report matches production. */
    public List<TacticRow> rankAllTactics(long teamId) {
        double[] coach = coachAbilities(teamId);
        List<PersonalizedTactic> candidates = managerTacticService.candidateTactics();
        TacticVector neutralOpp = tacticalScoreService.vector(new PersonalizedTactic());
        List<TacticRow> all = new ArrayList<>();
        for (String formation : tacticService.getAllExistingTactics()) {
            TeamProfile profile = tacticalScoreService.coachedProfile(
                    tacticalScoreService.profile(starterValues(teamId, formation)), coach[0], coach[1]);
            for (PersonalizedTactic t : candidates) {
                TacticVector mv = tacticalScoreService.vector(t);
                double egd = tacticalScoreService.expectedGoalDifference(profile, mv, profile, neutralOpp);
                double ep = tacticalScoreService.panelExpectedPoints(profile, mv);
                all.add(new TacticRow(formation, t.getMentality(), t.getTempo(), t.getPassingType(),
                        t.getInPossession(), t.getTimeWasting(), egd, ep));
            }
        }
        all.sort(Comparator.comparingDouble(TacticRow::expectedPoints).reversed());
        return all;
    }

    /** Rebuild a {@link PersonalizedTactic} from a ranked row so its vector can be re-derived. */
    private static PersonalizedTactic reconstruct(TacticRow r) {
        PersonalizedTactic t = new PersonalizedTactic();
        t.setMentality(r.mentality());
        t.setTempo(r.tempo());
        t.setPassingType(r.passingType());
        t.setInPossession(r.inPossession());
        t.setTimeWasting(r.timeWasting());
        return t;
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
