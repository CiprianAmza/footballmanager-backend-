package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.frontend.PlayerAnalyticsView;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSeasonStat;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSeasonStatRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Faza 1 player analytics — synthesizes StatsBomb-style per-90 "expected" metrics
 * from {@link PlayerSkills} attributes and ranks them into percentiles versus
 * same-position-group peers in the same (competition, season).
 *
 * <p>The match engine is Poisson/two-axis and records no positional or pressing
 * event data, so nothing here is measured — every metric is a deterministic
 * function of attributes (and a pressing-tactic multiplier hook). All weights,
 * bases, exponents, position groups and heatmap templates live in
 * {@link MatchEngineConfig.Analytics} (config-driven, shipped defaults).
 *
 * <p>Determinism: same inputs ⇒ same output, no randomness. Higher relevant
 * attributes ⇒ strictly higher metric value. Percentiles are in [0, 100].
 */
@Service
public class PlayerAnalyticsService {

    @Autowired ScorerRepository scorerRepository;
    @Autowired HumanRepository humanRepository;
    @Autowired PlayerSkillsRepository playerSkillsRepository;
    @Autowired PlayerSeasonStatRepository playerSeasonStatRepository;
    @Autowired MatchEngineConfig engineConfig;

    /** Neutral pressing key — Faza 1 has no per-team tactic context here; Faza 2 can supply the real key. */
    private static final String DEFAULT_PRESS_KEY = "Standard";

    public PlayerAnalyticsView getPlayerAnalytics(long playerId, long competitionId, int seasonNumber) {
        MatchEngineConfig.Analytics cfg = engineConfig.getAnalytics();

        Human player = humanRepository.findById(playerId).orElse(null);
        PlayerSkills skills = playerSkillsRepository.findPlayerSkillsByPlayerId(playerId).orElse(null);

        PlayerAnalyticsView view = new PlayerAnalyticsView();
        view.setPlayerId(playerId);
        view.setCompetitionId(competitionId);
        view.setSeasonNumber(seasonNumber);
        view.setPlayerName(player != null ? player.getName() : "Unknown");
        view.setOverall(player != null ? player.getRating() : 0);

        String position = resolvePosition(player, skills);
        String basePos = TacticService.getBasePosition(position);
        if (basePos == null) basePos = "MC";
        String group = cfg.positionGroup(basePos);
        view.setPosition(position);
        view.setPositionGroup(group);

        // --- Faza 2: prefer REAL accumulated stats when the player has enough appearances ---
        PlayerSeasonStat myStat = playerSeasonStatRepository
                .findByPlayerIdAndCompetitionIdAndSeasonNumber(playerId, competitionId, seasonNumber)
                .orElse(null);
        boolean useAccumulated = myStat != null
                && myStat.getAppearances() >= cfg.getMinAppearances()
                && myStat.getMinutes() > 0;
        view.setAccumulated(useAccumulated);

        if (useAccumulated) {
            view.setSampleAppearances(myStat.getAppearances());
            populateFromAccumulated(view, cfg, myStat, group, competitionId, seasonNumber);
            view.setHeatmap(buildHeatmap(cfg, group, skills));
            return view;
        }

        // --- Faza 1 fallback: project per-90 metrics from attributes ---
        int appearances = countAppearances(playerId, competitionId, seasonNumber);
        view.setSampleAppearances(appearances);

        Map<String, Double> myValues = new HashMap<>();
        for (String metric : cfg.metricNames()) {
            myValues.put(metric, synthesize(metric, skills, cfg, DEFAULT_PRESS_KEY));
        }

        // --- Build the same-position-group peer pool for this (competition, season) ---
        List<Double> dummyPeerCounter = new ArrayList<>();
        Map<String, List<Double>> peerValuesByMetric = buildPeerPool(
                cfg, competitionId, seasonNumber, group, dummyPeerCounter);
        view.setPeerCount(dummyPeerCounter.size());

        List<PlayerAnalyticsView.MetricEntry> metrics = new ArrayList<>();
        for (String metric : cfg.metricNames()) {
            double value = myValues.get(metric);
            double pct = percentile(peerValuesByMetric.getOrDefault(metric, List.of()), value);
            metrics.add(new PlayerAnalyticsView.MetricEntry(metric, round2(value), round1(pct)));
        }
        view.setMetrics(metrics);

        // --- Synthetic heatmap from per-group template modulated by attributes ---
        view.setHeatmap(buildHeatmap(cfg, group, skills));

        return view;
    }

    // ------------------------------------------------------------------
    // Metric synthesis
    // ------------------------------------------------------------------

    /**
     * Synthetic per-90 for a metric = base * (weightedAttrAvg/20)^exponent, then a
     * pressing multiplier for the pressure family. Monotone increasing in every
     * referenced attribute.
     */
    double synthesize(String metric, PlayerSkills skills, MatchEngineConfig.Analytics cfg, String pressKey) {
        return AnalyticsFormula.synthesize(metric, skills, cfg, pressKey);
    }

    // ------------------------------------------------------------------
    // Faza 2: accumulated read-back
    // ------------------------------------------------------------------

    /**
     * Computes per-90 metrics from the player's REAL accumulated {@link PlayerSeasonStat}
     * sums ({@code sum * 90 / minutes}) and ranks them into percentiles versus all peers
     * in the same position-group who also have accumulated stats (>= minAppearances) in
     * this (competition, season).
     */
    private void populateFromAccumulated(PlayerAnalyticsView view, MatchEngineConfig.Analytics cfg,
                                         PlayerSeasonStat myStat, String group,
                                         long competitionId, int seasonNumber) {
        Map<String, Double> myValues = perNinety(cfg, myStat);

        // Peer pool: same position-group accumulated stats with enough appearances.
        List<PlayerSeasonStat> all = playerSeasonStatRepository
                .findAllByCompetitionIdAndSeasonNumber(competitionId, seasonNumber);
        List<Long> candidateIds = new ArrayList<>();
        for (PlayerSeasonStat s : all) {
            if (s.getAppearances() >= cfg.getMinAppearances() && s.getMinutes() > 0) {
                candidateIds.add(s.getPlayerId());
            }
        }
        Map<Long, String> groupByPlayer = positionGroups(cfg, candidateIds);

        Map<String, List<Double>> peerValuesByMetric = new HashMap<>();
        for (String metric : cfg.metricNames()) peerValuesByMetric.put(metric, new ArrayList<>());
        int peerCount = 0;
        for (PlayerSeasonStat s : all) {
            if (s.getAppearances() < cfg.getMinAppearances() || s.getMinutes() <= 0) continue;
            if (!group.equals(groupByPlayer.getOrDefault(s.getPlayerId(), "MID"))) continue;
            peerCount++;
            Map<String, Double> peerVals = perNinety(cfg, s);
            for (String metric : cfg.metricNames()) {
                peerValuesByMetric.get(metric).add(peerVals.get(metric));
            }
        }
        view.setPeerCount(peerCount);

        List<PlayerAnalyticsView.MetricEntry> metrics = new ArrayList<>();
        for (String metric : cfg.metricNames()) {
            double value = myValues.get(metric);
            double pct = percentile(peerValuesByMetric.getOrDefault(metric, List.of()), value);
            metrics.add(new PlayerAnalyticsView.MetricEntry(metric, round2(value), round1(pct)));
        }
        view.setMetrics(metrics);
    }

    /** Maps each displayed metric to its per-90 value derived from the accumulated sums. */
    private Map<String, Double> perNinety(MatchEngineConfig.Analytics cfg, PlayerSeasonStat s) {
        double minutes = Math.max(1.0, s.getMinutes());
        double f = 90.0 / minutes;
        // Regain ratios tie the *-Regains metrics to their parent accumulator (from default bases).
        double defRegainRatio = cfg.metricBase("Def Action Regains") / cfg.metricBase("Defensive Actions"); // ~0.57
        double cprRegainRatio = cfg.metricBase("Counterpressure Regains") / cfg.metricBase("Counterpressures"); // ~0.38

        double passPct = s.getPassesAttempted() > 0
                ? Math.max(40.0, Math.min(99.0, s.getPassesCompleted() / s.getPassesAttempted() * 100.0))
                : 0.0;

        Map<String, Double> m = new HashMap<>();
        m.put("Defensive Actions", s.getDefensiveActions() * f);
        m.put("Def Action Regains", s.getDefensiveActions() * f * defRegainRatio);
        m.put("Pressures", s.getPressures() * f);
        m.put("Pressure Regains", s.getTackles() * f);
        m.put("Counterpressures", s.getCounterpressures() * f);
        m.put("Counterpressure Regains", s.getCounterpressures() * f * cprRegainRatio);
        m.put("Pass %", passPct);
        m.put("Expected Goals", s.getShots() * f);
        return m;
    }

    /** Resolves each candidate player's position group via PlayerSkills (fallback Human → "MID"). */
    private Map<Long, String> positionGroups(MatchEngineConfig.Analytics cfg, List<Long> playerIds) {
        Map<Long, String> out = new HashMap<>();
        if (playerIds.isEmpty()) return out;
        Map<Long, PlayerSkills> skillsByPlayer = new HashMap<>();
        for (PlayerSkills ps : playerSkillsRepository.findAllByPlayerIdIn(playerIds)) {
            skillsByPlayer.put(ps.getPlayerId(), ps);
        }
        for (Long pid : playerIds) {
            PlayerSkills ps = skillsByPlayer.get(pid);
            String pos = ps != null ? ps.getPosition() : null;
            if (pos == null) {
                pos = humanRepository.findById(pid).map(Human::getPosition).orElse("MC");
            }
            String basePos = TacticService.getBasePosition(pos);
            if (basePos == null) basePos = "MC";
            out.put(pid, cfg.positionGroup(basePos));
        }
        return out;
    }

    private int attributeValue(PlayerSkills skills, String attrName) {
        return AnalyticsFormula.attributeValue(skills, attrName);
    }

    // ------------------------------------------------------------------
    // Peer pool + percentile
    // ------------------------------------------------------------------

    /**
     * Builds, for each metric, the synthesized values of every same-position-group
     * peer with >= minAppearances in this (competition, season). Adds one marker
     * per peer to {@code peerCounterOut} so the caller can report peerCount.
     */
    private Map<String, List<Double>> buildPeerPool(MatchEngineConfig.Analytics cfg,
                                                    long competitionId, int seasonNumber,
                                                    String group, List<Double> peerCounterOut) {
        Map<String, List<Double>> out = new HashMap<>();
        for (String metric : cfg.metricNames()) out.put(metric, new ArrayList<>());

        List<Scorer> rows = scorerRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, seasonNumber);

        // appearances per player in this comp+season (count unique non-placeholder match rows)
        Map<Long, Integer> appearancesByPlayer = new HashMap<>();
        for (Scorer s : rows) {
            if (s.getTeamScore() < 0 || s.getOpponentTeamId() < 0) continue; // drop placeholders
            appearancesByPlayer.merge(s.getPlayerId(), 1, Integer::sum);
        }

        int min = cfg.getMinAppearances();
        List<Long> peerIds = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : appearancesByPlayer.entrySet()) {
            if (e.getValue() >= min) peerIds.add(e.getKey());
        }
        if (peerIds.isEmpty()) return out;

        List<PlayerSkills> peerSkills = playerSkillsRepository.findAllByPlayerIdIn(peerIds);
        Map<Long, PlayerSkills> skillsByPlayer = new HashMap<>();
        for (PlayerSkills ps : peerSkills) skillsByPlayer.put(ps.getPlayerId(), ps);

        for (Long pid : peerIds) {
            PlayerSkills ps = skillsByPlayer.get(pid);
            String pos = ps != null ? ps.getPosition() : null;
            if (pos == null) {
                pos = humanRepository.findById(pid).map(Human::getPosition).orElse("MC");
            }
            String basePos = TacticService.getBasePosition(pos);
            if (basePos == null) basePos = "MC";
            if (!group.equals(cfg.positionGroup(basePos))) continue;

            peerCounterOut.add(1.0);
            for (String metric : cfg.metricNames()) {
                out.get(metric).add(synthesize(metric, ps, cfg, DEFAULT_PRESS_KEY));
            }
        }
        return out;
    }

    /**
     * Percentile of {@code value} within {@code pool} using the "mean rank" rule:
     * (countBelow + 0.5*countEqual) / n * 100. Empty pool ⇒ 50 (no peers to rank
     * against). Always in [0, 100].
     */
    double percentile(List<Double> pool, double value) {
        if (pool == null || pool.isEmpty()) return 50.0;
        int below = 0;
        int equal = 0;
        double eps = 1e-9;
        for (double p : pool) {
            if (p < value - eps) below++;
            else if (Math.abs(p - value) <= eps) equal++;
        }
        double pct = (below + 0.5 * equal) / pool.size() * 100.0;
        return Math.max(0.0, Math.min(100.0, pct));
    }

    // ------------------------------------------------------------------
    // Heatmap
    // ------------------------------------------------------------------

    /**
     * Per-group density template modulated deterministically by Work Rate / Pace /
     * Off The Ball: high work-rate + pace + off-the-ball push density higher (more
     * advanced columns) and broaden coverage. Output normalized so the peak cell ≈ 1.
     */
    double[][] buildHeatmap(MatchEngineConfig.Analytics cfg, String group, PlayerSkills skills) {
        double[][] template = cfg.heatmapTemplate(group);
        int rows = template.length;
        int cols = template[0].length;
        double[][] grid = new double[rows][cols];

        double workRate = attributeValue(skills, "Work Rate") / 20.0;
        double pace = attributeValue(skills, "Pace") / 20.0;
        double offTheBall = attributeValue(skills, "Off The Ball") / 20.0;

        // Forward bias: pace+offTheBall push activity toward attacking columns.
        double forwardBias = 0.5 * pace + 0.5 * offTheBall; // 0..1
        // Coverage: high work-rate spreads density (raises low cells); low work-rate concentrates it.
        double spread = 0.5 + 0.5 * workRate; // 0.5..1.0

        double max = 0.0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double colFrac = cols > 1 ? (double) c / (cols - 1) : 0.5; // 0 own → 1 opp
                double base = template[r][c];
                // shift toward forward columns
                double shifted = base * (1.0 + forwardBias * (colFrac - 0.5));
                // work-rate spreads activity: blend cell toward row mean
                double v = Math.pow(Math.max(0.0, shifted), spread);
                grid[r][c] = v;
                if (v > max) max = v;
            }
        }
        if (max > 0) {
            for (int r = 0; r < rows; r++)
                for (int c = 0; c < cols; c++)
                    grid[r][c] = round2(grid[r][c] / max);
        }
        return grid;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private int countAppearances(long playerId, long competitionId, int seasonNumber) {
        List<Scorer> rows = scorerRepository.findAllByCompetitionIdAndSeasonNumber(competitionId, seasonNumber);
        int n = 0;
        for (Scorer s : rows) {
            if (s.getPlayerId() != playerId) continue;
            if (s.getTeamScore() < 0 || s.getOpponentTeamId() < 0) continue;
            n++;
        }
        return n;
    }

    private String resolvePosition(Human player, PlayerSkills skills) {
        if (skills != null && skills.getPosition() != null && !skills.getPosition().isBlank()) {
            return skills.getPosition();
        }
        if (player != null && player.getPosition() != null) return player.getPosition();
        return "MC";
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
}
