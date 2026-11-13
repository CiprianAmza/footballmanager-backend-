package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.AgentPersonality;
import com.footballmanagergamesimulator.model.AgentRelationEvent;
import com.footballmanagergamesimulator.service.ContractPoints.Offer;

/**
 * Round-based contract negotiation with a player's agent (design §3.3–§3.4). Pure rules, no state of its own.
 * <ul>
 *   <li>The agent wants {@code demandFactor(relation)} × the player's value (1.0 at relation 50,
 *       1.4 at the refusal threshold 10, 0.75 at 100, linear in between). Patience does not lower it.</li>
 *   <li>Rounds 1 … patience−1: I make an offer. Enough points → agreed. Under the personality's walk-away
 *       share of the player's value → the agent leaves the table. Otherwise he repeats his concrete demand.</li>
 *   <li>If round patience−1 ends without a deal, the agent puts his demand as a take-it-or-leave-it offer;
 *       the last round is only accept / reject.</li>
 * </ul>
 */
public final class AgentNegotiation {

    public static final double DEMAND_AT_THRESHOLD = 1.40;
    public static final double DEMAND_AT_BEST = 0.75;
    public static final double ABSOLUTE_WALK_AWAY = 0.50;
    /** A player without an agent negotiates for himself. */
    public static final int NO_AGENT_PATIENCE = 3;

    /** CANCELLED: the deal became impossible from outside (e.g. the player left the selling club). */
    /** REFUSED: the agent would not sit at the table at all (the talks end before round 1). */
    public enum Status { OPEN, FINAL_OFFER, AGREED, WALKED_AWAY, FINAL_REJECTED, CANCELLED, REFUSED }

    /** What is fixed for the whole negotiation. {@code personality} null = no agent. */
    public record Terms(long playerValue, long transferValue, AgentPersonality personality, int relation, int patience) {
        public int rounds() {
            return Math.max(3, patience);
        }
    }

    /**
     * The negotiation after a step. {@code round} is the round to play next; {@code agentOffer} is what the
     * agent has on the table; {@code event} is what the step does to the relation (null = nothing).
     */
    public record Step(Status status, int round, Offer agentOffer, AgentRelationEvent event) {
    }

    private AgentNegotiation() {
    }

    /** True while the talks go on (the day stays blocked). */
    public static boolean isOpen(Status status) {
        return status == Status.OPEN || status == Status.FINAL_OFFER;
    }

    /** Opening state: round 1, the agent's demand on the table. */
    public static Step start(Terms terms) {
        return new Step(Status.OPEN, 1, agentDemand(terms), null);
    }

    /** How much the agent wants, as a share of the player's value. */
    public static double demandFactor(int relation) {
        int r = Math.max(AgentRelationService.REFUSAL_THRESHOLD, Math.min(AgentRelationService.MAX, relation));
        int mid = AgentRelationService.START;
        if (r <= mid) {
            return 1.0 + (DEMAND_AT_THRESHOLD - 1.0) * (mid - r) / (double) (mid - AgentRelationService.REFUSAL_THRESHOLD);
        }
        return 1.0 - (1.0 - DEMAND_AT_BEST) * (r - mid) / (double) (AgentRelationService.MAX - mid);
    }

    /** Under this share of the player's value the agent leaves the table. */
    public static double walkAwayShare(AgentPersonality personality) {
        if (personality == null) return ABSOLUTE_WALK_AWAY;
        return switch (personality) {
            case PERSONAL_GAIN -> 0.80;
            case TURNOVER -> 0.70;
            case STABILITY -> 0.60;
        };
    }

    /**
     * The concrete contract the agent asks for, worth (by his own valuation) the demanded points:
     * Stability → 4 years; Personal gain → 2 years with a signing bonus worth 20% of the demand;
     * Turnover → 2 years with a release clause at the transfer value. The wage fills the rest.
     */
    public static Offer agentDemand(Terms terms) {
        long value = terms.playerValue();
        double target = demandFactor(terms.relation()) * value;
        AgentPersonality p = terms.personality();
        int years = p == AgentPersonality.STABILITY ? 4 : 2;
        long bonus = 0;
        long clause = 0;
        if (p == AgentPersonality.PERSONAL_GAIN) {
            bonus = Math.round(0.20 * target * 12 * years / ContractPoints.FAVOURITE_MULTIPLIER);
        }
        if (p == AgentPersonality.TURNOVER && terms.transferValue() > 0) {
            clause = terms.transferValue();
        }
        double extras = ContractPoints.offerPoints(new Offer(0, years, bonus, clause), value, terms.transferValue(), p);
        long wage = Math.max(0, (long) Math.ceil(target - extras));
        return new Offer(wage, years, bonus, clause);
    }

    /** I make an offer in an OPEN negotiation. */
    public static Step offer(Terms terms, Step current, Offer mine) {
        if (current.status() != Status.OPEN) {
            throw new IllegalStateException("No offer can be made when the negotiation is " + current.status());
        }
        double share = ContractPoints.share(mine, terms.playerValue(), terms.transferValue(), terms.personality());
        if (share + 1e-9 >= demandFactor(terms.relation())) {
            return new Step(Status.AGREED, current.round(), mine, AgentRelationEvent.CONTRACT_AGREED);
        }
        if (share < Math.max(ABSOLUTE_WALK_AWAY, walkAwayShare(terms.personality()))) {
            return new Step(Status.WALKED_AWAY, current.round(), current.agentOffer(), AgentRelationEvent.AGENT_WALKED_AWAY);
        }
        int next = current.round() + 1;
        Status status = next >= terms.rounds() ? Status.FINAL_OFFER : Status.OPEN;
        return new Step(status, next, current.agentOffer(), null);
    }

    /** I answer the take-it-or-leave-it offer in the last round. */
    public static Step answerFinalOffer(Step current, boolean accept) {
        if (current.status() != Status.FINAL_OFFER) {
            throw new IllegalStateException("There is no final offer when the negotiation is " + current.status());
        }
        return accept
                ? new Step(Status.AGREED, current.round(), current.agentOffer(), AgentRelationEvent.CONTRACT_AGREED)
                : new Step(Status.FINAL_REJECTED, current.round(), current.agentOffer(), AgentRelationEvent.FINAL_OFFER_REJECTED);
    }
}
