package com.footballmanagergamesimulator.transfermarket;

import com.footballmanagergamesimulator.model.Human;

import java.util.Comparator;

/**
 * Sells high and shops in the bargain bin: cashes in its most valuable starters,
 * then rebuilds with whoever is cheap. The widest {@link #stepUpGap()} of the paid
 * strategies — it will take a player well below its own bar if the price is right.
 */
public class BuyFreeSellHighTransferStrategy extends AbstractTransferStrategy {

  @Override
  protected SellSource sellSource() {
    return SellSource.STARTERS;
  }

  @Override
  protected Comparator<Human> sellOrder() {
    return Comparator.comparingLong(Human::getTransferValue).reversed();
  }

  @Override
  protected int maxBuyAge() {
    return 40;
  }

  @Override
  protected double stepUpGap() {
    return 70;
  }

  @Override
  protected boolean protectsStarters() {
    return false; // sell-high means selling the players it relies on
  }

  @Override
  protected double depthTolerance() {
    return 50; // bargain hunting tolerates a clear drop
  }
}
