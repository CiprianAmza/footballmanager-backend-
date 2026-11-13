package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.frontend.TransferOfferView;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.service.TransferOfferLifecycleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.util.*;

@RestController
@RequestMapping("/transferOffer")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class TransferOfferController implements com.footballmanagergamesimulator.service.AgreedTransferHandler {

    @Autowired
    UserContext userContext;

    @Autowired
    TransferOfferRepository transferOfferRepository;

    @Autowired
    HumanRepository humanRepository;

    @Autowired
    TeamRepository teamRepository;

    @Autowired
    TransferRepository transferRepository;

    @Autowired
    LoanRepository loanRepository;

    @Autowired
    ManagerInboxRepository managerInboxRepository;

    @Autowired
    RoundRepository roundRepository;

    @Autowired
    TeamFacilitiesRepository teamFacilitiesRepository;

    @Autowired
    CompetitionController competitionController;

    @Autowired
    com.footballmanagergamesimulator.service.FinanceService financeService;

    @Autowired
    ContractController contractController;

    @Autowired
    com.footballmanagergamesimulator.service.CoachPermissionService coachPermissionService;

    @Autowired
    TransferOfferLifecycleService transferOfferLifecycleService;

    @Autowired
    com.footballmanagergamesimulator.service.PlayerMarketAvailabilityService marketAvailabilityService;

    @Autowired
    com.footballmanagergamesimulator.service.PlayerPreviewService playerPreviewService;

    @Autowired
    com.footballmanagergamesimulator.service.ClubActionAuthorizationService clubActionAuthorizationService;

    @Autowired
    com.footballmanagergamesimulator.service.MediaNarrativeService mediaNarrativeService;

    private final Random random = new Random();

    @Autowired
    com.footballmanagergamesimulator.service.TransferPricingService transferPricingService;

    @Autowired
    GameCalendarRepository gameCalendarRepository;

    @Autowired
    com.footballmanagergamesimulator.service.PlannedTransferService plannedTransferService;

    @Autowired
    com.footballmanagergamesimulator.service.TransferInstallmentService transferInstallmentService;
    @Autowired
    com.footballmanagergamesimulator.service.TransferBonusService transferBonusService;
    @org.springframework.beans.factory.annotation.Autowired
    com.footballmanagergamesimulator.service.ContractSignature contractSignature;

    @GetMapping("/incoming/{teamId}")
    @Transactional
    public List<TransferOfferView> getIncomingOffers(@PathVariable(name = "teamId") long teamId) {
        List<TransferOffer> offers = transferOfferRepository.findAllByToTeamIdAndStatus(teamId, "pending");
        Set<Long> playerIds = offers.stream().map(TransferOffer::getPlayerId).collect(java.util.stream.Collectors.toSet());
        Map<Long, Human> players = humanRepository.findAllById(playerIds).stream()
                .collect(java.util.stream.Collectors.toMap(Human::getId, player -> player));
        offers = removeStaleOffers(offers, players);
        int currentSeason = roundRepository.findById(1L).map(round -> (int) round.getSeason()).orElse(1);
        return offers.stream()
                .map(offer -> TransferOfferView.from(offer, players.get(offer.getPlayerId()), currentSeason))
                .toList();
    }

    @GetMapping("/outgoing/{teamId}")
    @Transactional
    public List<TransferOffer> getOutgoingOffers(@PathVariable(name = "teamId") long teamId) {
        List<TransferOffer> pending = transferOfferRepository.findAllByFromTeamIdAndStatus(teamId, "pending");
        List<TransferOffer> negotiating = transferOfferRepository.findAllByFromTeamIdAndStatus(teamId, "negotiating");
        List<TransferOffer> counter = transferOfferRepository.findAllByFromTeamIdAndStatus(teamId, "counter");
        List<TransferOffer> feeAgreed = transferOfferRepository.findAllByFromTeamIdAndStatus(teamId, TransferOffer.STATUS_FEE_AGREED);
        List<TransferOffer> all = new ArrayList<>();
        all.addAll(pending);
        all.addAll(negotiating);
        all.addAll(counter);
        all.addAll(feeAgreed);
        Set<Long> playerIds = all.stream().map(TransferOffer::getPlayerId)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, Human> players = humanRepository.findAllById(playerIds).stream()
                .collect(java.util.stream.Collectors.toMap(Human::getId, player -> player));
        return removeStaleOffers(all, players);
    }

    /** Complete transfer record of one player, oldest first — powers the
     *  Transfer History section on the player page. */
    @GetMapping("/playerHistory/{playerId}")
    public List<Transfer> getPlayerTransferHistory(@PathVariable(name = "playerId") long playerId) {
        return transferRepository.findAllByPlayerIdOrderBySeasonNumberAscIdAsc(playerId);
    }

    @GetMapping("/history/{teamId}/{season}")
    public List<TransferOffer> getHistory(@PathVariable(name = "teamId") long teamId,
                                          @PathVariable(name = "season") int season) {
        List<TransferOffer> toOffers = transferOfferRepository.findAllByToTeamIdAndSeasonNumber(teamId, season);
        List<TransferOffer> fromOffers = transferOfferRepository.findAllByFromTeamIdAndSeasonNumber(teamId, season);
        Set<Long> seen = new HashSet<>();
        List<TransferOffer> combined = new ArrayList<>();
        for (TransferOffer o : toOffers) {
            if (seen.add(o.getId())) combined.add(o);
        }
        for (TransferOffer o : fromOffers) {
            if (seen.add(o.getId())) combined.add(o);
        }
        return combined;
    }

    // ==================== FEE TALKS WITH THE SELLING CLUB (rounds, decided 2026-11-12) ====================

    /** A rejected request; the code is stable for the frontend, the message readable. */
    private static ResponseEntity<Map<String, Object>> talksError(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "code", code, "message", message));
    }

    /**
     * "Make transfer offer": opens fee talks with the selling club, or returns the talks already open
     * (or the fee already agreed) for this player, so the button always leads back to them.
     */
    @PostMapping("/talks/start")
    @Transactional
    public ResponseEntity<?> startTalks(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long playerId = ((Number) body.get("playerId")).longValue();
        long humanTeamId = clubActionAuthorizationService.authorize(request, body,
                com.footballmanagergamesimulator.service.ClubActionAuthorizationService.Action.TRANSFER).teamId();

        Optional<TransferOffer> existing = transferOfferRepository.findAllByPlayerIdAndStatusIn(playerId,
                        List.of(TransferOffer.STATUS_TALKS, TransferOffer.STATUS_FINAL_OFFER, TransferOffer.STATUS_FEE_AGREED))
                .stream().filter(o -> o.getFromTeamId() == humanTeamId && "outgoing".equalsIgnoreCase(o.getDirection()))
                .findFirst();
        if (existing.isPresent()) return ResponseEntity.ok(talksView(existing.get()));

        int today = gameDay();
        int blockedUntil = transferOfferRepository.findAllByPlayerIdAndStatusIn(playerId, List.of(TransferOffer.STATUS_REJECTED))
                .stream().filter(o -> o.getFromTeamId() == humanTeamId)
                .mapToInt(TransferOffer::getRetryAfterDay).max().orElse(0);
        if (blockedUntil > today) {
            int days = blockedUntil - today;
            return talksError(409, "COOLDOWN", "The club will not talk to you about this player for "
                    + days + (days == 1 ? " more day." : " more days."));
        }

        if (!competitionController.isTransferWindowOpen()) {
            return talksError(400, "WINDOW_CLOSED", "Transfer window is not open.");
        }
        Human player = humanRepository.findByIdForUpdate(playerId).orElse(null);
        if (player == null) return talksError(400, "PLAYER_NOT_FOUND", "Player not found");
        ResponseEntity<Map<String, Object>> unavailable = unavailableForTransfer(player, humanTeamId);
        if (unavailable != null) return unavailable;

        Team humanTeam = teamRepository.findById(humanTeamId).orElse(null);
        Team sellingTeam = teamRepository.findById(player.getTeamId()).orElse(null);
        if (humanTeam == null || sellingTeam == null) return talksError(400, "TEAM_NOT_FOUND", "Team not found");

        Round round = roundRepository.findById(1L).orElse(new Round());
        TransferOffer offer = new TransferOffer();
        offer.setPlayerId(player.getId());
        offer.setPlayerName(player.getName());
        offer.setFromTeamId(humanTeamId);
        offer.setFromTeamName(humanTeam.getName());
        offer.setToTeamId(sellingTeam.getId());
        offer.setToTeamName(sellingTeam.getName());
        // Real price → the club's ask (−35%…+40%, shown) → its minimum (ask −0…30%, hidden).
        long realPrice = transferPricingService.realPrice(player, humanTeamId, (int) round.getSeason());
        long ask = com.footballmanagergamesimulator.service.TransferPricing.clubWants(
                realPrice, player.getReleaseClause(), random);
        offer.setRealPrice(realPrice);
        offer.setSellerSituation(transferPricingService.sellerSituation(player, (int) round.getSeason()).name());
        offer.setAskingPrice(ask);
        offer.setMinimumPrice(com.footballmanagergamesimulator.service.ClubNegotiation.minimum(ask, random));
        offer.setSeasonNumber((int) round.getSeason());
        offer.setDirection("outgoing");
        offer.setCreatedAt(System.currentTimeMillis());
        offer.setStatus(TransferOffer.STATUS_TALKS);
        offer.setRound(1);
        offer.setRounds(com.footballmanagergamesimulator.service.ClubNegotiation.roundsOf(sellingTeam.getId()));
        return ResponseEntity.ok(talksView(transferOfferRepository.save(offer)));
    }

    @GetMapping("/talks/{offerId}")
    public ResponseEntity<?> getTalks(HttpServletRequest request, @PathVariable long offerId) {
        long humanTeamId = userContext.getTeamId(request);
        Optional<TransferOffer> offer = transferOfferRepository.findById(offerId)
                .filter(o -> o.getFromTeamId() == humanTeamId && "outgoing".equalsIgnoreCase(o.getDirection()));
        if (offer.isEmpty()) return talksError(404, "OFFER_NOT_FOUND", "Offer not found");
        return ResponseEntity.ok(talksView(offer.get()));
    }

    /** My fee offer in the current round. */
    @PostMapping("/talks/{offerId}/offer")
    @Transactional
    public ResponseEntity<?> offerInTalks(HttpServletRequest request, @PathVariable long offerId,
                                          @RequestBody Map<String, Object> body) {
        var actor = clubActionAuthorizationService.authorize(request, body,
                com.footballmanagergamesimulator.service.ClubActionAuthorizationService.Action.TRANSFER);
        com.footballmanagergamesimulator.service.TransferPoints.Offer extras = new com.footballmanagergamesimulator.service.TransferPoints.Offer(
                longOf(body, "offerAmount"), (int) longOf(body, "installmentCount"), longOf(body, "installmentAmount"),
                (int) longOf(body, "sellOnPercent"), longOf(body, "buyBackPrice"),
                (int) longOf(body, "goalsBonusTarget"), longOf(body, "goalsBonusAmount"), longOf(body, "titleBonusAmount"));
        long amount = extras.upfront();
        TransferOffer offer = ownTalks(offerId, actor.teamId());
        if (offer == null) return talksError(404, "OFFER_NOT_FOUND", "Offer not found");
        if (!TransferOffer.STATUS_TALKS.equals(offer.getStatus())) {
            return talksError(409, "NOT_OPEN", "There is no round open for an offer.");
        }
        if (!competitionController.isTransferWindowOpen()) return closeWithWindow(offer);
        Human player = humanRepository.findByIdForUpdate(offer.getPlayerId()).orElse(null);
        if (player == null || !Objects.equals(player.getTeamId(), offer.getToTeamId())) {
            return endTalks(offer, "The player is no longer at " + offer.getToTeamName() + ".");
        }
        if (!extras.valid()) {
            return talksError(400, "INVALID_OFFER",
                    "Check the offer: up to 3 instalments, a sell-on of 0–50%, a goals bonus needs a target of 1–60 goals.");
        }
        if (amount > 0) {
            ResponseEntity<Map<String, Object>> unaffordable = unaffordable(actor, amount);
            if (unaffordable != null) return unaffordable;
        }

        offer.setOfferAmount(amount);
        offer.setInstallmentCount(extras.instalments());
        offer.setInstallmentAmount(extras.instalments() == 0 ? 0 : extras.instalmentAmount());
        offer.setSellOnPercent(extras.sellOnPercent());
        offer.setBuyBackPrice(extras.buyBackPrice());
        offer.setGoalsBonusTarget(extras.goalsBonusAmount() == 0 ? 0 : extras.goalsBonusTarget());
        offer.setGoalsBonusAmount(extras.goalsBonusAmount());
        offer.setTitleBonusAmount(extras.titleBonusAmount());
        Round round = roundRepository.findById(1L).orElse(new Round());
        // The release clause is cash: only the money paid now triggers it. Otherwise the club weighs the whole
        // offer in transfer points, by what each extra is worth in its situation.
        boolean clause = player.getReleaseClause() > 0 && amount >= player.getReleaseClause();
        var chances = transferBonusService.chances(player.getId(), extras.goalsBonusTarget(), offer.getFromTeamId(),
                com.footballmanagergamesimulator.service.PlannedTransferService.seasonOf(gameDay()));
        long points = com.footballmanagergamesimulator.service.TransferPoints.points(extras, offer.getRealPrice(),
                sellerSituation(offer), chances.goals(), chances.title());
        var answer = com.footballmanagergamesimulator.service.ClubNegotiation.answer(clause ? offer.getAskingPrice() : points,
                offer.getAskingPrice(), offer.getMinimumPrice(), 0, offer.getRound(), offer.getRounds(), random);
        offer.setAskingPrice(answer.ask());
        // The club answered me: an AI club planning to sign him has to wait (design §3.6).
        plannedTransferService.postpone(offer.getPlayerId());
        switch (answer.outcome()) {
            case FEE_AGREED -> agreeFee(offer, round, clause
                    ? "Your offer of " + amount + " has triggered the release clause for " + player.getName() + "."
                    : offer.getToTeamName() + " have accepted your offer for " + player.getName() + ": " + describe(offer) + ".");
            case TURNED_DOWN -> offer.setRound(offer.getRound() + 1);
            case FINAL_OFFER -> {
                offer.setRound(offer.getRound() + 1);
                offer.setStatus(TransferOffer.STATUS_FINAL_OFFER);
            }
            case CLUB_WALKED_AWAY -> {
                offer.setStatus(TransferOffer.STATUS_REJECTED);
                offer.setRetryAfterDay(gameDay() + com.footballmanagergamesimulator.service.ClubNegotiation.cooldownDays(random));
                sendInboxMessage(offer.getFromTeamId(), (int) round.getSeason(), (int) round.getRound(), "Transfer Talks Ended",
                        offer.getToTeamName() + " found your offer of " + amount + " for " + player.getName()
                                + " far too low and ended the talks.", "transfer");
            }
        }
        return ResponseEntity.ok(talksView(transferOfferRepository.save(offer)));
    }

    /** My answer to the club's take-it-or-leave-it offer (its asking price). */
    @PostMapping("/talks/{offerId}/final")
    @Transactional
    public ResponseEntity<?> answerClubFinalOffer(HttpServletRequest request, @PathVariable long offerId,
                                                  @RequestBody Map<String, Object> body) {
        var actor = clubActionAuthorizationService.authorize(request, body,
                com.footballmanagergamesimulator.service.ClubActionAuthorizationService.Action.TRANSFER);
        boolean accept = Boolean.parseBoolean(String.valueOf(body.get("accept")));
        TransferOffer offer = ownTalks(offerId, actor.teamId());
        if (offer == null) return talksError(404, "OFFER_NOT_FOUND", "Offer not found");
        if (!TransferOffer.STATUS_FINAL_OFFER.equals(offer.getStatus())) {
            return talksError(409, "NO_FINAL_OFFER", "The club has not made a final offer.");
        }
        if (!competitionController.isTransferWindowOpen()) return closeWithWindow(offer);
        Round round = roundRepository.findById(1L).orElse(new Round());
        if (!accept) {
            offer.setStatus(TransferOffer.STATUS_REJECTED);
            offer.setRetryAfterDay(gameDay() + com.footballmanagergamesimulator.service.ClubNegotiation.cooldownDays(random));
            return ResponseEntity.ok(talksView(transferOfferRepository.save(offer)));
        }
        Human player = humanRepository.findByIdForUpdate(offer.getPlayerId()).orElse(null);
        if (player == null || !Objects.equals(player.getTeamId(), offer.getToTeamId())) {
            return endTalks(offer, "The player is no longer at " + offer.getToTeamName() + ".");
        }
        ResponseEntity<Map<String, Object>> unaffordable = unaffordable(actor, offer.getAskingPrice());
        if (unaffordable != null) return unaffordable;
        offer.setOfferAmount(offer.getAskingPrice());
        offer.setInstallmentCount(0);
        offer.setInstallmentAmount(0);
        offer.setSellOnPercent(0);
        offer.setBuyBackPrice(0);
        offer.setGoalsBonusTarget(0);
        offer.setGoalsBonusAmount(0);
        offer.setTitleBonusAmount(0);
        plannedTransferService.postpone(offer.getPlayerId());
        agreeFee(offer, round, "You accepted the asking price of " + offer.getAskingPrice() + " for "
                + offer.getPlayerName() + " set by " + offer.getToTeamName() + ".");
        return ResponseEntity.ok(talksView(transferOfferRepository.save(offer)));
    }

    /** The talks as the buyer sees them: the club's ask, never its minimum. */
    Map<String, Object> talksView(TransferOffer offer) {
        int currentGameDay = gameDay();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", offer.getId());
        view.put("playerId", offer.getPlayerId());
        view.put("playerName", offer.getPlayerName());
        view.put("toTeamId", offer.getToTeamId());
        view.put("toTeamName", offer.getToTeamName());
        view.put("offerAmount", offer.getOfferAmount());
        view.put("askingPrice", offer.getAskingPrice()); // the minimum stays hidden
        view.put("status", offer.getStatus());
        view.put("round", offer.getRound());
        view.put("rounds", offer.getRounds());
        view.put("retryAfterDays", Math.max(0, offer.getRetryAfterDay() - currentGameDay));
        view.put("installmentCount", offer.getInstallmentCount());
        view.put("installmentAmount", offer.getInstallmentAmount());
        view.put("sellOnPercent", offer.getSellOnPercent());
        view.put("buyBackPrice", offer.getBuyBackPrice());
        view.put("goalsBonusTarget", offer.getGoalsBonusTarget());
        view.put("goalsBonusAmount", offer.getGoalsBonusAmount());
        view.put("titleBonusAmount", offer.getTitleBonusAmount());
        return view;
    }

    /** Today as an absolute game day (season × 365 + day), for cool-downs that cross a season. */
    int gameDay() {
        return gameCalendarRepository.findAll().stream()
                .max(java.util.Comparator.comparingInt(GameCalendar::getSeason))
                .map(c -> c.getSeason() * 365 + c.getCurrentDay())
                .orElse(0);
    }

    private static com.footballmanagergamesimulator.service.TransferPoints.Situation sellerSituation(TransferOffer offer) {
        try {
            return com.footballmanagergamesimulator.service.TransferPoints.Situation.valueOf(offer.getSellerSituation());
        } catch (RuntimeException unknown) {
            return com.footballmanagergamesimulator.service.TransferPoints.Situation.STABLE;
        }
    }

    private static long longOf(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null || String.valueOf(value).isBlank() ? 0 : ((Number) value).longValue();
    }

    /** "1,000,000 now + 2 × 300,000 + 20% sell-on + buy-back at 2,000,000" */
    static String describe(TransferOffer offer) {
        StringBuilder text = new StringBuilder(String.format("%,d now", offer.getOfferAmount()));
        if (offer.getInstallmentCount() > 0) {
            text.append(String.format(" + %d × %,d", offer.getInstallmentCount(), offer.getInstallmentAmount()));
        }
        if (offer.getSellOnPercent() > 0) text.append(" + ").append(offer.getSellOnPercent()).append("% sell-on");
        if (offer.getBuyBackPrice() > 0) text.append(String.format(" + buy-back at %,d", offer.getBuyBackPrice()));
        if (offer.getGoalsBonusAmount() > 0) {
            text.append(String.format(" + %,d at %d goals in a season", offer.getGoalsBonusAmount(), offer.getGoalsBonusTarget()));
        }
        if (offer.getTitleBonusAmount() > 0) text.append(String.format(" + %,d for a league title", offer.getTitleBonusAmount()));
        return text.toString();
    }

    private TransferOffer ownTalks(long offerId, long teamId) {
        return transferOfferRepository.findByIdForUpdate(offerId)
                .filter(o -> o.getFromTeamId() == teamId && "outgoing".equalsIgnoreCase(o.getDirection()))
                .orElse(null);
    }

    /** The fee is agreed: the transfer waits for personal terms, reached from the inbox button. */
    private void agreeFee(TransferOffer offer, Round round, String content) {
        offer.setStatus(TransferOffer.STATUS_FEE_AGREED);
        sendNegotiationInvite(offer, (int) round.getSeason(), (int) round.getRound(), "Transfer Fee Accepted",
                content + " Agree personal terms with the player to complete the transfer.");
    }

    /** Fee talks do not block the day, so the window can close under them: they end without a deal. */
    private ResponseEntity<?> closeWithWindow(TransferOffer offer) {
        offer.setStatus(TransferOffer.STATUS_REJECTED);
        transferOfferRepository.save(offer);
        return talksError(409, "WINDOW_CLOSED", "The transfer window has closed. The talks are over.");
    }

    /** Talks that can no longer succeed are closed so the day is not blocked. */
    private ResponseEntity<?> endTalks(TransferOffer offer, String reason) {
        offer.setStatus(TransferOffer.STATUS_REJECTED);
        transferOfferRepository.save(offer);
        return talksError(409, "PLAYER_GONE", reason);
    }

    /** Null when the offer fits the budget and the owner's cap; otherwise the rejection. */
    private ResponseEntity<Map<String, Object>> unaffordable(
            com.footballmanagergamesimulator.service.ClubActionAuthorizationService.ClubActor actor, long amount) {
        if (amount <= 0) return talksError(400, "INVALID_AMOUNT", "The offer must be positive.");
        long buyCap = coachPermissionService.transferBudgetCap(actor.teamId());
        if (!actor.chairman() && buyCap >= 0 && amount > buyCap) {
            return talksError(403, "OVER_CAP", "Restricționat de patron: oferta depășește plafonul de transfer (" + buyCap + ").");
        }
        Team team = teamRepository.findById(actor.teamId()).orElse(null);
        if (team == null || amount > team.getTransferBudget()) {
            return talksError(400, "INSUFFICIENT_BUDGET", "Insufficient transfer budget");
        }
        return null;
    }

    /** Null when the player can be bought by this club; otherwise the rejection. */
    private ResponseEntity<Map<String, Object>> unavailableForTransfer(Human player, long humanTeamId) {
        if (player.getTeamId() == null || player.getTeamId() == humanTeamId) {
            return talksError(400, "OWN_PLAYER", "Cannot buy a player from your own team");
        }
        if (player.isRetired()) return talksError(400, "RETIRED", "Player is retired");
        if (!loanRepository.findAllByPlayerIdAndStatus(player.getId(), "active").isEmpty()) {
            return talksError(409, "ON_LOAN", "A player currently on loan cannot be transferred permanently");
        }
        return null;
    }

    @PostMapping("/respond/{offerId}")
    @Transactional
    public ResponseEntity<?> respondToOffer(HttpServletRequest request,
                                            @PathVariable(name = "offerId") long offerId,
                                            @RequestBody Map<String, Object> body) {
        // Lock the offer too: reject/counter/accept requests for the same row cannot
        // race while the player ownership lock protects competing offer rows.
        Optional<TransferOffer> offerOpt = transferOfferRepository.findByIdForUpdate(offerId);
        if (offerOpt.isEmpty()) {
            return ResponseEntity.badRequest().body("Offer not found");
        }

        TransferOffer offer = offerOpt.get();
        String action = (String) body.get("action");
        Round round = roundRepository.findById(1L).orElse(new Round());
        int season = (int) round.getSeason();
        long humanTeamId = userContext.getTeamId(request);

        if (offer.getToTeamId() != humanTeamId || !"incoming".equalsIgnoreCase(offer.getDirection())) {
            return ResponseEntity.status(403).body("This offer does not belong to your team");
        }
        if (!"pending".equalsIgnoreCase(offer.getStatus())) {
            return ResponseEntity.status(409).body("This offer is no longer active");
        }

        Human lockedPlayer = humanRepository.findByIdForUpdate(offer.getPlayerId()).orElse(null);
        if (lockedPlayer == null) {
            transferOfferLifecycleService.removeActiveOffersForPlayer(offer.getPlayerId());
            return ResponseEntity.status(409).body("Player is no longer available");
        }
        if (!Objects.equals(lockedPlayer.getTeamId(), offer.getToTeamId())) {
            transferOfferLifecycleService.removeActiveOffersForPlayer(lockedPlayer.getId());
            return ResponseEntity.status(409).body("The player has already left the selling club");
        }
        if (("accept".equals(action) || "counter".equals(action)) && lockedPlayer.isWillNeverLeave()) {
            transferOfferLifecycleService.removeActiveOffersForPlayer(lockedPlayer.getId());
            return ResponseEntity.status(409).body("This player will never leave their current club");
        }
        if (("accept".equals(action) || "counter".equals(action))
                && !loanRepository.findAllByPlayerIdAndStatus(lockedPlayer.getId(), "active").isEmpty()) {
            transferOfferLifecycleService.removeActiveOffersForPlayer(lockedPlayer.getId());
            return ResponseEntity.status(409).body("A player currently on loan cannot be transferred permanently");
        }

        // Accepting an incoming offer (or a counter that the AI then accepts) sells one of our
        // players → guard with canSellPlayers. Owner can lock the squad down.
        if (("accept".equals(action) || "counter".equals(action)) && !coachPermissionService.canSellPlayers(humanTeamId)) {
            return ResponseEntity.status(403).body("Restricționat de patron: nu poți vinde jucători.");
        }

        if ("accept".equals(action)) {
            Team fromTeam = teamRepository.findById(offer.getFromTeamId()).orElse(null);
            Team toTeam = teamRepository.findById(offer.getToTeamId()).orElse(null);

            if (fromTeam == null || toTeam == null) {
                return ResponseEntity.status(409).body("Team is no longer available");
            }

            // For incoming offers: fromTeam is the buyer (AI), toTeam is seller (human).
            executeTransfer(lockedPlayer, toTeam, fromTeam, offer.getOfferAmount(), season);
            offer.setStatus("accepted");
            transferOfferRepository.save(offer);
            transferOfferLifecycleService.removeActiveOffersForPlayer(lockedPlayer.getId());
            sendInboxMessage(humanTeamId, season, (int) round.getRound(), "Transfer Completed",
                    "You have sold " + lockedPlayer.getName() + " to " + fromTeam.getName() +
                    " for " + offer.getOfferAmount() + ".",
                    "transfer");
        } else if ("reject".equals(action)) {
            offer.setStatus("rejected");
            transferOfferRepository.save(offer);

            // Player whose transfer was rejected gets a morale penalty (they wanted to leave)
            double moralePenalty = -(5 + random.nextDouble() * 5); // -5 to -10
            lockedPlayer.setMorale(lockedPlayer.getMorale() + moralePenalty);
            lockedPlayer.setMorale(Math.min(lockedPlayer.getMorale(), 100D));
            lockedPlayer.setMorale(Math.max(lockedPlayer.getMorale(), 0D));
            humanRepository.save(lockedPlayer);

            sendInboxMessage(humanTeamId, season, (int) round.getRound(), "Player Unhappy",
                    lockedPlayer.getName() + " is unhappy that their transfer to " +
                    offer.getFromTeamName() + " was rejected. Their morale has dropped.",
                    "morale");
        } else if ("counter".equals(action)) {
            long counterAmount = ((Number) body.get("counterAmount")).longValue();
            offer.setAskingPrice(counterAmount);
            offer.setStatus("negotiating");

            long playerValue = calculateTransferValue(
                    lockedPlayer.getAge(), lockedPlayer.getPosition(), lockedPlayer.getRating()
            );

            // AI re-evaluates: if counter <= value * 1.3, they accept
            if (counterAmount <= (long)(playerValue * 1.3)) {
                Team fromTeam = teamRepository.findById(offer.getFromTeamId()).orElse(null);
                Team toTeam = teamRepository.findById(offer.getToTeamId()).orElse(null);

                if (fromTeam == null || toTeam == null) {
                    return ResponseEntity.status(409).body("Team is no longer available");
                }

                executeTransfer(lockedPlayer, toTeam, fromTeam, counterAmount, season);
                offer.setStatus("accepted");
                offer.setOfferAmount(counterAmount);
                transferOfferRepository.save(offer);
                transferOfferLifecycleService.removeActiveOffersForPlayer(lockedPlayer.getId());
                sendInboxMessage(humanTeamId, season, (int) round.getRound(), "Transfer Completed",
                        "Your counter-offer has been accepted! " + lockedPlayer.getName() +
                        " has been sold to " + fromTeam.getName() + " for " + counterAmount + ".",
                        "transfer");
            } else {
                offer.setStatus("rejected");
                transferOfferRepository.save(offer);
                sendInboxMessage(humanTeamId, season, (int) round.getRound(), "Counter-Offer Rejected",
                        offer.getFromTeamName() + " have rejected your counter-offer of " +
                        counterAmount + " for " + offer.getPlayerName() + ".",
                        "transfer");
            }
        } else {
            return ResponseEntity.badRequest().body("Invalid action. Use: accept, reject, or counter");
        }

        return ResponseEntity.ok(offer);
    }

    @GetMapping("/availablePlayers/{teamId}")
    public List<Map<String, Object>> getAvailablePlayers(
            @PathVariable(name = "teamId") long teamId,
            @RequestParam(name = "position", required = false) String position) {

        List<Human> allPlayers = humanRepository.findAll();
        List<Map<String, Object>> available = new ArrayList<>();

        Set<Long> unavailablePlayerIds = marketAvailabilityService.unavailablePlayerIds();

        // Get scouting level for the requesting team
        TeamFacilities facilities = teamFacilitiesRepository.findByTeamId(teamId);
        int scoutingLevel = (facilities != null) ? facilities.getScoutingLevel() : 1;
        if (scoutingLevel < 1) scoutingLevel = 1;
        if (scoutingLevel > 20) scoutingLevel = 20;
        int errorMargin = (20 - scoutingLevel) * 3;
        int scoutingAccuracy = scoutingLevel * 5;

        for (Human player : allPlayers) {
            if (player.getTeamId() == null || player.getTeamId() == teamId) continue;
            if (player.isRetired()) continue;
            if (player.isWillNeverLeave()) continue;
            if (player.getTypeId() != 1L) continue; // only players, not managers
            if (unavailablePlayerIds.contains(player.getId())) continue;
            if (position != null && !position.isEmpty() && !player.getPosition().equals(position)) continue;

            Team team = teamRepository.findById(player.getTeamId()).orElse(null);
            if (team == null) continue;

            // Apply scouting noise to rating
            double actualRating = player.getRating();
            double noise = (errorMargin > 0) ? (random.nextDouble() * 2 * errorMargin - errorMargin) : 0;
            double estimatedRating = Math.max(1, Math.round(actualRating + noise));

            Map<String, Object> playerInfo = new LinkedHashMap<>();
            playerInfo.put("id", player.getId());
            playerInfo.put("name", player.getName());
            playerInfo.put("position", player.getPosition());
            playerInfo.put("age", player.getAge());
            playerInfo.put("estimatedRating", estimatedRating);
            playerInfo.put("scoutingAccuracy", scoutingAccuracy);
            playerInfo.put("teamId", team.getId());
            playerInfo.put("teamName", team.getName());
            playerInfo.put("transferValue", player.getTransferValue());
            addPlayerPreview(playerInfo, player);

            available.add(playerInfo);
        }

        return available;
    }

    /**
     * Server-side transfer-market page. This avoids loading and rendering every
     * player in the game and keeps scouting estimates stable across refreshes.
     */
    @GetMapping("/availablePlayersPage/{teamId}")
    public Map<String, Object> getAvailablePlayersPage(
            @PathVariable(name = "teamId") long teamId,
            @RequestParam(name = "position", required = false) String position,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size,
            @RequestParam(name = "sort", defaultValue = "rating") String sort,
            @RequestParam(name = "direction", defaultValue = "desc") String direction,
            @RequestParam(name = "minValue", defaultValue = "0") long minValue,
            @RequestParam(name = "maxValue", defaultValue = "9223372036854775807") long maxValue,
            @RequestParam(name = "maxRating", defaultValue = "1.7976931348623157E308") double maxRating,
            @RequestParam(name = "marketStatus", defaultValue = "AVAILABLE") String marketStatus) {

        int safePage = Math.max(0, page);
        int safeSize = Math.max(10, Math.min(100, size));
        Set<String> allowedSorts = Set.of("rating", "age", "transferValue", "name", "position", "club");
        String sortField = allowedSorts.contains(sort) ? sort : "rating";
        boolean sortByClub = "club".equals(sortField);
        Sort.Direction sortDirection = "asc".equalsIgnoreCase(direction)
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort playerSort = Sort.by(sortDirection, sortField).and(Sort.by(Sort.Direction.ASC, "id"));
        PageRequest pageable = PageRequest.of(safePage, safeSize,
                sortByClub ? Sort.unsorted() : playerSort);
        long safeMinValue = Math.max(0, minValue);
        long safeMaxValue = Math.max(safeMinValue, maxValue);
        double safeMaxRating = Double.isFinite(maxRating) ? Math.max(0, maxRating) : Double.MAX_VALUE;

        Map<Long, com.footballmanagergamesimulator.service.PlayerMarketAvailabilityService.MarketState> marketStates =
                Optional.ofNullable(marketAvailabilityService.currentSeasonStates()).orElse(Map.of());
        Set<Long> unavailablePlayerIds = marketStates.entrySet().stream()
                .filter(entry -> entry.getValue().transferredThisSeason()
                        || entry.getValue().loanedThisSeason() || entry.getValue().loaned())
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        Set<Long> transferredPlayerIds = marketStates.entrySet().stream()
                .filter(entry -> entry.getValue().transferredThisSeason())
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        Set<Long> loanedPlayerIds = marketStates.entrySet().stream()
                .filter(entry -> entry.getValue().loaned())
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        String normalizedMarketStatus = Set.of("ALL", "AVAILABLE", "TRANSFERRED", "LOANED")
                .contains(marketStatus.toUpperCase(Locale.ROOT))
                ? marketStatus.toUpperCase(Locale.ROOT) : "AVAILABLE";
        Specification<Human> availableSpec = (root, query, cb) -> {
            Predicate marketStatusPredicate = switch (normalizedMarketStatus) {
                case "ALL" -> cb.conjunction();
                case "TRANSFERRED" -> transferredPlayerIds.isEmpty()
                        ? cb.disjunction() : root.get("id").in(transferredPlayerIds);
                case "LOANED" -> loanedPlayerIds.isEmpty()
                        ? cb.disjunction() : root.get("id").in(loanedPlayerIds);
                default -> unavailablePlayerIds.isEmpty()
                        ? cb.conjunction() : cb.not(root.get("id").in(unavailablePlayerIds));
            };
            List<Predicate> predicates = new ArrayList<>(List.of(
                    cb.equal(root.get("typeId"), 1L),
                    cb.isFalse(root.get("retired")),
                    cb.isFalse(root.get("willNeverLeave")),
                    cb.isNotNull(root.get("teamId")),
                    cb.notEqual(root.get("teamId"), teamId),
                    cb.greaterThanOrEqualTo(root.get("transferValue"), safeMinValue),
                    cb.lessThanOrEqualTo(root.get("transferValue"), safeMaxValue),
                    cb.lessThanOrEqualTo(root.get("rating"), safeMaxRating),
                    marketStatusPredicate,
                    position == null || position.isBlank() || "ALL".equalsIgnoreCase(position)
                            ? cb.conjunction() : cb.equal(root.get("position"), position)
            ));

            if (sortByClub) {
                Root<Team> club = query.from(Team.class);
                predicates.add(cb.equal(club.get("id"), root.get("teamId")));
                if (!Long.class.equals(query.getResultType()) && !long.class.equals(query.getResultType())) {
                    query.orderBy(sortDirection == Sort.Direction.ASC
                                    ? cb.asc(cb.lower(club.get("name")))
                                    : cb.desc(cb.lower(club.get("name"))),
                            cb.asc(root.get("id")));
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<Human> playerPage = humanRepository.findAll(availableSpec, pageable);

        int season = marketAvailabilityService.currentSeason();
        Map<Long, com.footballmanagergamesimulator.service.PlayerPreviewService.Preview> previews =
                playerPreviewService.previews(playerPage.getContent(), season);
        Map<Long, Team> teams = teamRepository.findAllById(playerPage.getContent().stream()
                        .map(Human::getTeamId)
                        .filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet()))
                .stream()
                .collect(java.util.stream.Collectors.toMap(Team::getId, team -> team));

        TeamFacilities facilities = teamFacilitiesRepository.findByTeamId(teamId);
        int scoutingLevel = Math.max(1, Math.min(20,
                facilities != null ? facilities.getScoutingLevel() : 1));
        int errorMargin = (20 - scoutingLevel) * 3;
        int scoutingAccuracy = scoutingLevel * 5;

        List<Map<String, Object>> content = new ArrayList<>();
        for (Human player : playerPage.getContent()) {
            Team team = teams.get(player.getTeamId());
            if (team == null) continue;

            Random stableScoutingRandom =
                    new Random(Objects.hash(teamId, player.getId(), season));
            double noise = errorMargin > 0
                    ? stableScoutingRandom.nextDouble() * 2 * errorMargin - errorMargin
                    : 0;
            Map<String, Object> playerInfo = new LinkedHashMap<>();
            playerInfo.put("id", player.getId());
            playerInfo.put("name", player.getName());
            playerInfo.put("position", player.getPosition());
            playerInfo.put("age", player.getAge());
            playerInfo.put("estimatedRating", Math.max(1, Math.round(player.getRating() + noise)));
            playerInfo.put("scoutingAccuracy", scoutingAccuracy);
            playerInfo.put("teamId", team.getId());
            playerInfo.put("teamName", team.getName());
            playerInfo.put("transferValue", player.getTransferValue());
            addPlayerPreview(playerInfo, player, previews.get(player.getId()));
            addMarketState(playerInfo, marketStates.getOrDefault(player.getId(),
                    com.footballmanagergamesimulator.service.PlayerMarketAvailabilityService.MarketState.available()));
            content.add(playerInfo);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("page", playerPage.getNumber());
        result.put("size", playerPage.getSize());
        result.put("totalElements", playerPage.getTotalElements());
        result.put("totalPages", playerPage.getTotalPages());
        return result;
    }

    /** Source-compatible delegate for tests and older internal callers. */
    Map<String, Object> getAvailablePlayersPage(long teamId, String position, int page, int size,
                                                String sort, String direction, long minValue, long maxValue) {
        return getAvailablePlayersPage(teamId, position, page, size, sort, direction,
                minValue, maxValue, Double.MAX_VALUE, "AVAILABLE");
    }

    /** Source-compatible delegate with a maximum-rating filter for focused tests. */
    Map<String, Object> getAvailablePlayersPage(long teamId, String position, int page, int size,
                                                String sort, String direction, long minValue, long maxValue,
                                                double maxRating) {
        return getAvailablePlayersPage(teamId, position, page, size, sort, direction,
                minValue, maxValue, maxRating, "AVAILABLE");
    }

    private void addMarketState(Map<String, Object> target,
                                com.footballmanagergamesimulator.service.PlayerMarketAvailabilityService.MarketState state) {
        if (state == null) {
            state = com.footballmanagergamesimulator.service.PlayerMarketAvailabilityService.MarketState.available();
        }
        target.put("marketStatus", state.status());
        target.put("transferredThisSeason", state.transferredThisSeason());
        target.put("loanedThisSeason", state.loanedThisSeason());
        target.put("loaned", state.loaned());
        target.put("parentTeamId", state.parentTeamId());
        target.put("parentTeamName", state.parentTeamName());
        target.put("loanTeamId", state.loanTeamId());
        target.put("loanTeamName", state.loanTeamName());
    }

    private void addPlayerPreview(Map<String, Object> target, Human player) {
        addPlayerPreview(target, player, null);
    }

    private void addPlayerPreview(Map<String, Object> target, Human player,
                                  com.footballmanagergamesimulator.service.PlayerPreviewService.Preview preview) {
        target.put("wage", player.getWage());
        target.put("contractEndSeason", player.getContractEndSeason());
        target.put("fitness", player.getFitness());
        target.put("morale", player.getMorale());
        target.put("currentStatus", player.getCurrentStatus());
        target.put("releaseClause", player.getReleaseClause());
        target.put("seasonAppearances", preview == null ? 0 : preview.appearances());
        target.put("seasonGoals", preview == null ? 0 : preview.goals());
        target.put("seasonAssists", preview == null ? 0 : preview.assists());
        target.put("importantAttributes", preview == null ? List.of() : preview.importantAttributes());
    }

    private void executeTransfer(Human player, Team sellingTeam, Team buyingTeam, long fee, int season) {
        if (player.isWillNeverLeave()) {
            throw new IllegalStateException("This player will never leave their current club");
        }
        if (!Objects.equals(player.getTeamId(), sellingTeam.getId())) {
            throw new IllegalStateException("Player no longer belongs to the selling club");
        }
        long playerWage = player.getWage();

        // Calculate sell-on fee before changing ownership
        long sellOnFee = 0;
        long sellOnRecipientTeamId = 0;
        if (player.getSellOnPercentage() > 0 && player.getSellOnClubId() > 0) {
            sellOnFee = (long) (fee * player.getSellOnPercentage() / 100.0);
            sellOnRecipientTeamId = player.getSellOnClubId();
        }

        // Set up sell-on clause for the selling team on the player's new contract
        long previousTeamId = sellingTeam.getId();

        player.setTeamId(buyingTeam.getId());
        contractSignature.mark(player);
        player.setSeasonMatchesPlayed(0);
        player.setConsecutiveBenched(0);
        // Clear old sell-on clause; the buying team can set a new one during contract negotiation
        player.setSellOnPercentage(0);
        player.setSellOnClubId(0);
        humanRepository.save(player);

        // Record financial transactions
        long effectiveFeeForSeller = fee - sellOnFee;

        financeService.recordExpense(buyingTeam.getId(), season, 0,
                "TRANSFER_BUY", "Bought " + player.getName(), fee);
        buyingTeam = teamRepository.findById(buyingTeam.getId()).orElse(buyingTeam);
        buyingTeam.setTransferBudget(buyingTeam.getTransferBudget() - fee);
        buyingTeam.setSalaryBudget(buyingTeam.getSalaryBudget() + playerWage);
        teamRepository.save(buyingTeam);

        financeService.recordTransaction(sellingTeam.getId(), season, 0,
                "TRANSFER_SALE", "Sold " + player.getName(), effectiveFeeForSeller);
        sellingTeam = teamRepository.findById(sellingTeam.getId()).orElse(sellingTeam);
        sellingTeam.setSalaryBudget(sellingTeam.getSalaryBudget() - playerWage);
        // The sale money must actually ARRIVE: without this the fee existed
        // only as a ledger line and the selling club could never spend it.
        sellingTeam.setTransferBudget(sellingTeam.getTransferBudget() + effectiveFeeForSeller);
        teamRepository.save(sellingTeam);

        // Pay sell-on fee to the third-party club
        if (sellOnFee > 0 && sellOnRecipientTeamId > 0) {
            financeService.recordTransaction(sellOnRecipientTeamId, season, 0,
                    "SELL_ON_FEE", "Sell-on fee for " + player.getName() + " (" + player.getSellOnPercentage() + "%)", sellOnFee);
        }

        Transfer transfer = new Transfer();
        transfer.setPlayerId(player.getId());
        transfer.setPlayerName(player.getName());
        transfer.setPlayerTransferValue(fee);
        transfer.setSellTeamId(sellingTeam.getId());
        transfer.setSellTeamName(sellingTeam.getName());
        transfer.setBuyTeamId(buyingTeam.getId());
        transfer.setBuyTeamName(buyingTeam.getName());
        transfer.setRating(player.getRating());
        transfer.setSeasonNumber(season);
        transfer.setPlayerAge(player.getAge());
        transfer.setSellOnFeePaid(sellOnFee);
        transfer.setSellOnRecipientTeamId(sellOnRecipientTeamId);
        transferRepository.save(transfer);
    }

    private List<TransferOffer> removeStaleOffers(List<TransferOffer> offers,
                                                  Map<Long, Human> players) {
        Set<Long> stalePlayerIds = offers.stream()
                .filter(offer -> {
                    Human player = players.get(offer.getPlayerId());
                    return player == null || player.isRetired() || player.isWillNeverLeave()
                            || !Objects.equals(player.getTeamId(), offer.getToTeamId());
                })
                .map(TransferOffer::getPlayerId)
                .collect(java.util.stream.Collectors.toSet());
        transferOfferLifecycleService.removeActiveOffersForPlayers(stalePlayerIds);
        if (stalePlayerIds.isEmpty()) return offers;
        return offers.stream()
                .filter(offer -> !stalePlayerIds.contains(offer.getPlayerId()))
                .toList();
    }

    /** Inbox message with a button that opens (and later reopens) the personal-terms negotiation. */
    private void sendNegotiationInvite(TransferOffer offer, int season, int roundNumber, String title, String content) {
        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(offer.getFromTeamId());
        inbox.setSeasonNumber(season);
        inbox.setRoundNumber(roundNumber);
        inbox.setTitle(title);
        inbox.setContent(content);
        inbox.setCategory("transfer");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        inbox.setActionType(ManagerInbox.ACTION_NEGOTIATE_TRANSFER);
        inbox.setActionRefId(offer.getId());
        managerInboxRepository.save(inbox);
    }

    /** Personal terms agreed: the fee-agreed offer becomes the actual transfer. */
    @Override
    @Transactional
    public void complete(long transferOfferId) {
        TransferOffer offer = transferOfferRepository.findByIdForUpdate(transferOfferId)
                .filter(o -> TransferOffer.STATUS_FEE_AGREED.equals(o.getStatus()))
                .orElseThrow(() -> new IllegalStateException("The fee agreement is no longer valid"));
        Human player = humanRepository.findByIdForUpdate(offer.getPlayerId()).orElseThrow();
        Team sellingTeam = teamRepository.findById(offer.getToTeamId()).orElseThrow();
        Team buyingTeam = teamRepository.findById(offer.getFromTeamId()).orElseThrow();
        executeTransfer(player, sellingTeam, buyingTeam, offer.getOfferAmount(), offer.getSeasonNumber());
        applyExtras(offer, player, sellingTeam, buyingTeam);
        offer.setStatus("accepted");
        transferOfferRepository.save(offer);
        transferOfferLifecycleService.removeActiveOffersForPlayer(player.getId());
        Round round = roundRepository.findById(1L).orElse(new Round());
        sendInboxMessage(buyingTeam.getId(), (int) round.getSeason(), (int) round.getRound(), "Transfer Completed",
                player.getName() + " has agreed personal terms and joins from " + sellingTeam.getName() + ".",
                "transfer");
    }

    /**
     * I sold this player with a buy-back clause: I take him back at the agreed price, as long as the clause runs
     * and the window is open. No talks: the clause was agreed when I sold him.
     */
    @PostMapping("/buyBack/{playerId}")
    @Transactional
    public ResponseEntity<?> exerciseBuyBack(HttpServletRequest request, @PathVariable long playerId,
                                             @RequestBody(required = false) Map<String, Object> body) {
        var actor = clubActionAuthorizationService.authorize(request, body == null ? Map.of() : body,
                com.footballmanagergamesimulator.service.ClubActionAuthorizationService.Action.TRANSFER);
        Human player = humanRepository.findByIdForUpdate(playerId).orElse(null);
        Round round = roundRepository.findById(1L).orElse(new Round());
        if (player == null || player.getBuyBackClubId() != actor.teamId()
                || player.getBuyBackUntilSeason() < round.getSeason() || player.getTeamId() == null) {
            return talksError(400, "NO_BUY_BACK", "You hold no buy-back clause for this player.");
        }
        if (!competitionController.isTransferWindowOpen()) {
            return talksError(400, "WINDOW_CLOSED", "Transfer window is not open.");
        }
        ResponseEntity<Map<String, Object>> unaffordable = unaffordable(actor, player.getBuyBackPrice());
        if (unaffordable != null) return unaffordable;
        Team seller = teamRepository.findById(player.getTeamId()).orElse(null);
        Team me = teamRepository.findById(actor.teamId()).orElse(null);
        if (seller == null || me == null) return talksError(400, "TEAM_NOT_FOUND", "Team not found");
        long price = player.getBuyBackPrice();
        player.setBuyBackClubId(0);
        player.setBuyBackPrice(0);
        player.setBuyBackUntilSeason(0);
        executeTransfer(player, seller, me, price, (int) round.getSeason());
        transferOfferLifecycleService.removeActiveOffersForPlayer(player.getId());
        sendInboxMessage(me.getId(), (int) round.getSeason(), (int) round.getRound(), "Buy-Back Completed",
                player.getName() + " is back at the club for " + String.format("%,d", price) + ".", "transfer");
        return ResponseEntity.ok(Map.of("success", true, "message", player.getName() + " is back at the club."));
    }

    /** The extras agreed with the selling club: sell-on and buy-back on the player, instalments booked. */
    private void applyExtras(TransferOffer offer, Human player, Team sellingTeam, Team buyingTeam) {
        Round round = roundRepository.findById(1L).orElse(new Round());
        if (offer.getSellOnPercent() > 0) {
            player.setSellOnPercentage(offer.getSellOnPercent());
            player.setSellOnClubId(sellingTeam.getId());
        }
        if (offer.getBuyBackPrice() > 0) {
            player.setBuyBackClubId(sellingTeam.getId());
            player.setBuyBackPrice(offer.getBuyBackPrice());
            player.setBuyBackUntilSeason((int) round.getSeason() + com.footballmanagergamesimulator.service.TransferPoints.BUY_BACK_SEASONS);
        }
        humanRepository.save(player);
        if (offer.getInstallmentCount() > 0) {
            transferInstallmentService.schedule(player.getId(), player.getName(), buyingTeam.getId(), sellingTeam.getId(),
                    offer.getInstallmentCount(), offer.getInstallmentAmount(), (int) round.getSeason());
        }
        if (offer.getGoalsBonusAmount() > 0 || offer.getTitleBonusAmount() > 0) {
            transferBonusService.schedule(player.getId(), player.getName(), buyingTeam.getId(), sellingTeam.getId(),
                    offer.getGoalsBonusTarget(), offer.getGoalsBonusAmount(), offer.getTitleBonusAmount(), gameDay());
        }
    }

    /** Personal terms failed: the fee agreement lapses. */
    @Override
    @Transactional
    public void collapse(long transferOfferId, String reason) {
        transferOfferRepository.findById(transferOfferId).ifPresent(offer -> {
            offer.setStatus(TransferOffer.STATUS_TERMS_FAILED);
            transferOfferRepository.save(offer);
            Round round = roundRepository.findById(1L).orElse(new Round());
            sendInboxMessage(offer.getFromTeamId(), (int) round.getSeason(), (int) round.getRound(), "Transfer Collapsed",
                    "The transfer of " + offer.getPlayerName() + " collapsed: " + reason + ".", "transfer");
        });
    }

    private void sendInboxMessage(long teamId, int season, int roundNumber, String title, String content, String category) {
        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(teamId);
        inbox.setSeasonNumber(season);
        inbox.setRoundNumber(roundNumber);
        inbox.setTitle(title);
        inbox.setContent(content);
        inbox.setCategory(category);
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        managerInboxRepository.save(inbox);
    }

    /**
     * Browse free agent players (teamId = 0, not retired, player type only).
     */
    @GetMapping("/freeAgents/{teamId}")
    public List<Map<String, Object>> getFreeAgents(@PathVariable(name = "teamId") long teamId) {
        List<Human> freeAgents = humanRepository.findAllByTeamIdAndTypeId(0L, 1L);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Human player : freeAgents) {
            if (player.isRetired()) continue;
            if (player.isWillNeverLeave()) continue;

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", player.getId());
            info.put("name", player.getName());
            info.put("position", player.getPosition());
            info.put("age", player.getAge());
            info.put("rating", Math.round(player.getRating() * 10) / 10.0);
            info.put("wage", player.getWage());
            info.put("transferValue", calculateTransferValue(player.getAge(), player.getPosition(), player.getRating()));
            result.add(info);
        }

        return result;
    }

    /**
     * Sign a free agent player (no transfer fee, wage negotiation only).
     */
    @PostMapping("/signFreeAgent")
    public ResponseEntity<?> signFreeAgent(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long playerId = ((Number) body.get("playerId")).longValue();
        long offeredWage = ((Number) body.get("offeredWage")).longValue();
        int contractYears = body.containsKey("contractYears") ? ((Number) body.get("contractYears")).intValue() : 3;

        Human player = humanRepository.findById(playerId).orElse(null);
        if (player == null || player.isRetired()) {
            return ResponseEntity.badRequest().body("Player not found or retired");
        }
        if (player.isWillNeverLeave()) {
            return ResponseEntity.status(409).body("This player is protected from joining another club");
        }
        if (player.getTeamId() != null && player.getTeamId() != 0L) {
            return ResponseEntity.badRequest().body("Player is not a free agent");
        }

        long humanTeamId = clubActionAuthorizationService.authorize(request, body,
                com.footballmanagergamesimulator.service.ClubActionAuthorizationService.Action.ACQUISITION).teamId();
        Team team = teamRepository.findById(humanTeamId).orElse(null);
        if (team == null) {
            return ResponseEntity.badRequest().body("Team not found");
        }

        // Free agents have a minimum wage demand based on rating
        long wageDemand = (long) (Math.pow(player.getRating() / 10.0, 2.5) * 400);
        wageDemand = Math.max(wageDemand, (long) (player.getWage() * 0.8));

        if (offeredWage < wageDemand * 0.8) {
            return ResponseEntity.badRequest().body("Wage offer too low. Player demands at least " + wageDemand);
        }

        // Accept if offered wage >= 80% of demand (with some negotiation luck)
        boolean accepted = offeredWage >= wageDemand || (offeredWage >= wageDemand * 0.8 && random.nextDouble() < 0.5);

        if (!accepted) {
            return ResponseEntity.ok(Map.of("success", false, "message",
                    player.getName() + " rejected your wage offer. They demand at least " + wageDemand,
                    "wageDemand", wageDemand));
        }

        Round round = roundRepository.findById(1L).orElse(new Round());
        int season = (int) round.getSeason();

        player.setTeamId(humanTeamId);
        player.setWage(offeredWage);
        player.setContractEndSeason(season + contractYears);
        contractSignature.mark(player);
        player.setSeasonMatchesPlayed(0);
        player.setConsecutiveBenched(0);
        player.setMorale(75);
        humanRepository.save(player);
        transferOfferLifecycleService.removeActiveOffersForPlayer(player.getId());

        team.setSalaryBudget(team.getSalaryBudget() + offeredWage);
        teamRepository.save(team);

        sendInboxMessage(humanTeamId, season, (int) round.getRound(), "Free Agent Signed",
                player.getName() + " has signed a " + contractYears + "-year contract as a free agent with a wage of " + offeredWage + ".",
                "transfer");

        return ResponseEntity.ok(Map.of("success", true, "message",
                player.getName() + " has signed for the club!"));
    }

    public long calculateTransferValue(long age, String position, double rating) {
        return com.footballmanagergamesimulator.service.TransferValuation.of(age, rating);
    }

}
