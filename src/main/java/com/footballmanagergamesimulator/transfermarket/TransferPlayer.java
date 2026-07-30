package com.footballmanagergamesimulator.transfermarket;

import lombok.Data;

/** One slot in a club's buy plan: the position wanted and the bar a signing must clear. */
@Data
public class TransferPlayer {

  private String position;

  /**
   * Effective rating of the club's current starter at this position (0 when the
   * XI fields nobody there). A candidate who beats it walks into the XI — which
   * is simultaneously why the club wants him and why he accepts the move.
   *
   * <p>Was {@code minRating}: the old name described a floor, but the value has
   * always been the incumbent's strength, and treating it as a floor with a flat
   * tolerance is what stalled the market.
   */
  private double incumbentRating;

  /** How far this position drags the XI down — the buy-priority ranking. */
  private double deficit;

  /**
   * A weak link the club is obliged to fix ({@link SquadDepthChart#CRITICAL_DEFICIT}).
   * Critical slots ignore the roster cap and must be filled before the club may
   * spend on a position it is already comfortable in.
   */
  private boolean critical;
}
