package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.util.TransferWindow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Spreads the AI market over the days of the window (design §3.6, decided 2026-11-12): the window's transfers are
 * still computed all at once, by the existing algorithm, but each is carried out on its own day. Meanwhile the
 * player is "wanted"; if he is gone or no longer free to move on the day, the planned move is dropped.
 */
@Service
public class PlannedTransferService {

    public static final int DAYS_PER_SEASON = 365;

    @Autowired PlannedTransferRepository plannedTransferRepository;
    @Autowired HumanRepository humanRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired TransferRepository transferRepository;
    @Autowired LoanRepository loanRepository;
    @Autowired FinanceService financeService;
    @Autowired GameCalendarRepository gameCalendarRepository;
    @Autowired ManagerInboxRepository managerInboxRepository;
    @Autowired TransferOfferRepository transferOfferRepository;

    /** Each answer from the selling club or round with the agent pushes the AI's planned move back this much. */
    public static final int POSTPONE_DAYS = 3;
    /** A manager's bid still in play for a player. */
    static final List<String> LIVE_BIDS = List.of(TransferOffer.STATUS_TALKS, TransferOffer.STATUS_FINAL_OFFER,
            TransferOffer.STATUS_FEE_AGREED);
    @Autowired ContractSignature contractSignature;
    @Autowired com.footballmanagergamesimulator.user.UserContext userContext;
    @Autowired @Lazy MatchSimulationOrchestrator matchSimulationOrchestrator;

    /** Today as an absolute game day. */
    public int today() {
        return gameCalendarRepository.findAll().stream()
                .max(Comparator.comparingInt(GameCalendar::getSeason))
                .map(c -> c.getSeason() * DAYS_PER_SEASON + c.getCurrentDay())
                .orElse(0);
    }

    /** Season of an absolute game day (days run 1–365 inside a season). */
    public static int seasonOf(int absoluteDay) {
        return (absoluteDay - 1) / DAYS_PER_SEASON;
    }

    /** Day 1–365 inside the season of an absolute game day. */
    public static int dayOf(int absoluteDay) {
        return absoluteDay - seasonOf(absoluteDay) * DAYS_PER_SEASON;
    }

    /** The last day of the summer window that is open on {@code today} (absolute). */
    public static int windowLastDay(int today) {
        int season = seasonOf(today);
        int day = dayOf(today);
        int closeSeason = day >= TransferWindow.SUMMER_OPEN_DAY ? season + 1 : season;
        return closeSeason * DAYS_PER_SEASON + TransferWindow.SUMMER_CLOSE_DAY - 1;
    }

    /**
     * Day of the {@code index}-th of {@code count} transfers, spread evenly from tomorrow to the last day of the
     * window (several a day when there are more transfers than days).
     */
    public static int dayFor(int index, int count, int today, int lastDay) {
        int first = today + 1;
        int span = Math.max(1, lastDay - first + 1);
        return first + (int) ((long) index * span / Math.max(1, count));
    }

    /** Stores the window's AI transfers, each on its day, instead of carrying them out now. */
    @Transactional
    public List<PlannedTransfer> schedule(List<Transfer> transfers, Map<Long, Human> movedPlayers, int season) {
        return schedule(transfers, movedPlayers, season, Map.of());
    }

    /**
     * As above, remembering for each player every club the market found able to sign him: "wanted" shows them
     * all, so the manager cannot tell which one the player already chose (design §3.6).
     */
    @Transactional
    public List<PlannedTransfer> schedule(List<Transfer> transfers, Map<Long, Human> movedPlayers, int season,
                                          Map<Long, ? extends Collection<Long>> contenders) {
        int today = today();
        int lastDay = windowLastDay(today);
        List<PlannedTransfer> rows = new ArrayList<>();
        for (int i = 0; i < transfers.size(); i++) {
            Transfer t = transfers.get(i);
            Human planned = movedPlayers.get(t.getPlayerId());
            PlannedTransfer row = new PlannedTransfer();
            row.setPlayerId(t.getPlayerId());
            row.setSellTeamId(t.getSellTeamId());
            row.setBuyTeamId(t.getBuyTeamId());
            row.setFee(t.getPlayerTransferValue());
            row.setWage(planned == null ? 0 : planned.getWage());
            row.setContractEndSeason(planned == null ? season + 2 : planned.getContractEndSeason());
            row.setSeason(season);
            row.setExecuteOnDay(dayFor(i, transfers.size(), today, lastDay));
            row.setWantedFromDay(row.getExecuteOnDay() - WANTED_DAYS_BEFORE);
            row.setContenderTeamIds(contenderList(t.getBuyTeamId(), contenders.get(t.getPlayerId())));
            row.setStatus(PlannedTransfer.PLANNED);
            rows.add(row);
        }
        return plannedTransferRepository.saveAll(rows);
    }

    /**
     * AI clubs holding a buy-back clause use it when the player is now worth more than the clause and they can
     * pay: the move is planned for tomorrow, like any other move of the window.
     */
    @Transactional
    public List<PlannedTransfer> planBuyBacks(int season) {
        Set<Long> human = new HashSet<>(userContext.getAllHumanTeamIds());
        int tomorrow = today() + 1;
        List<PlannedTransfer> rows = new ArrayList<>();
        for (Human player : humanRepository.findAllByTypeId(com.footballmanagergamesimulator.util.TypeNames.PLAYER_TYPE)) {
            long club = player.getBuyBackClubId();
            if (club <= 0 || player.isRetired() || player.getBuyBackUntilSeason() < season) continue;
            if (human.contains(club) || player.getTeamId() == null || player.getTeamId() == club) continue;
            if (hasPlannedMove(player.getId())) continue;
            Team buyer = teamRepository.findById(club).orElse(null);
            if (buyer == null || buyer.getTransferBudget() < player.getBuyBackPrice()) continue;
            if (TransferValuation.of(player.getAge(), player.getRating()) <= player.getBuyBackPrice()) continue;
            PlannedTransfer row = new PlannedTransfer();
            row.setPlayerId(player.getId());
            row.setSellTeamId(player.getTeamId());
            row.setBuyTeamId(club);
            row.setFee(player.getBuyBackPrice());
            row.setWage(player.getWage());
            row.setContractEndSeason(Math.max(player.getContractEndSeason(), season + 2));
            row.setSeason(season);
            row.setExecuteOnDay(tomorrow);
            row.setWantedFromDay(tomorrow - WANTED_DAYS_BEFORE);
            row.setContenderTeamIds(String.valueOf(club));
            row.setStatus(PlannedTransfer.PLANNED);
            row.setBuyBack(true);
            rows.add(row);
        }
        return plannedTransferRepository.saveAll(rows);
    }

    /** "Wanted" shows this many days before the planned day (design §3.6, decided). */
    public static final int WANTED_DAYS_BEFORE = 5;

    /** Players shown as "wanted" today: an AI move is planned for them within the next 5 days. */
    public Set<Long> wantedPlayerIds() {
        return wantedBy().keySet();
    }

    /** Today's "wanted" players, each with every club that had a chance to sign him (ids, the buyer among them). */
    public Map<Long, List<Long>> wantedBy() {
        int today = today();
        Map<Long, List<Long>> wanted = new HashMap<>();
        for (PlannedTransfer row : plannedTransferRepository.findAllByStatus(PlannedTransfer.PLANNED)) {
            if (!visible(row, today)) continue;
            wanted.computeIfAbsent(row.getPlayerId(), id -> new ArrayList<>());
            for (long club : contenders(row)) {
                if (!wanted.get(row.getPlayerId()).contains(club)) wanted.get(row.getPlayerId()).add(club);
            }
        }
        return wanted;
    }

    /** Clubs shown on one player's "wanted"; empty when he is not wanted today. */
    public List<Long> wantedBy(long playerId) {
        int today = today();
        List<Long> clubs = new ArrayList<>();
        for (PlannedTransfer row : plannedTransferRepository.findAllByPlayerIdAndStatus(playerId, PlannedTransfer.PLANNED)) {
            if (!visible(row, today)) continue;
            for (long club : contenders(row)) if (!clubs.contains(club)) clubs.add(club);
        }
        return clubs;
    }

    static boolean visible(PlannedTransfer row, int today) {
        return row.getWantedFromDay() <= today;
    }

    /** The buyer and the other able clubs, in id order so the list does not give the buyer away. */
    static String contenderList(long buyer, Collection<Long> others) {
        TreeSet<Long> all = new TreeSet<>();
        all.add(buyer);
        if (others != null) all.addAll(others);
        return all.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    static List<Long> contenders(PlannedTransfer row) {
        String ids = row.getContenderTeamIds();
        if (ids == null || ids.isBlank()) return List.of(row.getBuyTeamId());
        return Arrays.stream(ids.split(",")).map(String::trim).filter(x -> !x.isEmpty()).map(Long::parseLong).toList();
    }

    /**
     * A manager is talking about this player (with his club or his agent): the AI's planned move waits 3 more
     * days, never past the last day of the window.
     */
    @Transactional
    public void postpone(long playerId) {
        int lastDay = windowLastDay(today());
        List<PlannedTransfer> rows = plannedTransferRepository.findAllByPlayerIdAndStatus(playerId, PlannedTransfer.PLANNED);
        rows.forEach(r -> r.setExecuteOnDay(Math.min(r.getExecuteOnDay() + POSTPONE_DAYS, lastDay)));
        plannedTransferRepository.saveAll(rows);
    }

    /** Shown as "wanted" today. */
    public boolean isWanted(long playerId) {
        return !wantedBy(playerId).isEmpty();
    }

    /** An AI move is planned for him in this window, shown yet or not. */
    public boolean hasPlannedMove(long playerId) {
        return plannedTransferRepository.existsByPlayerIdAndStatus(playerId, PlannedTransfer.PLANNED);
    }

    /** Carries out every planned transfer due by today. Returns those that happened. */
    @Transactional
    public List<Transfer> executeDue() {
        int today = today();
        List<PlannedTransfer> due = plannedTransferRepository
                .findAllByStatusAndExecuteOnDayLessThanEqualOrderByExecuteOnDayAscIdAsc(PlannedTransfer.PLANNED, today);
        if (due.isEmpty()) return List.of();
        Set<Long> human = new HashSet<>(userContext.getAllHumanTeamIds());
        List<Transfer> done = new ArrayList<>();
        int lastDay = windowLastDay(today);
        for (PlannedTransfer plan : due) {
            List<TransferOffer> bids = liveBids(plan.getPlayerId(), human);
            if (!bids.isEmpty() && today < lastDay) {
                // A manager is still in it: wait, at most until the last day of the window.
                plan.setExecuteOnDay(Math.min(today + POSTPONE_DAYS, lastDay));
                continue;
            }
            Optional<TransferOffer> agreedBid = bids.stream()
                    .filter(b -> TransferOffer.STATUS_FEE_AGREED.equals(b.getStatus())).findFirst();
            if (agreedBid.isPresent() && playerChoosesManager(plan, agreedBid.get())) {
                plan.setStatus(PlannedTransfer.CANCELLED);
                plan.setCancelReason("player chose " + agreedBid.get().getFromTeamName());
                notify(agreedBid.get().getFromTeamId(), today, "Player Choice: " + agreedBid.get().getPlayerName(),
                        agreedBid.get().getPlayerName() + " turned down another club: he wants to join you. "
                                + "Agree personal terms before the window closes.");
                continue;
            }
            String problem = execute(plan, human, today, done);
            if (problem == null) closeLostBids(bids, plan, today);
            if (problem == null) {
                plan.setStatus(PlannedTransfer.DONE);
            } else {
                plan.setStatus(PlannedTransfer.CANCELLED);
                plan.setCancelReason(problem);
            }
        }
        plannedTransferRepository.saveAll(due);
        announce(done, human, today);
        return done;
    }

    private List<TransferOffer> liveBids(long playerId, Set<Long> humanTeamIds) {
        // A bid made to a club the player has since left is dead: it must not hold up (or cancel) the AI move.
        Long playerClubId = humanRepository.findById(playerId).map(Human::getTeamId).orElse(null);
        return transferOfferRepository.findAllByPlayerIdAndStatusIn(playerId, LIVE_BIDS).stream()
                .filter(o -> "outgoing".equalsIgnoreCase(o.getDirection()) && humanTeamIds.contains(o.getFromTeamId()))
                .filter(o -> java.util.Objects.equals(playerClubId, o.getToTeamId()))
                .toList();
    }

    /** Last day of the window, the fee agreed with me: the player compares the AI club and mine. */
    private boolean playerChoosesManager(PlannedTransfer plan, TransferOffer bid) {
        Human player = humanRepository.findById(plan.getPlayerId()).orElse(null);
        if (player == null) return false;
        PlayerChoice.Club ai = new PlayerChoice.Club(plan.getWage(), reputation(plan.getBuyTeamId()),
                betterAtPosition(plan.getBuyTeamId(), player));
        List<ContractPoints.SquadMember> mySquad = humanRepository
                .findAllByTeamIdAndTypeId(bid.getFromTeamId(), com.footballmanagergamesimulator.util.TypeNames.PLAYER_TYPE)
                .stream().filter(h -> !h.isRetired() && h.getId() != player.getId())
                .map(h -> new ContractPoints.SquadMember(h.getRating(), h.getWage())).toList();
        PlayerChoice.Club me = new PlayerChoice.Club(ContractPoints.demandedWage(player.getRating(), mySquad),
                reputation(bid.getFromTeamId()), betterAtPosition(bid.getFromTeamId(), player));
        return PlayerChoice.choosesMe(PlayerPersonality.of(player), ai, me);
    }

    private int reputation(long teamId) {
        return teamRepository.findById(teamId).map(Team::getReputation).orElse(0);
    }

    /** Players of the club at his base position rated above him. */
    private int betterAtPosition(long teamId, Human player) {
        String position = TacticService.getBasePosition(player.getPosition());
        return (int) humanRepository.findAllByTeamIdAndTypeId(teamId, com.footballmanagergamesimulator.util.TypeNames.PLAYER_TYPE)
                .stream()
                .filter(h -> !h.isRetired() && h.getId() != player.getId())
                .filter(h -> java.util.Objects.equals(TacticService.getBasePosition(h.getPosition()), position))
                .filter(h -> h.getRating() > player.getRating())
                .count();
    }

    /** The AI club got him: the managers who were after him lose their bids and are told. */
    private void closeLostBids(List<TransferOffer> bids, PlannedTransfer plan, int today) {
        for (TransferOffer bid : bids) {
            bid.setStatus(TransferOffer.STATUS_FEE_AGREED.equals(bid.getStatus())
                    ? TransferOffer.STATUS_TERMS_FAILED : TransferOffer.STATUS_REJECTED);
            transferOfferRepository.save(bid);
            String buyer = teamRepository.findById(plan.getBuyTeamId()).map(Team::getName).orElse("another club");
            notify(bid.getFromTeamId(), today, "Transfer Lost: " + bid.getPlayerName(),
                    bid.getPlayerName() + " chose to join " + buyer + ".");
        }
    }

    private void notify(long teamId, int today, String title, String content) {
        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(teamId);
        inbox.setSeasonNumber(seasonOf(today));
        inbox.setRoundNumber(dayOf(today));
        inbox.setTitle(title);
        inbox.setContent(content);
        inbox.setCategory("transfer");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        managerInboxRepository.save(inbox);
    }

    /**
     * Runs the rest of the window at once, as if its days passed with no manager stepping in: every planned move
     * is carried out in its planned order. Used by simulations and market measurements, which count the window's
     * transfers right after it opens. Returns the moves that happened; the others are cancelled and logged.
     */
    @Transactional
    public List<Transfer> drainWindow() {
        List<PlannedTransfer> planned = plannedTransferRepository.findAllByStatus(PlannedTransfer.PLANNED).stream()
                .sorted(Comparator.comparingInt(PlannedTransfer::getExecuteOnDay).thenComparingLong(PlannedTransfer::getId))
                .toList();
        Set<Long> human = new HashSet<>(userContext.getAllHumanTeamIds());
        int today = today();
        List<Transfer> done = new ArrayList<>();
        Map<String, Integer> cancelled = new TreeMap<>();
        for (PlannedTransfer plan : planned) {
            String problem = execute(plan, human, today, done);
            plan.setStatus(problem == null ? PlannedTransfer.DONE : PlannedTransfer.CANCELLED);
            plan.setCancelReason(problem);
            if (problem != null) cancelled.merge(problem, 1, Integer::sum);
        }
        plannedTransferRepository.saveAll(planned);
        System.out.println("=== Window drained: planned=" + planned.size() + " done=" + done.size()
                + " cancelled=" + cancelled + " ===");
        return done;
    }

    /** Today's moves reach the managers' inboxes, so the market is seen moving day by day. */
    private void announce(List<Transfer> done, Set<Long> humanTeamIds, int today) {
        if (done.isEmpty()) return;
        String news = done.stream()
                .map(t -> String.format("%s (%.0f): %s → %s (%,d)", t.getPlayerName(), t.getRating(),
                        t.getSellTeamName(), t.getBuyTeamName(), t.getPlayerTransferValue()))
                .collect(Collectors.joining("\n"));
        for (long teamId : humanTeamIds) {
            ManagerInbox inbox = new ManagerInbox();
            inbox.setTeamId(teamId);
            inbox.setSeasonNumber(seasonOf(today));
            inbox.setRoundNumber(dayOf(today));
            inbox.setTitle("Transfer News - " + done.size() + (done.size() == 1 ? " move today" : " moves today"));
            inbox.setContent(news);
            inbox.setCategory("transfer");
            inbox.setRead(false);
            inbox.setCreatedAt(System.currentTimeMillis());
            managerInboxRepository.save(inbox);
        }
    }

    /** Null when the move happened; otherwise why it could not. */
    private String execute(PlannedTransfer plan, Set<Long> humanTeamIds, int today, List<Transfer> done) {
        Human player = humanRepository.findById(plan.getPlayerId()).orElse(null);
        if (player == null || player.isRetired()) return "player retired";
        if (player.isWillNeverLeave()) return "player will never leave";
        long owner = player.getTeamId() == null ? 0L : player.getTeamId();
        if (owner != plan.getSellTeamId()) return "player already moved";
        if (owner != 0L && humanTeamIds.contains(owner) && !plan.isBuyBack()) return "player belongs to a managed club";
        if (!loanRepository.findAllByPlayerIdAndStatus(player.getId(), "active").isEmpty()) return "player on loan";
        Team buyer = teamRepository.findById(plan.getBuyTeamId()).orElse(null);
        if (buyer == null) return "buyer gone";
        Team seller = owner == 0L ? null : teamRepository.findById(owner).orElse(null);

        int season = seasonOf(today);
        int day = dayOf(today);
        if (plan.isBuyBack()) {
            player.setBuyBackClubId(0);
            player.setBuyBackPrice(0);
            player.setBuyBackUntilSeason(0);
        }
        player.setTeamId(buyer.getId());
        player.setSeasonMatchesPlayed(0);
        player.setConsecutiveBenched(0);
        player.setContractEndSeason(plan.getContractEndSeason());
        if (plan.getWage() > 0) player.setWage(plan.getWage());
        contractSignature.mark(player);
        humanRepository.save(player);

        long fee = plan.getFee();
        // A sell-on agreed in an earlier transfer takes its share of this fee (design §3.5).
        long sellOnFee = 0;
        long sellOnClub = player.getSellOnClubId();
        if (fee > 0 && player.getSellOnPercentage() > 0 && sellOnClub > 0) {
            sellOnFee = fee * player.getSellOnPercentage() / 100;
        }
        player.setSellOnPercentage(0);
        player.setSellOnClubId(0);
        humanRepository.save(player);
        if (sellOnFee > 0) {
            financeService.recordTransaction(sellOnClub, season, day, "SELL_ON_FEE",
                    "Sell-on fee for " + player.getName(), sellOnFee);
        }
        if (fee > 0) {
            // Same money movements as the window used to make all at once.
            financeService.recordTransaction(buyer.getId(), season, day, "TRANSFER_BUY", "Bought " + player.getName(), -fee);
            Team freshBuyer = teamRepository.findById(buyer.getId()).orElse(buyer);
            freshBuyer.setTransferBudget(Math.max(0, freshBuyer.getTransferBudget() - fee));
            teamRepository.save(freshBuyer);
            if (seller != null) {
                financeService.recordTransaction(seller.getId(), season, day, "TRANSFER_SALE", "Sold " + player.getName(), fee - sellOnFee);
                Team freshSeller = teamRepository.findById(seller.getId()).orElse(seller);
                freshSeller.setTransferBudget(freshSeller.getTransferBudget() + fee - sellOnFee);
                teamRepository.save(freshSeller);
            }
        }

        Transfer transfer = new Transfer();
        transfer.setPlayerId(player.getId());
        transfer.setPlayerName(player.getName());
        transfer.setPlayerTransferValue(fee);
        transfer.setSellTeamId(seller == null ? 0L : seller.getId());
        transfer.setSellTeamName(seller == null ? "Free agent" : seller.getName());
        transfer.setBuyTeamId(buyer.getId());
        transfer.setBuyTeamName(buyer.getName());
        transfer.setRating(player.getRating());
        transfer.setSeasonNumber(plan.getSeason());
        transfer.setPlayerAge(player.getAge());
        done.add(transferRepository.save(transfer));

        matchSimulationOrchestrator.invalidateSquadCaches(buyer.getId());
        if (seller != null) matchSimulationOrchestrator.invalidateSquadCaches(seller.getId());
        return null;
    }
}
