package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.model.PlayerSeasonStat;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.repository.PlayerSeasonStatRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Analytics Faza 2 — accumulates deterministic synthetic per-player tallies across the
 * <b>real</b> matches a team plays, into {@link PlayerSeasonStat} rows scoped to
 * (player, competition, season).
 *
 * <p><b>Read-only w.r.t. scoring.</b> This runs <i>after</i> the scoreline is already
 * decided (called from the Scorer-persisting hooks). It only reads attributes + the
 * team's tactic and writes its own table; it never touches match value, so the Poisson
 * engine stays deterministic. The read-only tactic tools
 * ({@code TacticSimulationService}, {@code BestTacticService}) never reach those hooks,
 * so they never produce rows here.
 *
 * <p>The per-match tally for each metric reuses the shared {@link AnalyticsFormula}
 * (the same attribute→metric math Faza 1 projects with), then modulates the
 * defensive/pressing family by the team's tactic (pressing High → more pressures /
 * defensive actions, etc.). A start = 90 minutes, so the per-match tally equals the
 * per-90 value; accumulating then reading back via {@code ×90/minutes} recovers the
 * tactic-modulated average — consistent with Faza 1.
 */
@Service
public class PlayerMatchStatService {

    @Autowired PlayerSkillsRepository playerSkillsRepository;
    @Autowired PlayerSeasonStatRepository playerSeasonStatRepository;
    @Autowired MatchEngineConfig engineConfig;

    /** A volume of attempted passes per 90 for an all-20 player (scaled by Pass% attribute average). */
    private static final double BASE_PASS_VOLUME = 70.0;

    /**
     * Records ONE played match for every starter on {@code startingXI}: appearances+1,
     * minutes+90 and the per-match synthetic tally added to each accumulator. All upserts
     * are flushed in a single {@code saveAll}.
     *
     * @param tacticOrNull the team's PersonalizedTactic (pressing/defensiveLine/width are
     *                     read for modulation); neutral "Standard" behaviour when null.
     */
    public void recordRealMatchForTeam(long teamId, List<PlayerView> startingXI,
                                       PersonalizedTactic tacticOrNull,
                                       long competitionId, int seasonNumber) {
        if (startingXI == null || startingXI.isEmpty()) return;

        MatchEngineConfig.Analytics cfg = engineConfig.getAnalytics();
        String pressKey = pressingKey(tacticOrNull);
        double defActionMult = defensiveActionMultiplier(tacticOrNull);

        // Batch-load skills for all starters (1 query) and existing rows for this comp+season.
        List<Long> playerIds = new ArrayList<>(startingXI.size());
        for (PlayerView pv : startingXI) playerIds.add(pv.getId());

        Map<Long, PlayerSkills> skillsByPlayer = new HashMap<>();
        for (PlayerSkills ps : playerSkillsRepository.findAllByPlayerIdIn(playerIds)) {
            skillsByPlayer.put(ps.getPlayerId(), ps);
        }

        List<PlayerSeasonStat> toSave = new ArrayList<>(startingXI.size());
        for (PlayerView pv : startingXI) {
            long playerId = pv.getId();
            PlayerSkills skills = skillsByPlayer.get(playerId);

            PlayerSeasonStat row = playerSeasonStatRepository
                    .findByPlayerIdAndCompetitionIdAndSeasonNumber(playerId, competitionId, seasonNumber)
                    .orElseGet(() -> {
                        PlayerSeasonStat fresh = new PlayerSeasonStat();
                        fresh.setPlayerId(playerId);
                        fresh.setCompetitionId(competitionId);
                        fresh.setSeasonNumber(seasonNumber);
                        return fresh;
                    });

            row.setTeamId(teamId); // latest team the player turned out for
            row.setAppearances(row.getAppearances() + 1);
            row.setMinutes(row.getMinutes() + 90);

            // Per-match synthetic tally (= per-90 since a start is 90'), tactic-modulated.
            double defensiveActions = AnalyticsFormula.synthesize("Defensive Actions", skills, cfg, pressKey) * defActionMult;
            double pressures = AnalyticsFormula.synthesize("Pressures", skills, cfg, pressKey);
            double counterpressures = AnalyticsFormula.synthesize("Counterpressures", skills, cfg, pressKey);
            double tackles = AnalyticsFormula.synthesize("Pressure Regains", skills, cfg, pressKey) * defActionMult;
            double shots = AnalyticsFormula.synthesize("Expected Goals", skills, cfg, pressKey);
            double passPct = AnalyticsFormula.synthesize("Pass %", skills, cfg, pressKey); // 40..99
            double passesAttempted = BASE_PASS_VOLUME * (passPct / 100.0);
            double passesCompleted = passesAttempted * (passPct / 100.0);

            row.setDefensiveActions(row.getDefensiveActions() + defensiveActions);
            row.setPressures(row.getPressures() + pressures);
            row.setCounterpressures(row.getCounterpressures() + counterpressures);
            row.setTackles(row.getTackles() + tackles);
            row.setShots(row.getShots() + shots);
            row.setPassesAttempted(row.getPassesAttempted() + passesAttempted);
            row.setPassesCompleted(row.getPassesCompleted() + passesCompleted);

            toSave.add(row);
        }

        playerSeasonStatRepository.saveAll(toSave);
    }

    // ------------------------------------------------------------------
    // Tactic modulation
    // ------------------------------------------------------------------

    /** Pressing tactic → press-multiplier key understood by {@link MatchEngineConfig.Analytics}. */
    static String pressingKey(PersonalizedTactic tactic) {
        if (tactic == null || tactic.getPressing() == null) return "Standard";
        String p = tactic.getPressing().trim();
        if ("Low".equalsIgnoreCase(p) || "High".equalsIgnoreCase(p) || "Standard".equalsIgnoreCase(p)) {
            // normalize capitalization to the config keys
            return Character.toUpperCase(p.charAt(0)) + p.substring(1).toLowerCase();
        }
        return "Standard";
    }

    /**
     * Extra modulation for ground-defensive actions (tackles / defensive actions):
     * a High defensive line + High pressing wins the ball higher and more often →
     * more such actions; Deep + Low concedes them. Multiplicative, centered on 1.0.
     */
    static double defensiveActionMultiplier(PersonalizedTactic tactic) {
        if (tactic == null) return 1.0;
        double m = 1.0;
        String pressing = tactic.getPressing();
        if ("High".equalsIgnoreCase(pressing)) m *= 1.20;
        else if ("Low".equalsIgnoreCase(pressing)) m *= 0.82;

        String line = tactic.getDefensiveLine();
        if ("High".equalsIgnoreCase(line)) m *= 1.10;
        else if ("Deep".equalsIgnoreCase(line)) m *= 0.92;
        return m;
    }
}
