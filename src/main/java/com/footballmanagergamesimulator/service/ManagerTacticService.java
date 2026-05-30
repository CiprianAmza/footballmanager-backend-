package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.service.TacticalScoreService.TeamProfile;
import com.footballmanagergamesimulator.service.TacticalScoreService.TacticVector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Chooses a tactic for an AI manager based on his tactical ability. The full tactic-setting space
 * is ranked (best first) by the cheap {@link TacticalScoreService#expectedGoalDifference} proxy
 * against a representative opponent; a manager of ability {@code a} (0-100) then picks the tactic
 * at rank {@code round((100-a)/100 × (N-1))} — top coaches play near-optimal tactics, weak coaches
 * play poor ones. Because the model is matchup-based, "best on average" still loses some specific
 * matchups, so leagues hold a meaningful spread of tactical quality rather than one dominant setup.
 */
@Service
public class ManagerTacticService {

    @Autowired private TacticalScoreService tacticalScoreService;

    private static final List<String> MENTALITIES = List.of(
            "Very Attacking", "Attacking", "Balanced", "Defensive", "Very Defensive");
    private static final List<String> TIME_WASTING = List.of("Never", "Sometimes", "Frequently", "Always");
    private static final List<String> IN_POSSESSION = List.of("Standard", "Keep Ball", "Free Ball Early");
    private static final List<String> PASSING = List.of("Short", "Normal", "Long");
    private static final List<String> TEMPO = List.of("Much Lower", "Lower", "Standard", "Higher", "Much Higher");

    /** All candidate tactic-setting combinations (formation is chosen separately by squad fit). */
    public List<PersonalizedTactic> candidateTactics() {
        List<PersonalizedTactic> out = new ArrayList<>(900);
        for (String mentality : MENTALITIES)
            for (String timeWasting : TIME_WASTING)
                for (String inPossession : IN_POSSESSION)
                    for (String passing : PASSING)
                        for (String tempo : TEMPO)
                            out.add(build(mentality, timeWasting, inPossession, passing, tempo));
        return out;
    }

    /** Candidate tactics ranked best-first for {@code mine} against a representative {@code opponent}. */
    public List<PersonalizedTactic> rankTactics(TeamProfile mine, TeamProfile opponent) {
        TacticVector neutralOpp = tacticalScoreService.vector(new PersonalizedTactic());
        List<PersonalizedTactic> tactics = candidateTactics();
        tactics.sort(Comparator.comparingDouble((PersonalizedTactic t) ->
                tacticalScoreService.expectedGoalDifference(mine, tacticalScoreService.vector(t), opponent, neutralOpp)).reversed());
        return tactics;
    }

    /**
     * Pick a tactic for a manager of the given ability (0-100) facing a representative opponent.
     * Ability 100 ⇒ the top-ranked tactic; ability 0 ⇒ the worst.
     */
    public PersonalizedTactic chooseTactic(TeamProfile mine, TeamProfile opponent, double tacticalAbility) {
        List<PersonalizedTactic> ranked = rankTactics(mine, opponent);
        double a = Math.max(0, Math.min(100, tacticalAbility));
        int index = (int) Math.round((100 - a) / 100.0 * (ranked.size() - 1));
        return ranked.get(index);
    }

    private static PersonalizedTactic build(String mentality, String timeWasting,
                                            String inPossession, String passing, String tempo) {
        PersonalizedTactic t = new PersonalizedTactic();
        t.setMentality(mentality);
        t.setTimeWasting(timeWasting);
        t.setInPossession(inPossession);
        t.setPassingType(passing);
        t.setTempo(tempo);
        return t;
    }
}
