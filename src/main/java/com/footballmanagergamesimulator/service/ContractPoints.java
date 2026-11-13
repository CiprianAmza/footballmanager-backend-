package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.AgentPersonality;

import java.util.Comparator;
import java.util.List;

/**
 * Contract points (design §3.4): a common currency, in "monthly wage equivalent", to compare
 * what the player is worth with what an offer is worth.
 * <ul>
 *   <li><b>Player value</b> = the wage at his rank, by rating, in the offering club's squad.</li>
 *   <li><b>Offer value</b> = wage + signing bonus spread over the contract months
 *       + 5% of the demand per year beyond the first + 10% of the demand for a release clause
 *       no higher than the transfer value. The agent's personality doubles its favourite part
 *       (duration: Stability, bonus: Personal gain, low clause: Turnover).</li>
 * </ul>
 */
public final class ContractPoints {

    public static final double DURATION_SHARE_PER_YEAR = 0.05;
    public static final double LOW_CLAUSE_SHARE = 0.10;
    public static final double FAVOURITE_MULTIPLIER = 2.0;

    private ContractPoints() {
    }

    /** One squad member as seen by the wage hierarchy. */
    public record SquadMember(double rating, long wage) {
    }

    /** What the offer contains. {@code releaseClause} 0 = none. Wage is monthly. */
    public record Offer(long wage, int years, long signingBonus, long releaseClause) {
    }

    /**
     * The wage the player asks for in this squad: if r squad members are rated above him, he asks for the
     * (r+1)-th highest wage; below everyone, the lowest wage. Empty squad (or no wages): the rating-based wage.
     * The player himself must not be in {@code squad}.
     */
    public static long demandedWage(double rating, List<SquadMember> squad) {
        List<Long> wages = squad.stream().map(SquadMember::wage).filter(w -> w > 0)
                .sorted(Comparator.reverseOrder()).toList();
        if (wages.isEmpty()) return WageService.baseWage(rating);
        long above = squad.stream().filter(m -> m.rating() > rating).count();
        return wages.get((int) Math.min(above, wages.size() - 1));
    }

    /** The offer in contract points, as valued by an agent of this personality (null = no agent). */
    public static double offerPoints(Offer offer, long demandedWage, long transferValue, AgentPersonality personality) {
        int years = Math.max(1, offer.years());
        double bonus = (double) offer.signingBonus() / (12.0 * years);
        double duration = DURATION_SHARE_PER_YEAR * demandedWage * (years - 1);
        boolean lowClause = offer.releaseClause() > 0 && offer.releaseClause() <= transferValue;
        double clause = lowClause ? LOW_CLAUSE_SHARE * demandedWage : 0.0;
        if (personality == AgentPersonality.STABILITY) duration *= FAVOURITE_MULTIPLIER;
        if (personality == AgentPersonality.PERSONAL_GAIN) bonus *= FAVOURITE_MULTIPLIER;
        if (personality == AgentPersonality.TURNOVER) clause *= FAVOURITE_MULTIPLIER;
        return offer.wage() + bonus + duration + clause;
    }

    /** Offer points as a share of what the player is worth (1.0 = exactly his value). */
    public static double share(Offer offer, long demandedWage, long transferValue, AgentPersonality personality) {
        if (demandedWage <= 0) return Double.POSITIVE_INFINITY;
        return offerPoints(offer, demandedWage, transferValue, personality) / demandedWage;
    }
}
