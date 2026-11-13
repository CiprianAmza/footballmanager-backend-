package com.footballmanagergamesimulator.service;

/**
 * Transfer points (design §3.5, decided 2026-11-12): what an offer with extras is worth to the selling club, in
 * money now. Cash now counts in full; each extra counts by a weight that depends on the club's situation.
 *
 * <table>
 *   <tr><th>Situation</th><th>Instalment</th><th>Sell-on</th><th>Buy-back clause</th></tr>
 *   <tr><td>In trouble (negative budget or relegated)</td><td>50%</td><td>10% × % × real price</td><td>0</td></tr>
 *   <tr><td>Stable</td><td>90%</td><td>30% × % × real price</td><td>5% of real price</td></tr>
 *   <tr><td>Stable, losing a starter (top 11)</td><td>90%</td><td>20% × % × real price</td><td>15% of real price</td></tr>
 * </table>
 *
 * <p>Performance bonuses (goals in a season, league title; {@link TransferBonusOdds}) count like instalments,
 * times the club's estimate of the chance they are paid.
 */
public final class TransferPoints {

    public static final int MAX_INSTALMENTS = 3;
    public static final int MAX_SELL_ON_PERCENT = 50;
    /** Seasons during which the selling club may take the player back. */
    public static final int BUY_BACK_SEASONS = 2;

    public enum Situation {
        TROUBLED(0.50, 0.10, 0.00),
        STABLE(0.90, 0.30, 0.05),
        LOSING_STARTER(0.90, 0.20, 0.15);

        final double instalmentWeight;
        final double sellOnWeight;
        final double buyBackWeight;

        Situation(double instalmentWeight, double sellOnWeight, double buyBackWeight) {
            this.instalmentWeight = instalmentWeight;
            this.sellOnWeight = sellOnWeight;
            this.buyBackWeight = buyBackWeight;
        }
    }

    /**
     * What I offer: cash now, then equal instalments at the start of the next seasons, the extras, and two
     * performance bonuses (a sum when the player scores {@code goalsBonusTarget} goals in a season for me; a sum
     * when I win the league), each 0 when not offered.
     */
    public record Offer(long upfront, int instalments, long instalmentAmount, int sellOnPercent, long buyBackPrice,
                        int goalsBonusTarget, long goalsBonusAmount, long titleBonusAmount) {
        public Offer(long upfront, int instalments, long instalmentAmount, int sellOnPercent, long buyBackPrice) {
            this(upfront, instalments, instalmentAmount, sellOnPercent, buyBackPrice, 0, 0, 0);
        }

        public static Offer cash(long amount) {
            return new Offer(amount, 0, 0, 0, 0);
        }

        public boolean valid() {
            return upfront >= 0 && instalments >= 0 && instalments <= MAX_INSTALMENTS && instalmentAmount >= 0
                    && (instalments == 0 || instalmentAmount > 0) && sellOnPercent >= 0
                    && sellOnPercent <= MAX_SELL_ON_PERCENT && buyBackPrice >= 0 && upfront + instalments * instalmentAmount > 0
                    && goalsBonusAmount >= 0 && titleBonusAmount >= 0
                    && (goalsBonusAmount == 0 || (goalsBonusTarget >= 1 && goalsBonusTarget <= TransferBonusOdds.MAX_GOALS_TARGET));
        }

        public long total() {
            return upfront + (long) instalments * instalmentAmount;
        }
    }

    private TransferPoints() {
    }

    public static Situation situation(boolean clubInTrouble, int squadRank) {
        if (clubInTrouble) return Situation.TROUBLED;
        return squadRank <= TransferPricing.STARTERS ? Situation.LOSING_STARTER : Situation.STABLE;
    }

    public static long points(Offer offer, long realPrice, Situation situation) {
        return points(offer, realPrice, situation, 0, 0);
    }

    /** With the chances (0–1, over the bonus's seasons) that the goals and title bonuses are paid. */
    public static long points(Offer offer, long realPrice, Situation situation, double goalsChance, double titleChance) {
        double points = offer.upfront()
                + situation.instalmentWeight * offer.instalments() * offer.instalmentAmount()
                + situation.instalmentWeight * (offer.goalsBonusAmount() * goalsChance + offer.titleBonusAmount() * titleChance)
                + situation.sellOnWeight * offer.sellOnPercent() / 100.0 * realPrice
                + (offer.buyBackPrice() > 0 ? situation.buyBackWeight * realPrice : 0);
        return Math.round(points);
    }
}
