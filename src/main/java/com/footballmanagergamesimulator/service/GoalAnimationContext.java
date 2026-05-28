package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.frontend.GoalAnimationData;
import com.footballmanagergamesimulator.frontend.GoalAnimationData.AnimationPlayer;
import com.footballmanagergamesimulator.frontend.GoalAnimationData.TeamKit;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared infrastructure for the goal-animation engine: kit attachment, per-match
 * stoppage-time stash, eleven-vs-eleven roster selection, and math primitives.
 *
 * <p>The three scenario-specific generators ({@link GoalAnimationOpenPlayGenerator},
 * {@link GoalAnimationPenaltyGenerator}, {@link GoalAnimationFreeKickGenerator})
 * all inject this; {@link GoalAnimationService} delegates the public lifecycle
 * methods ({@link #setMatchStoppage}, {@link #clearMatchStoppage}) here.
 */
@Service
public class GoalAnimationContext {

    /** Standard animation length used by every scenario. Keyframe table + ball
     *  flight timings in the open-play generator are tuned around 150. */
    public static final int TOTAL_FRAMES = 150;

    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamKitResolver teamKitResolver;

    /** Per-match stoppage time stash. Set by LiveMatchSimulationService at the
     *  start of a match (via {@link #setMatchStoppage}) and read by the three
     *  generate*() methods for their mirror logic. Reset to 0 between matches. */
    private final ThreadLocal<Integer> firstHalfStoppageTl = ThreadLocal.withInitial(() -> 0);

    /** Tell the animation service how many minutes of first-half stoppage this
     *  match has. Anything generated until {@link #clearMatchStoppage} treats
     *  minutes ≤ {@code 45 + firstHalfStoppage} as first half. */
    public void setMatchStoppage(int firstHalfStoppage) {
        firstHalfStoppageTl.set(Math.max(0, firstHalfStoppage));
    }

    public void clearMatchStoppage() {
        firstHalfStoppageTl.remove();
    }

    public int firstHalfStoppage() {
        return firstHalfStoppageTl.get();
    }

    /** Populate scoring + defending kits on the result so the frontend can color
     *  players per-team instead of falling back to its hard-coded blue/red defaults.
     *  Safe to call with null data (no-op) so callers don't need to null-check. */
    public void attachKits(GoalAnimationData data, long scoringTeamId, long defendingTeamId) {
        if (data == null) return;
        // Propagate the match-level stoppage flag onto the result so the
        // frontend can format minute display correctly ("45+2'" not "47'").
        data.setFirstHalfStoppage(firstHalfStoppage());
        Team scoring = teamRepository.findById(scoringTeamId).orElse(null);
        Team defending = teamRepository.findById(defendingTeamId).orElse(null);
        if (scoring == null || defending == null) return;
        TeamKit[] kits = teamKitResolver.resolveKits(scoring, defending);
        data.setScoringTeamKit(kits[0]);
        data.setDefendingTeamKit(kits[1]);
    }

    // ==================== STATIC HELPERS ====================

    /**
     * Choose 11 players for an animation: GK first, then any required must-includes
     * (scorer/assister), then top-rated outfielders. Skips backup GKs so the
     * positioning step doesn't double-place keepers on the goal line.
     */
    public static List<Human> selectEleven(List<Human> all, Human must1, Human must2) {
        List<Human> sorted = all.stream()
                .filter(h -> !h.isRetired())
                .sorted(Comparator.comparingDouble(Human::getRating).reversed())
                .collect(Collectors.toList());

        List<Human> result = new ArrayList<>();
        Set<Long> used = new HashSet<>();

        // GK first
        sorted.stream().filter(h -> "GK".equals(h.getPosition())).findFirst().ifPresent(gk -> {
            result.add(gk);
            used.add(gk.getId());
        });

        // Must-includes
        for (Human m : new Human[]{must1, must2}) {
            if (m != null && !used.contains(m.getId())) {
                result.add(m);
                used.add(m.getId());
            }
        }

        // Fill remaining by rating — skip backup goalkeepers since one is already
        // in the squad. Without this filter, a high-rated backup GK could be added
        // and assignPositions would put both at the same goal-area X, visually
        // looking like the team has two keepers (one of which "wanders forward"
        // because of patrol oscillation). Scorer/assister are always outfielders
        // (the simulation never picks GKs as attackers), so we can't get a surplus
        // GK via the must-include path either.
        for (Human p : sorted) {
            if (result.size() >= 11) break;
            if (used.contains(p.getId())) continue;
            if ("GK".equals(p.getPosition())) continue;
            result.add(p);
            used.add(p.getId());
        }
        return result;
    }

    /** Build the per-player animation info row. */
    public static AnimationPlayer toAnimPlayer(Human p, long teamId) {
        AnimationPlayer ap = new AnimationPlayer();
        ap.setPlayerId(p.getId());
        ap.setName(p.getName());
        ap.setShirtNumber(p.getShirtNumber());
        ap.setTeamId(teamId);
        ap.setPosition(p.getPosition());
        return ap;
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public static double easeInOut(double t) {
        return t * t * (3 - 2 * t); // Hermite smoothstep
    }

    public static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
