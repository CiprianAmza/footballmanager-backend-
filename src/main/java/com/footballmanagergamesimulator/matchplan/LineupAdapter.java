package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.PersonalizedTacticRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Turns a team's selected eleven + bench into a {@link Lineup} of
 * {@link Contributor}s the {@link ContributionResolver} can score from, loading
 * Passing/Vision/Finishing from {@link PlayerSkills} and honouring the
 * personalized-tactic penalty/free-kick takers.
 *
 * <p>For the instant/AI path it also pre-simulates a deterministic substitution
 * timeline (seed-derived) so bench players can score late goals — matching the
 * old behaviour where substitutes could appear on the scoresheet.
 */
@Service
public class LineupAdapter {

    @Autowired @Lazy private TacticController tacticController;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;
    @Autowired private PersonalizedTacticRepository personalizedTacticRepository;
    @Autowired private MatchEngineConfig engineConfig;

    /** Build a lineup with a simulated substitution timeline for team {@code teamId}. */
    public Lineup build(long teamId, String tactic, long seed) {
        List<PlayerView> xiViews = safe(tacticController.getBestEleven(String.valueOf(teamId), tactic));
        List<PlayerView> benchViews = safe(tacticController.getSubstitutions(String.valueOf(teamId), tactic));

        List<Long> ids = new ArrayList<>();
        xiViews.forEach(v -> ids.add(v.getId()));
        benchViews.forEach(v -> ids.add(v.getId()));
        Map<Long, PlayerSkills> skills = playerSkillsRepository.findAllByPlayerIdIn(ids).stream()
                .collect(Collectors.toMap(PlayerSkills::getPlayerId, ps -> ps, (a, b) -> a));

        Optional<PersonalizedTactic> pt = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId);
        Long penaltyTakerId = pt.map(PersonalizedTactic::getPenaltyTakerId).orElse(null);
        Long freeKickTakerId = pt.map(PersonalizedTactic::getFreeKickTakerId).orElse(null);

        List<Contributor> xi = xiViews.stream()
                .map(v -> toContributor(v, skills, penaltyTakerId, freeKickTakerId)).collect(Collectors.toList());
        List<Contributor> bench = benchViews.stream()
                .map(v -> toContributor(v, skills, penaltyTakerId, freeKickTakerId)).collect(Collectors.toList());

        List<Lineup.SubMove> subs = simulateSubs(xi, bench, seed * 31 + teamId);
        return new Lineup(xi, subs);
    }

    private Contributor toContributor(PlayerView v, Map<Long, PlayerSkills> skills,
                                      Long penaltyTakerId, Long freeKickTakerId) {
        PlayerSkills s = skills.get(v.getId());
        int finishing = s != null ? s.getFinishing() : 0;
        int passing = s != null ? s.getPassing() : 0;
        int vision = s != null ? s.getVision() : 0;
        return new Contributor(
                v.getId(), v.getName(), v.getPosition(), v.getRating(),
                finishing, passing, vision, v.getFitness(),
                penaltyTakerId != null && penaltyTakerId == v.getId(),
                freeKickTakerId != null && freeKickTakerId == v.getId());
    }

    /** Deterministically pair outfield starters off for bench players, at spread minutes. */
    private List<Lineup.SubMove> simulateSubs(List<Contributor> xi, List<Contributor> bench, long seed) {
        List<Contributor> outfieldStarters = xi.stream().filter(c -> !c.isGoalkeeper()).collect(Collectors.toList());
        List<Contributor> benchPool = new ArrayList<>(bench);
        if (outfieldStarters.isEmpty() || benchPool.isEmpty()) return List.of();

        Random rng = new Random(seed);
        Collections.shuffle(outfieldStarters, rng);
        Collections.shuffle(benchPool, rng);

        MatchEngineConfig.Events ev = engineConfig.getEvents();
        int count = Math.min(ev.getSubstitutionsPerTeam(), Math.min(outfieldStarters.size(), benchPool.size()));
        int minMin = ev.getSubstitutionMinuteMin();
        int minMax = ev.getSubstitutionMinuteMax();

        List<Integer> minutes = new ArrayList<>();
        for (int i = 0; i < count; i++) minutes.add(rng.nextInt(minMin, minMax));
        Collections.sort(minutes);

        List<Lineup.SubMove> moves = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            moves.add(new Lineup.SubMove(minutes.get(i), outfieldStarters.get(i).playerId(), benchPool.get(i)));
        }
        return moves;
    }

    private List<PlayerView> safe(List<PlayerView> list) {
        return list != null ? list : List.of();
    }
}
