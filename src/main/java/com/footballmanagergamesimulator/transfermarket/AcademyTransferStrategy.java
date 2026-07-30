package com.footballmanagergamesimulator.transfermarket;

import com.footballmanagergamesimulator.model.Human;

import java.util.Comparator;

/**
 * Develops its own players and cashes in on them: sells from the XI, best first.
 *
 * <p>It does not compete in the paid market — {@link #spendingCap()} is 0 — but it
 * is no longer barred from buying outright. Real academy clubs still sign free
 * agents to cover positions the youth intake missed, so it takes one or two
 * released players rather than fielding juniors everywhere.
 */
public class AcademyTransferStrategy extends AbstractTransferStrategy {

  @Override
  protected SellSource sellSource() {
    return SellSource.STARTERS;
  }

  @Override
  protected Comparator<Human> sellOrder() {
    return Comparator.comparingDouble(Human::getRating).reversed();
  }

  @Override
  protected int maxBuyAge() {
    return 32; // an experienced free agent is worth more than a third-choice junior
  }

  @Override
  protected double stepUpGap() {
    return 60; // nothing is free but a free agent, so be generous about who fits
  }

  @Override
  protected boolean protectsStarters() {
    return false; // selling its best IS the model — the club lives off it
  }

  @Override
  protected double depthTolerance() {
    return 60; // a released player only has to be plausible cover
  }

  @Override
  protected long spendingCap() {
    return 0L; // free agents only
  }

  @Override
  protected int wantedPositionCount() {
    return random.nextInt(1, 3); // 1-2 gaps, not a full rebuild
  }
}
