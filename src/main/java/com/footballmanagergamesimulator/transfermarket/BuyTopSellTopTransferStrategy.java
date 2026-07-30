package com.footballmanagergamesimulator.transfermarket;

import com.footballmanagergamesimulator.model.Human;

import java.util.Comparator;

/**
 * The elite club. Buys at the top of the market and will part with a key player —
 * but only when the money is genuinely large.
 *
 * <p>The distinction from {@link BuyFreeSellHighTransferStrategy}, which also sells
 * high, is what it does with the proceeds and how much it lets go. A sell-high club
 * runs a rolling clearout and rebuilds from bargains; this one sells at most one or
 * two players a window and replaces them with better. It is the profile of a side
 * that does not need to sell and therefore only does so at its own price.
 *
 * <p>There is no negotiation in this market — the fee is derived from the player —
 * so "only for a big offer" is expressed by listing sparingly and only from the top
 * of the value order: the one man whose price cannot be refused, not a shortlist.
 *
 * <p>On the buying side it is the most demanding profile in the game. The narrowest
 * {@link #depthTolerance()} of any strategy, because a club at this level has no use
 * for filler; and a small {@link #stepUpGap()}, because players accept a place on its
 * bench that they would refuse anywhere else.
 */
public class BuyTopSellTopTransferStrategy extends AbstractTransferStrategy {

  @Override
  protected SellSource sellSource() {
    return SellSource.STARTERS;
  }

  @Override
  protected Comparator<Human> sellOrder() {
    return Comparator.comparingLong(Human::getTransferValue).reversed();
  }

  @Override
  protected int listedForSaleCount() {
    return random.nextInt(1, 3); // 1-2: it sells at its price, it does not hold a sale
  }

  @Override
  protected boolean protectsStarters() {
    return false; // parting with a key man for a big fee is the whole point
  }

  @Override
  protected int maxBuyAge() {
    return 30; // prime years — it pays for what plays now
  }

  @Override
  protected double stepUpGap() {
    return 10; // its bench is a step up for almost anyone
  }

  @Override
  protected double depthTolerance() {
    return 15; // cover must be near-equal; no filler at this level
  }
}
