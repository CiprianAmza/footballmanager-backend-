package com.footballmanagergamesimulator.transfermarket;

import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.service.TacticService;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One club's squad split the way the match engine sees it: the best XI it would
 * actually field, everybody else, and the incumbent strength of every base
 * position.
 *
 * <p>Built from {@code MatchRoundSimulator.startersFor} so transfer planning and
 * matchday can never disagree about who is a starter. This is the single input
 * the AI transfer market reasons about:
 *
 * <ul>
 *   <li>{@link #starters()} / {@link #reserves()} — the two sets each strategy
 *       draws its sale list from;</li>
 *   <li>{@link #incumbentRating(String)} — the effective rating a signing must
 *       beat to become a starter at that position;</li>
 *   <li>{@link #xiAverage()} — the club's overall level, used to rank which
 *       positions are dragging the XI down and to judge step-up bench moves.</li>
 * </ul>
 *
 * <p><b>Familiarity is deliberately asymmetric.</b> The incumbent is discounted
 * by how well he fits the slot he actually occupies ({@code rating × familiarity}),
 * so a club forced to improvise at a position shows a lower bar there and becomes
 * an easier buyer. Incoming candidates are never discounted — positions are
 * collapsed permissively via {@link TacticService#getBasePosition} for market
 * indexing, so an AMC counts as a full-value MC. Familiarity may lower a bar or
 * break a tie; it must never reject anybody.
 */
public final class SquadDepthChart {

    /** Every base position the market indexes on. */
    public static final List<String> BASE_POSITIONS =
            List.of("GK", "DL", "DC", "DR", "ML", "MC", "MR", "ST");

    private final long teamId;
    private final List<Human> starters;
    private final Map<Long, String> slotByStarterId;
    private final List<Human> reserves;
    private final Map<String, Double> incumbentByPosition;
    private final Map<String, Integer> squadCountByPosition;
    private final double xiAverage;
    /** Kept so a derived chart can value promoted reserves the same way. */
    private final FamiliarityResolver familiarity;

    private SquadDepthChart(long teamId,
                            List<Human> starters,
                            Map<Long, String> slotByStarterId,
                            List<Human> reserves,
                            Map<String, Double> incumbentByPosition,
                            Map<String, Integer> squadCountByPosition,
                            double xiAverage,
                            FamiliarityResolver familiarity) {
        this.teamId = teamId;
        this.starters = List.copyOf(starters);
        this.slotByStarterId = Map.copyOf(slotByStarterId);
        this.reserves = List.copyOf(reserves);
        this.incumbentByPosition = Map.copyOf(incumbentByPosition);
        this.squadCountByPosition = Map.copyOf(squadCountByPosition);
        this.xiAverage = xiAverage;
        this.familiarity = familiarity;
    }

    /**
     * @param squad      the club's players, retired already filtered out by the caller
     * @param starterXI  best-XI slots from the match engine (player id → used slot)
     * @param familiarity resolves {@code (naturalPosition, usedPosition) → 0..1}; the
     *                    engine's own matrix, never a transfer-local copy
     */
    public static SquadDepthChart build(long teamId,
                                        List<Human> squad,
                                        List<TacticController.StarterSlot> starterXI,
                                        FamiliarityResolver familiarity) {
        Map<Long, String> slotByPlayerId = new HashMap<>();
        for (TacticController.StarterSlot slot : starterXI) {
            PlayerView player = slot.player();
            if (player == null) continue;
            slotByPlayerId.put(player.getId(), slot.usedPosition());
        }

        List<Human> starters = new ArrayList<>();
        List<Human> reserves = new ArrayList<>();
        Map<String, Integer> squadCountByPosition = new HashMap<>();
        for (Human player : squad) {
            String basePosition = TacticService.getBasePosition(player.getPosition());
            if (basePosition != null) {
                squadCountByPosition.merge(basePosition, 1, Integer::sum);
            }
            if (slotByPlayerId.containsKey(player.getId())) starters.add(player);
            else reserves.add(player);
        }

        // The bar at a position is its WEAKEST starter — he is the one a signing
        // would displace, so he is the one the club has to beat.
        //
        // Taking the best starter instead hides exactly the problem the market
        // exists to fix. A 5-3-2 fields three centre-backs; if they rate 244, 235
        // and 35, the maximum says 244 and centre-back looks like the club's
        // strongest position, so it shops for strikers instead and the 35 plays
        // every week. The same collapse hid a 37-rated AMC behind a 138-rated MC,
        // because both occupy the MC base slot.
        //
        // Familiarity discounts the incumbent by how well he fits the slot he
        // actually occupies, so a club improvising at a position shows a lower bar.
        Map<String, Double> incumbentByPosition = new HashMap<>();
        double xiTotal = 0;
        for (Human starter : starters) {
            xiTotal += starter.getRating();
            String usedSlot = slotByPlayerId.get(starter.getId());
            String slotBase = TacticService.getBasePosition(usedSlot);
            if (slotBase == null) continue;
            double effective = starter.getRating() * fitFactor(starter.getPosition(), usedSlot, familiarity);
            incumbentByPosition.merge(slotBase, effective, Math::min);
        }
        double xiAverage = starters.isEmpty() ? 0 : xiTotal / starters.size();

        return new SquadDepthChart(teamId, starters, slotByPlayerId, reserves,
                incumbentByPosition, squadCountByPosition, xiAverage, familiarity);
    }

    public long teamId() {
        return teamId;
    }

    /** Players in the club's best XI. */
    public List<Human> starters() {
        return starters;
    }

    /** Everybody else in the squad. */
    public List<Human> reserves() {
        return reserves;
    }

    /** The whole squad — the pool a liquidity ("basic") strategy draws from. */
    public List<Human> wholeSquad() {
        List<Human> all = new ArrayList<>(starters.size() + reserves.size());
        all.addAll(starters);
        all.addAll(reserves);
        return all;
    }

    /** The formation slot this starter occupies, or null if he is not in the XI. */
    public String starterSlot(long playerId) {
        return slotByStarterId.get(playerId);
    }

    public Set<Long> starterIds() {
        Set<Long> ids = new HashSet<>();
        for (Human starter : starters) ids.add(starter.getId());
        return ids;
    }

    /**
     * How far below the club's own XI average a position must sit before it counts
     * as a weak link the club is obliged to fix. On the 1-300 rating scale a squad's
     * XI typically spans ~50 points, so a starter 40 below his own team's average is
     * a genuine liability rather than ordinary spread.
     */
    public static final double CRITICAL_DEFICIT = 40D;

    /**
     * Whether the club's chosen formation actually fields this base position.
     *
     * <p>A 5-3-2 fields no wingers. That is a tactical choice, not a hole, and it
     * must not be read as one — otherwise every club is permanently "missing" the
     * three or four positions its formation never uses, those phantom gaps outrank
     * its real problems, and it is blocked from ordinary business until it signs
     * players it will not pick.
     */
    public boolean isFielded(String basePosition) {
        return incumbentByPosition.containsKey(basePosition);
    }

    /**
     * Effective rating of the weakest starter at this base position, or 0 when the
     * formation does not field anybody there.
     */
    public double incumbentRating(String basePosition) {
        return incumbentByPosition.getOrDefault(basePosition, 0D);
    }

    /**
     * How far below the XI average this position sits — the buy-priority ranking.
     * Zero for positions the formation does not use: they are ordinary depth
     * opportunities, never priorities.
     */
    public double deficit(String basePosition) {
        if (!isFielded(basePosition)) return 0D;
        return xiAverage - incumbentRating(basePosition);
    }

    /**
     * A weak link the club must address this window: a player it actually fields who
     * drags the XI down by more than {@link #CRITICAL_DEFICIT}. Anything better than
     * what is there now is an improvement, so these slots ignore the roster cap and
     * are filled before the club may spend on positions it is comfortable in.
     */
    public boolean isCritical(String basePosition) {
        return isFielded(basePosition) && deficit(basePosition) >= CRITICAL_DEFICIT;
    }

    public double xiAverage() {
        return xiAverage;
    }

    public int squadCount(String basePosition) {
        return squadCountByPosition.getOrDefault(basePosition, 0);
    }

    public Map<String, Integer> squadCountsByBasePosition() {
        return squadCountByPosition;
    }

    /**
     * The same club, seen as it will be once the players it has just listed are gone.
     *
     * <p>The buy plan is drawn up after the sell list, and used to be computed against
     * the squad as it stood <i>before</i> those sales — so a club that had just put its
     * only good striker on the market did not see the hole it was about to have, and
     * shopped elsewhere. Selling is legitimate; going into the new season with nobody
     * to replace him is not. Against this chart the vacated slot shows its real
     * successor, usually turns critical, and the club is obliged to sign a replacement
     * before it may spend on anything discretionary.
     *
     * <p>Each vacated slot is filled by the best remaining player at that position;
     * a slot with nobody left reads as a hole. Promoted reserves are valued through
     * the same familiarity curve as the starters they replace.
     */
    public SquadDepthChart afterSales(Set<Long> soldPlayerIds) {
        if (soldPlayerIds == null || soldPlayerIds.isEmpty()) return this;

        Map<String, Integer> slotsPerPosition = new HashMap<>();
        for (String slot : slotByStarterId.values()) {
            String base = TacticService.getBasePosition(slot);
            if (base != null) slotsPerPosition.merge(base, 1, Integer::sum);
        }

        Map<String, List<Human>> availableByPosition = new HashMap<>();
        Map<String, Integer> newSquadCounts = new HashMap<>();
        for (Human player : wholeSquad()) {
            if (soldPlayerIds.contains(player.getId())) continue;
            String base = TacticService.getBasePosition(player.getPosition());
            if (base == null) continue;
            availableByPosition.computeIfAbsent(base, key -> new ArrayList<>()).add(player);
            newSquadCounts.merge(base, 1, Integer::sum);
        }
        availableByPosition.values().forEach(players ->
                players.sort(Comparator.comparingDouble(Human::getRating).reversed()));

        List<Human> newStarters = new ArrayList<>();
        Map<Long, String> newSlots = new HashMap<>();
        Map<String, Double> newIncumbents = new HashMap<>();
        double ratingTotal = 0;

        for (Map.Entry<String, Integer> entry : slotsPerPosition.entrySet()) {
            String position = entry.getKey();
            List<Human> pool = availableByPosition.getOrDefault(position, List.of());
            double weakest = Double.MAX_VALUE;
            for (int slot = 0; slot < entry.getValue(); slot++) {
                if (slot >= pool.size()) {
                    weakest = 0D; // a slot nobody is left to fill
                    continue;
                }
                Human promoted = pool.get(slot);
                newStarters.add(promoted);
                newSlots.put(promoted.getId(), position);
                ratingTotal += promoted.getRating();
                weakest = Math.min(weakest,
                        promoted.getRating() * fitFactor(promoted.getPosition(), position, familiarity));
            }
            newIncumbents.put(position, weakest == Double.MAX_VALUE ? 0D : weakest);
        }

        Set<Long> newStarterIds = new HashSet<>();
        for (Human starter : newStarters) newStarterIds.add(starter.getId());
        List<Human> newReserves = new ArrayList<>();
        for (Human player : wholeSquad()) {
            if (soldPlayerIds.contains(player.getId())) continue;
            if (!newStarterIds.contains(player.getId())) newReserves.add(player);
        }

        return new SquadDepthChart(teamId, newStarters, newSlots, newReserves,
                newIncumbents, newSquadCounts,
                newStarters.isEmpty() ? 0 : ratingTotal / newStarters.size(),
                familiarity);
    }

    /**
     * 1.0 when the player's natural position and the slot collapse to the same base —
     * the market treats those as interchangeable, which is the whole point of the
     * collapse. Otherwise the engine's own familiarity curve applies.
     */
    private static double fitFactor(String naturalPosition, String usedSlot, FamiliarityResolver familiarity) {
        String naturalBase = TacticService.getBasePosition(naturalPosition);
        String slotBase = TacticService.getBasePosition(usedSlot);
        if (naturalBase != null && naturalBase.equals(slotBase)) return 1.0;
        return familiarity.resolve(naturalPosition, usedSlot);
    }

    /** Engine-owned position familiarity, injected so this class holds no copy of it. */
    @FunctionalInterface
    public interface FamiliarityResolver {
        double resolve(String naturalPosition, String usedPosition);
    }
}
