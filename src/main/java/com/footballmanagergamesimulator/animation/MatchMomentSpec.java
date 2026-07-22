package com.footballmanagergamesimulator.animation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable canonical truth consumed by the animation-only layer. */
public record MatchMomentSpec(
        String fixtureKey,
        int slotIndex,
        long planSeed,
        int generatorVersion,
        int minute,
        int firstHalfStoppage,
        long scoringTeamId,
        long defendingTeamId,
        long homeTeamId,
        AnimationPhase phase,
        AnimationOutcome outcome,
        long scorerId,
        Long assisterId,
        List<PlayerSnapshot> playersOnPitch,
        TacticalContext tacticalContext) {

    public MatchMomentSpec {
        new AnimationKey(fixtureKey, slotIndex);
        if (generatorVersion <= 0) throw new IllegalArgumentException("generatorVersion must be positive");
        if (minute < 1) throw new IllegalArgumentException("minute must be positive");
        if (firstHalfStoppage < 0) throw new IllegalArgumentException("stoppage must be non-negative");
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

    public List<PlayerSnapshot> attackers() {
        return playersOnPitch.stream().filter(p -> p.teamId() == scoringTeamId).toList();
    }

    public List<PlayerSnapshot> defenders() {
        return playersOnPitch.stream().filter(p -> p.teamId() == defendingTeamId).toList();
    }

    public PlayerSnapshot scorer() {
        return playersOnPitch.stream().filter(p -> p.playerId() == scorerId).findFirst().orElseThrow();
    }

    public PlayerSnapshot assister() {
        if (assisterId == null) return null;
        return playersOnPitch.stream().filter(p -> p.playerId() == assisterId).findFirst().orElseThrow();
    }

    public boolean firstHalf() {
        return minute <= 45 + firstHalfStoppage;
    }
}
