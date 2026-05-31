package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.config.MatchEngineConfig.TacticalModel;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.service.TacticalScoreService.TeamProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Chooses a tactic for an AI manager based on his tactical ability. The full tactic-setting space
 * is ranked (best first) by average expected POINTS across an opponent panel
 * ({@link TacticalScoreService#panelExpectedPoints}); a manager of ability {@code a} (0-100) then picks the tactic
 * at rank {@code round((100-a)/100 × (N-1))} — top coaches play near-optimal tactics, weak coaches
 * play poor ones. Because the model is matchup-based, "best on average" still loses some specific
 * matchups, so leagues hold a meaningful spread of tactical quality rather than one dominant setup.
 *
 * <p>Every valid value of every axis is enumerated from the single-source catalog on
 * {@link MatchEngineConfig.TacticalModel} (the {@code *_OPTIONS} lists), so production AI selection,
 * the advisor, and the tests always explore the exact same tactic space.
 */
@Service
public class ManagerTacticService {

    @Autowired TacticalScoreService tacticalScoreService;
    @Autowired MatchEngineConfig engineConfig;

    /** All candidate tactic-setting combinations (formation is chosen separately by squad fit). */
    public List<PersonalizedTactic> candidateTactics() {
        List<PersonalizedTactic> out = new ArrayList<>(900);
        for (String mentality : TacticalModel.MENTALITY_OPTIONS)
            for (String timeWasting : TacticalModel.TIME_WASTING_OPTIONS)
                for (String inPossession : TacticalModel.IN_POSSESSION_OPTIONS)
                    for (String passing : TacticalModel.PASSING_OPTIONS)
                        for (String tempo : TacticalModel.TEMPO_OPTIONS)
                            out.add(build(mentality, timeWasting, inPossession, passing, tempo));
        return out;
    }

    /**
     * Pick a tactic for a manager of the given ability (0-100) for team {@code mine}. Each base
     * setting is scored by average expected POINTS across an opponent panel ({@link
     * TacticalScoreService#panelExpectedPoints}); the settings are then collapsed to the DISTINCT
     * point tiers (one tactic per distinct value, as displayed) sorted worst→best. The manager's
     * rating is a percentile into those tiers: <b>0 → the first (worst) tier, 100 → the last (best),
     * 50 → the median</b>. So managers of different skill genuinely play different-quality tactics,
     * instead of all landing on near-identical settings that merely tie on points. The chosen base is
     * then completed with line/pressing + the Faza-2 instructions via the greedy search (width is left
     * to the caller, a squad-shape identity via {@link #widthIdentity}). The {@code opponent} argument
     * is unused (the panel is derived from {@code mine}) but kept for caller compatibility.
     */
    public PersonalizedTactic chooseTactic(TeamProfile mine, TeamProfile opponent, double tacticalAbility) {
        record Scored(PersonalizedTactic tactic, double pts) {}
        List<Scored> scored = new ArrayList<>(900);
        for (PersonalizedTactic t : candidateTactics())
            scored.add(new Scored(t,
                    tacticalScoreService.panelExpectedPoints(mine, tacticalScoreService.vector(t))));
        scored.sort(Comparator.comparingDouble(Scored::pts)); // ascending: worst → best

        // Collapse to one tactic per distinct (2-decimal) point value — the skill tiers.
        List<PersonalizedTactic> tiers = new ArrayList<>();
        String lastKey = null;
        for (Scored s : scored) {
            String key = String.format(java.util.Locale.ROOT, "%.2f", s.pts());
            if (!key.equals(lastKey)) { tiers.add(s.tactic()); lastKey = key; }
        }

        double a = Math.max(0, Math.min(100, tacticalAbility));
        int index = (int) Math.round(a / 100.0 * (tiers.size() - 1)); // 0→worst(first), 100→best(last)
        PersonalizedTactic base = tiers.get(index);
        augmentLineAndPress(base, mine, a);
        augmentInstructions(base, mine, a);
        return base;
    }

    /**
     * Pick the defensive line + pressing for {@code base} via a cheap coordinate search: rank the 9
     * line×press combinations (holding the chosen settings fixed) by {@link
     * TacticalScoreService#panelExpectedPoints} and take the one at the manager's ability rank — top
     * coaches play the panel-optimal line/press, weak coaches play poor ones. Avoids enumerating the
     * full settings × line × press × width grid on the per-round AI hot path.
     */
    private void augmentLineAndPress(PersonalizedTactic base, TeamProfile mine, double ability) {
        record Combo(String line, String press, double score) {}
        List<String> lines = TacticalModel.DEFENSIVE_LINE_OPTIONS, presses = TacticalModel.PRESSING_OPTIONS;
        List<Combo> combos = new ArrayList<>(lines.size() * presses.size());
        for (String line : lines) {
            for (String press : presses) {
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
     * Pick the six Faza-2 team instructions (dribbling, foul frequency, foul hardness, tempo
     * fragmentation, wide play, transition) for {@code base} via a per-axis skill-ranked greedy search
     * (coordinate descent): for each axis, holding the rest fixed, rank its values by {@link
     * TacticalScoreService#panelExpectedPoints} and take the one at the manager's ability rank. Top
     * coaches play near-optimal instructions, weak coaches poor ones — the same selection model as
     * line/press, so production AI, advisor and tests all explore these axes identically.
     */
    private void augmentInstructions(PersonalizedTactic base, TeamProfile mine, double ability) {
        for (InstructionAxis axis : INSTRUCTION_AXES) {
            record Scored(String value, double score) {}
            List<Scored> scored = new ArrayList<>(axis.options().size());
            for (String value : axis.options()) {
                axis.setter().accept(base, value);
                scored.add(new Scored(value,
                        tacticalScoreService.panelExpectedPoints(mine, tacticalScoreService.vector(base))));
            }
            scored.sort(Comparator.comparingDouble(Scored::score).reversed());
            int idx = (int) Math.round((100 - ability) / 100.0 * (scored.size() - 1));
            axis.setter().accept(base, scored.get(idx).value());
        }
    }

    /** One Faza-2 instruction axis: its valid values (from the config catalog) + how to set it. */
    private record InstructionAxis(List<String> options, BiConsumer<PersonalizedTactic, String> setter) {}

    private static final List<InstructionAxis> INSTRUCTION_AXES = List.of(
            new InstructionAxis(TacticalModel.DRIBBLING_OPTIONS, PersonalizedTactic::setDribbling),
            new InstructionAxis(TacticalModel.FOUL_FREQUENCY_OPTIONS, PersonalizedTactic::setFoulFrequency),
            new InstructionAxis(TacticalModel.FOUL_HARDNESS_OPTIONS, PersonalizedTactic::setFoulHardness),
            new InstructionAxis(TacticalModel.TEMPO_FRAGMENTATION_OPTIONS, PersonalizedTactic::setTempoFragmentation),
            new InstructionAxis(TacticalModel.WIDE_PLAY_OPTIONS, PersonalizedTactic::setWidePlay),
            new InstructionAxis(TacticalModel.TRANSITION_OPTIONS, PersonalizedTactic::setTransition));

    /**
     * Stamp every non-grid axis onto a base tactic for advisor / simulation suggestions: defensive line
     * + pressing via the skill-ranked coordinate search, width as the squad-shape identity, and the six
     * Faza-2 instructions via the per-axis greedy search. Produces the same complete 14-axis tactic the
     * production AI ({@link #chooseTactic}) does, so advisor == production.
     */
    public void applyNewAxes(PersonalizedTactic base, TeamProfile mine, double ability, double wideShare) {
        augmentLineAndPress(base, mine, ability);
        base.setWidth(widthIdentity(wideShare));
        augmentInstructions(base, mine, ability);
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

    /**
     * One new (Strat-2 / Faza-2) axis: its valid values, its neutral default (the token that
     * contributes 0 in {@link TacticalScoreService#vector} — "factor 1" / no-op), and how to set it.
     */
    private record NewAxis(List<String> options, String neutral,
                           BiConsumer<PersonalizedTactic, String> setter) {}

    /** All 9 new axes with their neutral defaults (must match {@code TacticalScoreService.vector}'s
     *  {@code orDefault} tokens, i.e. the value that maps to 0 in the config axis maps). */
    private static final List<NewAxis> NEW_AXES = List.of(
            new NewAxis(TacticalModel.DEFENSIVE_LINE_OPTIONS, "Standard", PersonalizedTactic::setDefensiveLine),
            new NewAxis(TacticalModel.PRESSING_OPTIONS, "Low", PersonalizedTactic::setPressing),
            new NewAxis(TacticalModel.WIDTH_OPTIONS, "Balanced", PersonalizedTactic::setWidth),
            new NewAxis(TacticalModel.DRIBBLING_OPTIONS, "Standard", PersonalizedTactic::setDribbling),
            new NewAxis(TacticalModel.FOUL_FREQUENCY_OPTIONS, "Normal", PersonalizedTactic::setFoulFrequency),
            new NewAxis(TacticalModel.FOUL_HARDNESS_OPTIONS, "Medium", PersonalizedTactic::setFoulHardness),
            new NewAxis(TacticalModel.TEMPO_FRAGMENTATION_OPTIONS, "Normal", PersonalizedTactic::setTempoFragmentation),
            new NewAxis(TacticalModel.WIDE_PLAY_OPTIONS, "Shoot", PersonalizedTactic::setWidePlay),
            new NewAxis(TacticalModel.TRANSITION_OPTIONS, "Balanced", PersonalizedTactic::setTransition));

    /**
     * "Default = factor 1" enumeration of the new axes over the 900 base settings (for ONE formation):
     * the neutral default of every new axis is the baseline (always present, no-op), and each
     * non-default value is added one axis at a time (other new axes held at default). So per base
     * setting we emit 1 all-neutral tactic + one variant per non-default value of each axis —
     * 900 × (1 + Σ(options-1)) = 900 × (1 + 18) = <b>17,100</b> complete tactics. Additive, not the
     * 17.7M multiplicative cross-product: each axis is explored individually against the neutral
     * baseline, so its own contribution is visible without the combinatorial blow-up.
     */
    public List<PersonalizedTactic> candidateTacticsWithNewAxes() {
        List<PersonalizedTactic> out = new ArrayList<>(17_100);
        for (PersonalizedTactic base : candidateTactics()) {
            for (NewAxis a : NEW_AXES) a.setter().accept(base, a.neutral());
            out.add(copy(base));                                  // all new axes neutral
            for (NewAxis a : NEW_AXES) {
                for (String value : a.options()) {
                    if (value.equals(a.neutral())) continue;
                    a.setter().accept(base, value);               // one axis off neutral
                    out.add(copy(base));                          // others stay neutral
                    a.setter().accept(base, a.neutral());         // reset for the next deviation
                }
            }
        }
        return out;
    }

    /** Copy the 14 scoring axes of a tactic (everything {@link TacticalScoreService#vector} reads). */
    private static PersonalizedTactic copy(PersonalizedTactic s) {
        PersonalizedTactic t = new PersonalizedTactic();
        t.setMentality(s.getMentality());
        t.setTimeWasting(s.getTimeWasting());
        t.setInPossession(s.getInPossession());
        t.setPassingType(s.getPassingType());
        t.setTempo(s.getTempo());
        t.setDefensiveLine(s.getDefensiveLine());
        t.setPressing(s.getPressing());
        t.setWidth(s.getWidth());
        t.setDribbling(s.getDribbling());
        t.setFoulFrequency(s.getFoulFrequency());
        t.setFoulHardness(s.getFoulHardness());
        t.setTempoFragmentation(s.getTempoFragmentation());
        t.setWidePlay(s.getWidePlay());
        t.setTransition(s.getTransition());
        return t;
    }
}
