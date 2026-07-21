package com.footballmanagergamesimulator.matchplan;

import com.footballmanagergamesimulator.model.MatchEvent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Executes a {@link MatchPlan} for an un-watched match: walks the goal slots in
 * minute order, resolves each against the players on the pitch at that minute,
 * and emits the canonical {@link MatchEvent} timeline. The live executor produces
 * the same events for a watched match; with the same seed and no manual subs the
 * two are identical.
 *
 * <p>Each slot is resolved with its own {@link Random} derived from the plan seed
 * and the slot index, so the scorer choice does not depend on how many cosmetic
 * events (misses, cards) a live run might interleave.
 */
@Service
public class InstantMatchExecutor {

    private static final long SLOT_SALT = 1_000_003L;

    private final ContributionResolver resolver;

    public InstantMatchExecutor(ContributionResolver resolver) {
        this.resolver = resolver;
    }

    /** Context needed to stamp {@link MatchEvent} rows (not part of the pure plan). */
    public record MatchContext(String fixtureKey, long competitionId, int seasonNumber, int roundNumber) {}

    /**
     * Resolve every goal slot and return the goal/assist events. Slots are
     * mutated with their resolved contributors (so callers can persist the plan
     * too). Card/substitution/cosmetic events are NOT produced here — they carry
     * no result meaning and belong to the presentation layer.
     */
    public List<MatchEvent> execute(MatchPlan plan, Lineup homeLineup, Lineup awayLineup, MatchContext ctx) {
        List<MatchEvent> events = new ArrayList<>();
        List<GoalSlot> slots = plan.getGoalSlots();

        for (GoalSlot slot : slots) {
            boolean home = slot.getTeamId() == plan.getHomeTeamId();
            Lineup lineup = home ? homeLineup : awayLineup;
            List<Contributor> onPitch = lineup.onPitchAt(slot.getMinute());

            // Per-slot RNG keyed by the persisted slotIndex, so a reload/refresh
            // resolves an unresolved slot the same way the first run would have.
            Random rng = new Random(plan.getSeed() * SLOT_SALT + slot.getSlotIndex());
            resolver.resolve(slot, onPitch, rng);
            if (!slot.isResolved() || slot.getScorerId() == null) continue;

            Contributor scorer = find(onPitch, slot.getScorerId());
            events.add(buildEvent(plan, ctx, slot.getSlotIndex(), MatchEvent.ORDER_GOAL,
                    slot.getMinute(), "goal",
                    slot.getScorerId(), scorer != null ? scorer.name() : "", slot.getTeamId(),
                    slot.getGoalType()));

            if (slot.getAssistId() != null) {
                Contributor assister = find(onPitch, slot.getAssistId());
                events.add(buildEvent(plan, ctx, slot.getSlotIndex(), MatchEvent.ORDER_ASSIST,
                        slot.getMinute(), "assist",
                        slot.getAssistId(), assister != null ? assister.name() : "", slot.getTeamId(),
                        "Assist"));
            }
        }
        return events;
    }

    private Contributor find(List<Contributor> players, long id) {
        for (Contributor c : players) if (c.playerId() == id) return c;
        return null;
    }

    private MatchEvent buildEvent(MatchPlan plan, MatchContext ctx, int slotIndex, int eventOrder,
                                  int minute, String type,
                                  long playerId, String playerName, long teamId, String details) {
        MatchEvent e = new MatchEvent();
        e.setFixtureKey(ctx.fixtureKey());
        e.setSlotIndex(slotIndex);
        e.setEventOrder(eventOrder);
        e.setCompetitionId(ctx.competitionId());
        e.setSeasonNumber(ctx.seasonNumber());
        e.setRoundNumber(ctx.roundNumber());
        e.setTeamId1(plan.getHomeTeamId());
        e.setTeamId2(plan.getAwayTeamId());
        e.setMinute(minute);
        e.setEventType(type);
        e.setPlayerId(playerId);
        e.setPlayerName(playerName);
        e.setTeamId(teamId);
        e.setDetails(details);
        return e;
    }
}
