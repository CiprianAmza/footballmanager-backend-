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
import java.util.function.Function;

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
        // AI commits to a tactical identity: drop the neutral value so a team never plays the bland
        // "Standard line / Low press" default (Deep|High × Standard|High). The advisor's full
        // enumeration still offers every value for the human to explore.
        List<String> lines = committed(TacticalModel.DEFENSIVE_LINE_OPTIONS, "Standard");
        List<String> presses = committed(TacticalModel.PRESSING_OPTIONS, "Low");
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
            // AI commits: drop the neutral value so each instruction is a real, non-default choice.
            List<String> options = committed(axis.options(), axis.neutral());
            List<Scored> scored = new ArrayList<>(options.size());
            for (String value : options) {
                axis.setter().accept(base, value);
                scored.add(new Scored(value,
                        tacticalScoreService.panelExpectedPoints(mine, tacticalScoreService.vector(base))));
            }
            scored.sort(Comparator.comparingDouble(Scored::score).reversed());
            int idx = (int) Math.round((100 - ability) / 100.0 * (scored.size() - 1));
            axis.setter().accept(base, scored.get(idx).value());
        }
    }

    /** All values of an axis except its neutral default — so the AI always commits to a real choice. */
    private static List<String> committed(List<String> options, String neutral) {
        List<String> out = new ArrayList<>(options.size());
        for (String o : options) if (!o.equals(neutral)) out.add(o);
        return out;
    }

    /** One Faza-2 instruction axis: its valid values, its neutral default, + how to set it. */
    private record InstructionAxis(List<String> options, String neutral,
                                   BiConsumer<PersonalizedTactic, String> setter) {}

    private static final List<InstructionAxis> INSTRUCTION_AXES = List.of(
            new InstructionAxis(TacticalModel.DRIBBLING_OPTIONS, "Standard", PersonalizedTactic::setDribbling),
            new InstructionAxis(TacticalModel.FOUL_FREQUENCY_OPTIONS, "Normal", PersonalizedTactic::setFoulFrequency),
            new InstructionAxis(TacticalModel.FOUL_HARDNESS_OPTIONS, "Medium", PersonalizedTactic::setFoulHardness),
            new InstructionAxis(TacticalModel.TEMPO_FRAGMENTATION_OPTIONS, "Normal", PersonalizedTactic::setTempoFragmentation),
            new InstructionAxis(TacticalModel.WIDE_PLAY_OPTIONS, "Shoot", PersonalizedTactic::setWidePlay),
            new InstructionAxis(TacticalModel.TRANSITION_OPTIONS, "Balanced", PersonalizedTactic::setTransition));

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
     * against a width-neutral panel, so it is a stylistic choice). The AI <b>commits</b>: a squad with a
     * wide-position value share above the midpoint of the two thresholds plays Wide, otherwise Narrow —
     * never the bland Balanced default. Gives the human's width counter something to bite and makes
     * AI-vs-AI width matchups fire.
     */
    public String widthIdentity(double wideShare) {
        MatchEngineConfig.TacticalModel cfg = engineConfig.getTacticalModel();
        double midpoint = (cfg.getAiWidthWideThreshold() + cfg.getAiWidthNarrowThreshold()) / 2.0;
        return wideShare >= midpoint ? "Wide" : "Narrow";
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
     * contributes 0 in {@link TacticalScoreService#vector} — "factor 1" / no-op), and how to read/set it.
     */
    private record NewAxis(List<String> options, String neutral,
                           Function<PersonalizedTactic, String> getter,
                           BiConsumer<PersonalizedTactic, String> setter) {}

    /** All 9 new axes with their neutral defaults (must match {@code TacticalScoreService.vector}'s
     *  {@code orDefault} tokens, i.e. the value that maps to 0 in the config axis maps). */
    private static final List<NewAxis> NEW_AXES = List.of(
            new NewAxis(TacticalModel.DEFENSIVE_LINE_OPTIONS, "Standard", PersonalizedTactic::getDefensiveLine, PersonalizedTactic::setDefensiveLine),
            new NewAxis(TacticalModel.PRESSING_OPTIONS, "Low", PersonalizedTactic::getPressing, PersonalizedTactic::setPressing),
            new NewAxis(TacticalModel.WIDTH_OPTIONS, "Balanced", PersonalizedTactic::getWidth, PersonalizedTactic::setWidth),
            new NewAxis(TacticalModel.DRIBBLING_OPTIONS, "Standard", PersonalizedTactic::getDribbling, PersonalizedTactic::setDribbling),
            new NewAxis(TacticalModel.FOUL_FREQUENCY_OPTIONS, "Normal", PersonalizedTactic::getFoulFrequency, PersonalizedTactic::setFoulFrequency),
            new NewAxis(TacticalModel.FOUL_HARDNESS_OPTIONS, "Medium", PersonalizedTactic::getFoulHardness, PersonalizedTactic::setFoulHardness),
            new NewAxis(TacticalModel.TEMPO_FRAGMENTATION_OPTIONS, "Normal", PersonalizedTactic::getTempoFragmentation, PersonalizedTactic::setTempoFragmentation),
            new NewAxis(TacticalModel.WIDE_PLAY_OPTIONS, "Shoot", PersonalizedTactic::getWidePlay, PersonalizedTactic::setWidePlay),
            new NewAxis(TacticalModel.TRANSITION_OPTIONS, "Balanced", PersonalizedTactic::getTransition, PersonalizedTactic::setTransition));

    /**
     * Advisor enumeration for ONE formation with <b>NO default values</b> on the 9 new axes — the AI's
     * committed style, explored. For each of the 900 base settings the new axes are first committed to
     * their greedy reference (via {@link #applyNewAxes}: best non-default line/press/instructions, width
     * by squad shape); then one variant per axis swaps in that axis's OTHER non-default value (others at
     * the reference). 1 reference + 9 single-axis alternatives = 10 fully-committed tactics per base ⇒
     * <b>9,000</b>. Because every row is committed, the distinct-points filter can never hide a real
     * tactic behind a neutral one. Needs the team's profile/ability/wideShare for the greedy reference.
     */
    public List<PersonalizedTactic> committedAdvisorTactics(TeamProfile mine, double ability, double wideShare) {
        java.util.Random rng = new java.util.Random(20260531L); // seeded ⇒ deterministic
        List<PersonalizedTactic> out = new ArrayList<>(900 * (1 + 9 + MULTI_AXIS_SAMPLES));
        for (PersonalizedTactic base : candidateTactics()) {
            applyNewAxes(base, mine, ability, wideShare);             // commit all 9 new axes (no defaults)
            PersonalizedTactic reference = copy(base);
            out.add(reference);                                      // the committed reference

            // Single-axis alternatives: each axis swapped to its OTHER non-default value (others = ref).
            for (NewAxis a : NEW_AXES) {
                String ref = a.getter().apply(base);
                for (String value : a.options()) {
                    if (value.equals(a.neutral()) || value.equals(ref)) continue;
                    a.setter().accept(base, value);
                    out.add(copy(base));
                    a.setter().accept(base, ref);
                }
            }

            // Multi-axis committed combos: several axes flipped to a random non-default value at once,
            // so the TOP tactics differ in many axes — no shared "core" of 3-4 constant axes among them.
            for (int k = 0; k < MULTI_AXIS_SAMPLES; k++) {
                PersonalizedTactic v = copy(reference);
                for (NewAxis a : NEW_AXES) {
                    if (rng.nextDouble() < MULTI_AXIS_FLIP_PROB) {
                        List<String> committed = nonNeutralValues(a);
                        a.setter().accept(v, committed.get(rng.nextInt(committed.size())));
                    }
                }
                out.add(v);
            }
        }
        return out;
    }

    /** How many random multi-axis committed combos to emit per base setting, and the per-axis flip
     *  probability — together they control how diverse the top tactics are. */
    private static final int MULTI_AXIS_SAMPLES = 12;
    private static final double MULTI_AXIS_FLIP_PROB = 0.55;

    /** An axis's values minus its neutral default (the committed choices). */
    private static List<String> nonNeutralValues(NewAxis a) {
        List<String> out = new ArrayList<>(a.options().size());
        for (String o : a.options()) if (!o.equals(a.neutral())) out.add(o);
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
