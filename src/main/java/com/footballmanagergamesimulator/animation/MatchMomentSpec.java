package com.footballmanagergamesimulator.animation;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable canonical truth consumed by the animation-only layer. */
public record MatchMomentSpec(
        String fixtureKey,
        int slotIndex,
        long planSeed,
        int generatorVersion,
        int minute,
        int firstHalfStoppage,
        MatchPeriod period,
        long scoringTeamId,
        long defendingTeamId,
        long homeTeamId,
        AnimationPhase phase,
        AnimationOutcome outcome,
        long scorerId,
        Long assisterId,
        List<PlayerSnapshot> playersOnPitch,
        TacticalContext tacticalContext) {

    /**
     * Stable ordering of tactical positions. Participants are canonicalised by
     * (position rank, playerId) before any list-order-dependent consumption, so
     * that the same set of players in any input order produces the exact same
     * pattern, frames, fingerprint and recipe.
     */
    private static final Map<String, Integer> POSITION_RANK = Map.ofEntries(
            Map.entry("GK", 0),
            Map.entry("DL", 1), Map.entry("WBL", 2), Map.entry("DC", 3),
            Map.entry("DR", 4), Map.entry("WBR", 5),
            Map.entry("DM", 6), Map.entry("ML", 7), Map.entry("MC", 8), Map.entry("MR", 9),
            Map.entry("AML", 10), Map.entry("AMC", 11), Map.entry("AMR", 12), Map.entry("ST", 13));

    private static final Comparator<PlayerSnapshot> CANONICAL =
            Comparator.comparingInt((PlayerSnapshot p) -> POSITION_RANK.getOrDefault(p.tacticalPosition(), 99))
                    .thenComparingLong(PlayerSnapshot::playerId);

    public MatchMomentSpec {
        new AnimationKey(fixtureKey, slotIndex);
        if (generatorVersion <= 0) throw new IllegalArgumentException("generatorVersion must be positive");
        if (minute < 1) throw new IllegalArgumentException("minute must be positive");
        if (firstHalfStoppage < 0) throw new IllegalArgumentException("stoppage must be non-negative");
        // period may be null only for legacy version-1 recipes persisted before the explicit
        // period model; in that case direction falls back to the first-half derivation.
        if (scoringTeamId <= 0 || defendingTeamId <= 0 || homeTeamId <= 0)
            throw new IllegalArgumentException("team ids must be positive");
        if (scoringTeamId == defendingTeamId) throw new IllegalArgumentException("teams must differ");
        if (homeTeamId != scoringTeamId && homeTeamId != defendingTeamId)
            throw new IllegalArgumentException("home team must be one of the participants");
        if (phase == null || outcome == null) throw new IllegalArgumentException("phase/outcome are required");
        if (playersOnPitch == null || playersOnPitch.isEmpty())
            throw new IllegalArgumentException("on-pitch snapshot is required");

        Set<Long> ids = new HashSet<>();
        int attacking = 0;
        int defending = 0;
        boolean scorerPresent = false;
        boolean assisterPresent = assisterId == null;
        boolean defendingKeeperPresent = false;
        for (PlayerSnapshot player : playersOnPitch) {
            if (player == null) throw new IllegalArgumentException("snapshot contains null player");
            if (!ids.add(player.playerId())) throw new IllegalArgumentException("duplicate player " + player.playerId());
            if (player.teamId() == scoringTeamId) attacking++;
            else if (player.teamId() == defendingTeamId) {
                defending++;
                if (player.goalkeeper()) defendingKeeperPresent = true;
            }
            else throw new IllegalArgumentException("snapshot contains a third-team player " + player.playerId());
            if (player.playerId() == scorerId && player.teamId() == scoringTeamId) scorerPresent = true;
            if (assisterId != null && player.playerId() == assisterId && player.teamId() == scoringTeamId)
                assisterPresent = true;
        }
        if (attacking < 1 || defending < 1 || attacking > 11 || defending > 11)
            throw new IllegalArgumentException("each team must field 1..11 snapshot players");
        if (!scorerPresent) throw new IllegalArgumentException("canonical scorer/shooter is not attacking");
        if (!defendingKeeperPresent) throw new IllegalArgumentException("defending snapshot requires a goalkeeper");
        if (assisterId != null && assisterId == scorerId) throw new IllegalArgumentException("assist cannot equal scorer");
        if (!assisterPresent) throw new IllegalArgumentException("canonical assist is not attacking");
        playersOnPitch = List.copyOf(playersOnPitch);
    }

    public AnimationKey key() {
        return new AnimationKey(fixtureKey, slotIndex);
    }

    /** Attackers in canonical (position rank, playerId) order, independent of snapshot input order. */
    public List<PlayerSnapshot> attackers() {
        return playersOnPitch.stream().filter(p -> p.teamId() == scoringTeamId).sorted(CANONICAL).toList();
    }

    /** Defenders in canonical (position rank, playerId) order, independent of snapshot input order. */
    public List<PlayerSnapshot> defenders() {
        return playersOnPitch.stream().filter(p -> p.teamId() == defendingTeamId).sorted(CANONICAL).toList();
    }

    public PlayerSnapshot scorer() {
        return playersOnPitch.stream().filter(p -> p.playerId() == scorerId).findFirst().orElseThrow();
    }

    public PlayerSnapshot assister() {
        if (assisterId == null) return null;
        return playersOnPitch.stream().filter(p -> p.playerId() == assisterId).findFirst().orElseThrow();
    }

    /** True while this moment is in the first half (legacy direction derivation for null period). */
    public boolean firstHalf() {
        return minute <= 45 + firstHalfStoppage;
    }

    /**
     * Canonical attack direction of the home team. Derived from the explicit period
     * when present; legacy version-1 recipes without a period fall back to the
     * first-half derivation so their frozen replays regenerate unchanged.
     */
    public boolean homeAttacksRight() {
        return period != null ? period.homeAttacksRight() : firstHalf();
    }

    /** Whether the scoring team attacks the right-hand goal in this period. */
    public boolean scoringTeamAttacksRight() {
        boolean scoringIsHome = scoringTeamId == homeTeamId;
        return scoringIsHome == homeAttacksRight();
    }
}
