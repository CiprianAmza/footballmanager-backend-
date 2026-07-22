package com.footballmanagergamesimulator.animation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable canonical contract handed to the {@link AnimationDirector}.
 * Everything in here was decided by the canonical match engine (MatchPlan /
 * ContributionResolver) and is treated as truth: the animation layer may only
 * invent the visual representation, never alter score, side, minute, scorer,
 * assister, phase or outcome.
 *
 * @param fixtureKey        canonical fixture identity (see {@code MatchEvent.fixtureKey})
 * @param slotIndex         canonical slot index inside the plan; together with
 *                          {@code fixtureKey} it is the animation's identity
 * @param planSeed          the canonical {@code MatchPlan} seed
 * @param generatorVersion  animation generator version to render with
 * @param minute            exact canonical minute — never shifted, even when two
 *                          slots share the same displayed minute
 * @param firstHalfStoppage first-half stoppage minutes (half detection for mirroring)
 * @param scoringTeamId     the canonical scoring/attacking side of the moment
 * @param scorerId          the shooter; the canonical scorer when outcome is GOAL
 * @param assisterId        nullable; when present, must deliver the final pass
 * @param attackingPlayers  players of the attacking side currently on the pitch
 * @param defendingPlayers  players of the defending side currently on the pitch
 * @param tacticalContext   optional, cosmetic only
 */
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
        List<PlayerSnapshot> attackingPlayers,
        List<PlayerSnapshot> defendingPlayers,
        TacticalContext tacticalContext) {

    public MatchMomentSpec {
        if (fixtureKey == null || fixtureKey.isBlank())
            throw new IllegalArgumentException("fixtureKey is required");
        if (slotIndex < 0) throw new IllegalArgumentException("slotIndex must be >= 0");
        if (generatorVersion <= 0) throw new IllegalArgumentException("generatorVersion must be positive");
        if (minute < 1) throw new IllegalArgumentException("minute must be >= 1");
        if (firstHalfStoppage < 0)
            throw new IllegalArgumentException("firstHalfStoppage must be >= 0");
        if (scoringTeamId <= 0 || defendingTeamId <= 0 || homeTeamId <= 0)
            throw new IllegalArgumentException("team ids must be positive");
        if (scoringTeamId == defendingTeamId)
            throw new IllegalArgumentException("scoring and defending teams must differ");
        if (homeTeamId != scoringTeamId && homeTeamId != defendingTeamId)
            throw new IllegalArgumentException("homeTeamId must identify one of the two teams");
        if (phase == null) throw new IllegalArgumentException("phase is required");
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
        if (attackingPlayers == null || attackingPlayers.isEmpty())
            throw new IllegalArgumentException("attacking snapshot is empty");
        if (defendingPlayers == null || defendingPlayers.isEmpty())
            throw new IllegalArgumentException("defending snapshot is empty");
        if (attackingPlayers.size() > 11 || defendingPlayers.size() > 11)
            throw new IllegalArgumentException("a side cannot field more than 11 players");

        Set<Long> ids = new HashSet<>();
        for (PlayerSnapshot p : attackingPlayers) {
            if (p == null) {
                throw new IllegalArgumentException("attacking snapshot contains null");
            }
            if (!ids.add(p.playerId())) {
                throw new IllegalArgumentException("duplicate playerId " + p.playerId());
            }
        }
        for (PlayerSnapshot p : defendingPlayers) {
            if (p == null) {
                throw new IllegalArgumentException("defending snapshot contains null");
            }
            if (!ids.add(p.playerId())) {
                throw new IllegalArgumentException("duplicate playerId " + p.playerId());
            }
        }
        attackingPlayers = List.copyOf(attackingPlayers);
        defendingPlayers = List.copyOf(defendingPlayers);

        if (attacker(attackingPlayers, scorerId) == null)
            throw new IllegalArgumentException("scorer " + scorerId + " not in attacking snapshot");
        if (assisterId != null) {
            if (assisterId == scorerId)
                throw new IllegalArgumentException("assister cannot equal scorer");
            if (attacker(attackingPlayers, assisterId) == null)
                throw new IllegalArgumentException("assister " + assisterId + " not in attacking snapshot");
        }
    }

    private static PlayerSnapshot attacker(List<PlayerSnapshot> list, long id) {
        for (PlayerSnapshot p : list) if (p.playerId() == id) return p;
        return null;
    }

    public PlayerSnapshot scorer() {
        return attacker(attackingPlayers, scorerId);
    }

    public PlayerSnapshot assister() {
        return assisterId == null ? null : attacker(attackingPlayers, assisterId);
    }

    /** True while the canonical minute falls in the first half (incl. stoppage). */
    public boolean isFirstHalf() {
        return minute <= 45 + Math.max(0, firstHalfStoppage);
    }
}
