package com.footballmanagergamesimulator.transfermarket;

import lombok.Data;

import java.util.List;

/** What one club wants from the window, ranked by which positions hurt it most. */
@Data
public class BuyPlanTransferView {

  /** Wanted slots, most deficient position first. */
  private List<TransferPlayer> positions;
  private int maxAge;

  /**
   * Per-plan spending cap, applied on top of the club's own transfer budget.
   * {@link Long#MAX_VALUE} means "only the club budget limits me"; Academy sets
   * it to 0 so it can only take free agents.
   */
  private long spendingCap = Long.MAX_VALUE;

  /**
   * Average rating of this club's best XI — its overall level. A player below the
   * bar at his position still accepts a bench role here when the club is this much
   * better than he is.
   */
  private double xiAverage;

  /**
   * How much better than the player the club must be before he will accept a bench
   * role here. This is the PLAYER's side of a depth signing.
   */
  private double stepUpGap;

  /**
   * How far below the incumbent a bench signing may sit and still be a credible
   * backup. This is the CLUB's side, and without it a depth signing has no lower
   * bound at all — the weaker the player, the more easily "the club is better than
   * him" holds, so a 231-rated side would happily sign a 99-rated keeper.
   */
  private double depthTolerance;

  private long teamId;
}
