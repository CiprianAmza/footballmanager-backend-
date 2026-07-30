package com.footballmanagergamesimulator.transfermarket;

import com.footballmanagergamesimulator.model.Human;

import java.util.Comparator;

/**
 * The ambitious club: clears its bench, worst first, and only signs genuine
 * upgrades. The narrowest {@link #stepUpGap()} — it has little interest in squad
 * filler, so most of its business is starters replacing starters.
 */
public class BuyTopSellWorstTransferStrategy extends AbstractTransferStrategy {

  @Override
  protected SellSource sellSource() {
    return SellSource.RESERVES;
  }

  @Override
  protected Comparator<Human> sellOrder() {
    return Comparator.comparingDouble(Human::getRating);
  }

  @Override
  protected int maxBuyAge() {
    return 40;
  }

  @Override
  protected double stepUpGap() {
    return 15;
  }

  @Override
  protected double depthTolerance() {
    return 20; // wants near-equal cover, not filler
  }
}
