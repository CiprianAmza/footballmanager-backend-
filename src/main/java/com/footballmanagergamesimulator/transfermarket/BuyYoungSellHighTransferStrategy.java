package com.footballmanagergamesimulator.transfermarket;

import com.footballmanagergamesimulator.model.Human;

import java.util.Comparator;

/** Trades on resale value: sells whoever is worth the most, buys only youth. */
public class BuyYoungSellHighTransferStrategy extends AbstractTransferStrategy {

  @Override
  protected SellSource sellSource() {
    // Anyone with a price, not just the XI. Drawing only from the starters made
    // an expensive prospect on the bench unsellable at any price, which is the
    // opposite of a sell-high club: it cashes in on value, wherever it sits.
    // Ordering by transfer value keeps the prime assets at the head regardless.
    return SellSource.WHOLE_SQUAD;
  }

  @Override
  protected Comparator<Human> sellOrder() {
    return Comparator.comparingLong(Human::getTransferValue).reversed();
  }

  @Override
  protected int maxBuyAge() {
    return 24;
  }

  @Override
  protected double stepUpGap() {
    return 45; // a promising youngster is worth a bench seat
  }

  @Override
  protected boolean protectsStarters() {
    return false; // sell-high means selling the players it relies on
  }

  @Override
  protected double depthTolerance() {
    return 35; // a prospect must already be close to the incumbent
  }
}
