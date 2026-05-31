package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
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
 * is ranked (best first) by average expected POINTS across an opponent panel
 * ({@link TacticalScoreService#panelExpectedPoints}); a manager of ability {@code a} (0-100) then picks the tactic
 * at rank {@code round((100-a)/100 × (N-1))} — top coaches play near-optimal tactics, weak coaches
 * play poor ones. Because the model is matchup-based, "best on average" still loses some specific
 * matchups, so leagues hold a meaningful spread of tactical quality rather than one dominant setup.
 */
@Service
public class ManagerTacticService {

    @Autowired TacticalScoreService tacticalScoreService;
    @Autowired MatchEngineConfig engineConfig;

    private static final List<String> MENTALITIES = List.of(
            "Very Attacking", "Attacking", "Balanced", "Defensive", "Very Defensive");
    private static final List<String> TIME_WASTING = List.of("Never", "Sometimes", "Frequently", "Always");
    private static final List<String> IN_POSSESSION = List.of("Standard", "Keep Ball", "Free Ball Early");
    private static final List<String> PASSING = List.of("Short", "Normal", "Long");
    private static final List<String> TEMPO = List.of("Much Lower", "Lower", "Standard", "Higher", "Much Higher");
    private static final List<String> LINE_HEIGHTS = List.of("Deep", "Standard", "High");
    private static final List<String> PRESSING = List.of("Low", "Standard", "High");

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

    /**
     * Candidate tactics ranked best-first for {@code mine} by average expected POINTS across an
     * opponent panel (weaker / equal / stronger, all neutral), via
     * {@link TacticalScoreService#panelExpectedPoints}. The {@code opponent} argument is retained for
     * signature compatibility with callers but no longer used: the panel is derived from {@code mine}
     * so the openness axes (tempo, passing) no longer cancel against a self-mirror.
     */
    public List<PersonalizedTactic> rankTactics(TeamProfile mine, TeamProfile opponent) {
        List<PersonalizedTactic> tactics = candidateTactics();
        tactics.sort(Comparator.comparingDouble((PersonalizedTactic t) ->
                tacticalScoreService.panelExpectedPoints(mine, tacticalScoreService.vector(t))).reversed());
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
        PersonalizedTactic base = ranked.get(index);
        augmentLineAndPress(base, mine, a);
        return base;
    }

    /**
     * Pick the defensive line + pressing for {@code base} via a cheap coordinate search: rank the 9
     * line×press combinations (holding the chosen settings fixed) by {@link
     * TacticalScoreService#panelExpectedPoints} and take the one at the manager's ability rank — top
     * coaches play the panel-optimal line/press, weak coaches play poor ones. Avoids enumerating the
     * full settings × line × press × width grid (27× larger) on the per-round AI hot path.
     */
    private void augmentLineAndPress(PersonalizedTactic base, TeamProfile mine, double ability) {
        record Combo(String line, String press, double score) {}
        List<Combo> combos = new ArrayList<>(LINE_HEIGHTS.size() * PRESSING.size());
        for (String line : LINE_HEIGHTS) {
            for (String press : PRESSING) {
                base.setDefensiveLine(line);
                base.setPressing(press);
                combos.add(new Combo(line, press,
                        tacticalScoreService.panelExpectedPoints(mine, tacticalScoreService.vector(base))));
            }
        }
        combos.sort(Comparator.comparingDouble(Combo::score).reversed());
        int idx = (int) Math.round((100 - ability) / 100.0 * (combos.size() - 1));
        base.setDefensiveLine(combos.get(idx).line());
        base.setPressing(combos.get(idx).press());
    }

    /**
     * Stamp the Strat-2 axes onto a base tactic for advisor / simulation suggestions: defensive line +
     * pressing via the skill-ranked coordinate search, width as the squad-shape identity. The perf
     * mitigation for "enumerate the new axes" — O(900 + 9) instead of the full 24,300-combo grid.
     */
    public void applyNewAxes(PersonalizedTactic base, TeamProfile mine, double ability, double wideShare) {
        augmentLineAndPress(base, mine, ability);
        base.setWidth(widthIdentity(wideShare));
    }

    /**
     * The AI's width <b>identity</b> from its squad shape (not opponent-optimized — width is invisible
     * against a width-neutral panel, so it is a stylistic choice): a team with a high value share in
     * wide positions plays Wide, a centrally-loaded one Narrow, else Balanced. Gives the human's width
     * counter something to bite and makes AI-vs-AI width matchups fire.
     */
    public String widthIdentity(double wideShare) {
        MatchEngineConfig.TacticalModel cfg = engineConfig.getTacticalModel();
        if (wideShare >= cfg.getAiWidthWideThreshold()) return "Wide";
        if (wideShare <= cfg.getAiWidthNarrowThreshold()) return "Narrow";
        return "Balanced";
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
