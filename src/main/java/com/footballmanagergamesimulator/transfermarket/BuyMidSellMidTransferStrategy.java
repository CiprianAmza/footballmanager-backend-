package com.footballmanagergamesimulator.transfermarket;

import com.footballmanagergamesimulator.model.Human;

import java.util.Comparator;

/**
 * The club without a plan — and the market's liquidity provider.
 *
 * <p>Its job is <b>not</b> to trade mid-quality players; it is to make sure as many
 * clubs as possible trade at all. So it draws from the whole squad at random rather
 * than skimming the XI or clearing the bench, and it is the most permissive buyer:
 * every acceptance path open, the widest step-up tolerance, no age limit. Expect it
 * to say yes more often than any other strategy.
 *
 * <p>Because the pick is random, its sold set tracks the squad mean <i>in
 * expectation</i> — that is a statistical property over many draws, not a per-window
 * guarantee, and assertions should be written accordingly.
 */
public class BuyMidSellMidTransferStrategy extends AbstractTransferStrategy {

  @Override
  protected SellSource sellSource() {
    return SellSource.WHOLE_SQUAD;
  }

  @Override
  protected Comparator<Human> sellOrder() {
    return null; // shuffle — no preference is the point
  }

  @Override
  protected int maxBuyAge() {
    return 40;
  }

  @Override
  protected double stepUpGap() {
    return 80;
  }

  @Override
  protected double depthTolerance() {
    return 60; // the liquidity provider takes almost anyone useful
  }
}
