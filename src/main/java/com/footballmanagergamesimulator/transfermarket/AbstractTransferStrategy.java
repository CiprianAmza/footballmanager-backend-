package com.footballmanagergamesimulator.transfermarket;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.service.TacticService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The one selling and buying algorithm every AI club runs. Subclasses supply only
 * what actually distinguishes a strategy: which set it sells from, how it orders
 * that set, how old it will buy, how permissive it is about bench signings, and
 * whether it may spend money at all.
 *
 * <p>Previously each of the five strategies carried its own copy of both methods —
 * three of them byte-identical — so the buy side had no strategy differentiation
 * beyond a single max-age constant.
 *
 * <h2>Selling</h2>
 * Candidates come from one of the depth-chart sets ({@link SellSource}), ordered by
 * {@link #sellOrder()}, and taken from the head. Whatever the strategy wants, the
 * per-position minimum is never breached: a club keeps its essential coverage even
 * if that means selling fewer players than it intended.
 *
 * <h2>Buying</h2>
 * Positions are ranked by how far each drags the XI down ({@code xiAverage -
 * incumbent}), so a hole (no starter at all) outranks a merely weak slot, and both
 * outrank a position that is already strong. Positions already at their roster cap
 * are skipped. This replaced a uniform random shuffle over free roster slots.
 */
public abstract class AbstractTransferStrategy implements TransferStrategy {

  /** Which half of the squad a strategy is willing to part with. */
  public enum SellSource {
    /** Skims the XI — sells the players it actually relies on. */
    STARTERS,
    /** Clears the bench — sells whoever is not in the XI. */
    RESERVES,
    /** No preference; draws from the whole squad. Keeps the market liquid. */
    WHOLE_SQUAD
  }

  protected Random random = new Random();

  @Override
  public void setRandom(Random random) {
    this.random = random;
  }

  // ---- strategy knobs -------------------------------------------------

  protected abstract SellSource sellSource();

  /**
   * Order applied to the sell source; the head is listed first. {@code null} means
   * shuffle — an un-opinionated club that simply moves whoever comes up.
   */
  protected abstract Comparator<Human> sellOrder();

  protected abstract int maxBuyAge();

  /**
   * How much better than the player the club must be before he accepts a bench
   * role. The PLAYER's side of a depth signing.
   */
  protected abstract double stepUpGap();

  /**
   * How far below the incumbent a signing may sit and still count as a credible
   * backup. The CLUB's side of a depth signing — an ambitious side wants near-equal
   * cover, a squad-filling one will take anyone vaguely useful. Never applies to a
   * genuine hole: beating a weak incumbent goes through the starter path instead.
   */
  protected abstract double depthTolerance();

  /**
   * Whether this club refuses a sale that would leave the vacated position a weak
   * link — no adequate replacement anywhere in the squad.
   *
   * <p>Off for the strategies whose whole business is cashing in on their best
   * players: blocking that would not protect them, it would delete them. On for
   * everyone else, because a club with no such plan has no reason to gut its own XI.
   *
   * <p>Note the positional minimum alone never caught this: it counts bodies, so a
   * club could sell its only decent striker while still owning two rated 63.
   */
  protected boolean protectsStarters() {
    return true;
  }

  /** Per-plan spending cap on top of the club budget. Academy overrides this to 0. */
  protected long spendingCap() {
    return Long.MAX_VALUE;
  }

  /** How many positions this club shops for. */
  protected int wantedPositionCount() {
    return random.nextInt(3, 5);
  }

  /** How many players it is willing to list. */
  protected int listedForSaleCount() {
    return random.nextInt(3, 6);
  }

  // ---- the shared algorithm -------------------------------------------

  @Override
  public List<PlayerTransferView> playersToSell(Team team,
                                                SquadDepthChart depthChart,
                                                Map<String, Integer> minimumPositionNeeded) {
    List<Human> source = switch (sellSource()) {
      case STARTERS -> new ArrayList<>(depthChart.starters());
      case RESERVES -> new ArrayList<>(depthChart.reserves());
      case WHOLE_SQUAD -> depthChart.wholeSquad();
    };

    Comparator<Human> order = sellOrder();
    if (order == null) Collections.shuffle(source, random);
    else source.sort(order);

    // Positional minimums are measured against the WHOLE squad, not the source
    // set — selling a reserve still counts against the club's cover at that spot.
    Map<String, Integer> remainingAtPosition = new HashMap<>(depthChart.squadCountsByBasePosition());

    // Who is left at each position, best first — so a sale can be judged on what
    // would actually replace the player, not just on how many bodies remain.
    Map<String, List<Human>> coverByPosition = new HashMap<>();
    for (Human player : depthChart.wholeSquad()) {
      String basePosition = TacticService.getBasePosition(player.getPosition());
      if (basePosition == null) continue;
      coverByPosition.computeIfAbsent(basePosition, key -> new ArrayList<>()).add(player);
    }
    coverByPosition.values().forEach(players ->
            players.sort(Comparator.comparingDouble(Human::getRating).reversed()));

    int wanted = listedForSaleCount();
    Set<Long> starterIds = depthChart.starterIds();
    List<PlayerTransferView> listed = new ArrayList<>();
    for (Human player : source) {
      if (listed.size() >= wanted) break;
      if (player.isWillNeverLeave()) continue;
      String basePosition = TacticService.getBasePosition(player.getPosition());
      if (basePosition == null) continue;
      int remaining = remainingAtPosition.getOrDefault(basePosition, 0);
      if (remaining <= minimumPositionNeeded.getOrDefault(basePosition, 0)) continue;

      List<Human> cover = coverByPosition.getOrDefault(basePosition, new ArrayList<>());
      if (protectsStarters() && starterIds.contains(player.getId())
              && leavesAWeakLink(player, cover, depthChart)) {
        continue;
      }

      cover.remove(player);
      remainingAtPosition.put(basePosition, remaining - 1);
      listed.add(toTransferView(team, player, starterIds.contains(player.getId())));
    }
    return listed;
  }

  /** True when nobody left at the position could hold it without dragging the XI down. */
  private static boolean leavesAWeakLink(Human leaving, List<Human> cover, SquadDepthChart depthChart) {
    double bestReplacement = cover.stream()
            .filter(candidate -> candidate.getId() != leaving.getId())
            .mapToDouble(Human::getRating)
            .max()
            .orElse(0D);
    return depthChart.xiAverage() - bestReplacement >= SquadDepthChart.CRITICAL_DEFICIT;
  }

  @Override
  public BuyPlanTransferView playersToBuy(Team team,
                                          SquadDepthChart depthChart,
                                          Map<String, Integer> maximumPositionsAllowed) {
    List<TransferPlayer> wanted = new ArrayList<>();
    for (String basePosition : SquadDepthChart.BASE_POSITIONS) {
      boolean critical = depthChart.isCritical(basePosition);
      int cap = maximumPositionsAllowed.getOrDefault(basePosition, Integer.MAX_VALUE);
      // The roster cap stops hoarding, but a weak link is a REPLACEMENT, not an
      // addition — a club with five centre-backs one of whom rates 35 is not
      // "saturated at centre-back", it has a hole with four spectators.
      if (depthChart.squadCount(basePosition) >= cap && !critical) continue;

      TransferPlayer slot = new TransferPlayer();
      slot.setPosition(basePosition);
      slot.setIncumbentRating(depthChart.incumbentRating(basePosition));
      slot.setDeficit(depthChart.deficit(basePosition));
      slot.setCritical(critical);
      wanted.add(slot);
    }

    // Weakest slot first: a hole (incumbent 0) always outranks a merely weak one.
    wanted.sort(Comparator.comparingDouble(TransferPlayer::getDeficit).reversed()
            .thenComparing(TransferPlayer::getPosition));

    // Every weak link goes into the plan, however many there are — fixing those is
    // not optional. Only the discretionary slots below them are rationed by the
    // strategy's appetite.
    List<TransferPlayer> positions = new ArrayList<>();
    int discretionaryBudget = wantedPositionCount();
    for (TransferPlayer slot : wanted) {
      if (slot.isCritical()) {
        positions.add(slot);
      } else if (discretionaryBudget > 0) {
        positions.add(slot);
        discretionaryBudget--;
      }
    }

    BuyPlanTransferView plan = new BuyPlanTransferView();
    plan.setPositions(positions);
    plan.setMaxAge(maxBuyAge());
    plan.setSpendingCap(spendingCap());
    plan.setXiAverage(depthChart.xiAverage());
    plan.setStepUpGap(stepUpGap());
    plan.setDepthTolerance(depthTolerance());
    plan.setTeamId(team.getId());
    return plan;
  }

  private static PlayerTransferView toTransferView(Team team, Human player, boolean starter) {
    return new PlayerTransferView(
            player.getId(),
            team.getId(),
            player.getRating(),
            TacticService.getBasePosition(player.getPosition()),
            player.getPosition(),
            player.getAge(),
            player.isWillNeverLeave(),
            starter);
  }
}
