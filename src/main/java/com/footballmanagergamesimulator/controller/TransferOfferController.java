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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/transferOffer")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class TransferOfferController {

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

    private final Random random = new Random();

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
        List<TransferOffer> all = new ArrayList<>();
        all.addAll(pending);
        all.addAll(negotiating);
        all.addAll(counter);
        Set<Long> playerIds = all.stream().map(TransferOffer::getPlayerId)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, Human> players = humanRepository.findAllById(playerIds).stream()
                .collect(java.util.stream.Collectors.toMap(Human::getId, player -> player));
        return removeStaleOffers(all, players);
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

    @PostMapping("/makeOffer")
    @Transactional
    public ResponseEntity<?> makeOffer(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        if (!competitionController.isTransferWindowOpen()) {
            return ResponseEntity.badRequest().body("Transfer window is not open. You can only make offers during the transfer window (end of season).");
        }

        long playerId = ((Number) body.get("playerId")).longValue();
        long offerAmount = ((Number) body.get("offerAmount")).longValue();

        // Lock ownership until the offer response and any immediate transfer finish.
        Optional<Human> playerOpt = humanRepository.findByIdForUpdate(playerId);
        if (playerOpt.isEmpty()) {
            return ResponseEntity.badRequest().body("Player not found");
        }

        Human player = playerOpt.get();
        long humanTeamId = userContext.getTeamId(request);

        if (!coachPermissionService.canBuyPlayers(humanTeamId)) {
            return ResponseEntity.status(403).body("Restricționat de patron: nu poți cumpăra jucători.");
        }
        long buyCap = coachPermissionService.transferBudgetCap(humanTeamId);
        if (buyCap >= 0 && offerAmount > buyCap) {
            return ResponseEntity.status(403).body("Restricționat de patron: oferta depășește plafonul de transfer (" + buyCap + ").");
        }

        if (player.getTeamId() == null || player.getTeamId() == humanTeamId) {
            return ResponseEntity.badRequest().body("Cannot buy a player from your own team");
        }

        if (player.isRetired()) {
            return ResponseEntity.badRequest().body("Player is retired");
        }
        if (player.isWillNeverLeave()) {
            return ResponseEntity.status(409).body("This player will never leave their current club");
        }

        Team humanTeam = teamRepository.findById(humanTeamId).orElse(null);
        if (humanTeam == null) {
            return ResponseEntity.badRequest().body("Human team not found");
        }

        if (offerAmount > humanTeam.getTransferBudget()) {
            return ResponseEntity.badRequest().body("Insufficient transfer budget");
        }

        Team sellingTeam = teamRepository.findById(player.getTeamId()).orElse(null);
        if (sellingTeam == null) {
            return ResponseEntity.badRequest().body("Selling team not found");
        }

        long askingPrice = calculateTransferValue(player.getAge(), player.getPosition(), player.getRating());
        Round round = roundRepository.findById(1L).orElse(new Round());
        int season = (int) round.getSeason();

        TransferOffer offer = new TransferOffer();
        offer.setPlayerId(player.getId());
        offer.setPlayerName(player.getName());
        offer.setFromTeamId(humanTeamId);
        offer.setFromTeamName(humanTeam.getName());
        offer.setToTeamId(sellingTeam.getId());
        offer.setToTeamName(sellingTeam.getName());
        offer.setOfferAmount(offerAmount);
        offer.setAskingPrice(askingPrice);
        offer.setSeasonNumber(season);
        offer.setDirection("outgoing");
        offer.setCreatedAt(System.currentTimeMillis());

        // Release clause enforcement: if player has a release clause and offer meets it, auto-accept
        if (player.getReleaseClause() > 0 && offerAmount >= player.getReleaseClause()) {
            offer.setStatus("accepted");
            transferOfferRepository.save(offer);
            executeTransfer(player, sellingTeam, humanTeam, offerAmount, season);
            transferOfferLifecycleService.removeActiveOffersForPlayer(player.getId());
            sendInboxMessage(humanTeamId, season, (int) round.getRound(), "Release Clause Triggered",
                    "Your offer of " + offerAmount + " has triggered the release clause for " +
                    player.getName() + ". The transfer is complete!",
                    "transfer");
            return ResponseEntity.ok(offer);
        }

        // AI responds immediately
        if (offerAmount >= askingPrice) {
            // Accept - execute transfer
            offer.setStatus("accepted");
            transferOfferRepository.save(offer);
            executeTransfer(player, sellingTeam, humanTeam, offerAmount, season);
            transferOfferLifecycleService.removeActiveOffersForPlayer(player.getId());
            sendInboxMessage(humanTeamId, season, (int) round.getRound(), "Transfer Accepted",
                    sellingTeam.getName() + " have accepted your offer of " + offerAmount +
                    " for " + player.getName() + ". The transfer is complete!",
                    "transfer");
        } else if (offerAmount >= (long)(askingPrice * 0.8)) {
            // Counter with asking price
            offer.setStatus("counter");
            transferOfferRepository.save(offer);
            sendInboxMessage(humanTeamId, season, (int) round.getRound(), "Transfer Counter-Offer",
                    sellingTeam.getName() + " have rejected your offer of " + offerAmount +
                    " for " + player.getName() + " but are willing to negotiate. " +
                    "Their asking price is " + askingPrice + ".",
                    "transfer");
        } else {
            // Reject
            offer.setStatus("rejected");
            transferOfferRepository.save(offer);
            sendInboxMessage(humanTeamId, season, (int) round.getRound(), "Transfer Rejected",
                    sellingTeam.getName() + " have rejected your offer of " + offerAmount +
                    " for " + player.getName() + ". The offer was too low.",
                    "transfer");
        }

        return ResponseEntity.ok(offer);
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

        // Get IDs of players already transferred this season (can't be re-sold)
        Round round = roundRepository.findById(1L).orElse(new Round());
        int season = (int) round.getSeason();
        List<Transfer> seasonTransfers = transferRepository.findAllBySeasonNumber(season);
        Set<Long> alreadyTransferredIds = new HashSet<>();
        for (Transfer t : seasonTransfers) {
            alreadyTransferredIds.add(t.getPlayerId());
        }

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
            if (alreadyTransferredIds.contains(player.getId())) continue; // already transferred this window
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
            playerInfo.put("transferValue", calculateTransferValue(player.getAge(), player.getPosition(), player.getRating()));

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
            @RequestParam(name = "direction", defaultValue = "desc") String direction) {

        int safePage = Math.max(0, page);
        int safeSize = Math.max(10, Math.min(100, size));
        Set<String> allowedSorts = Set.of("rating", "age", "transferValue", "name", "position");
        String sortField = allowedSorts.contains(sort) ? sort : "rating";
        Sort.Direction sortDirection = "asc".equalsIgnoreCase(direction)
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        PageRequest pageable = PageRequest.of(safePage, safeSize, Sort.by(sortDirection, sortField));

        Page<Human> playerPage = position == null || position.isBlank() || "ALL".equals(position)
                ? humanRepository.findAllByTypeIdAndRetiredFalseAndWillNeverLeaveFalseAndTeamIdIsNotNullAndTeamIdNot(
                        1L, teamId, pageable)
                : humanRepository.findAllByTypeIdAndRetiredFalseAndWillNeverLeaveFalseAndTeamIdIsNotNullAndTeamIdNotAndPosition(
                        1L, teamId, position, pageable);

        int season = (int) roundRepository.findById(1L).orElse(new Round()).getSeason();
        Set<Long> alreadyTransferredIds = transferRepository.findAllBySeasonNumber(season).stream()
                .map(Transfer::getPlayerId)
                .collect(java.util.stream.Collectors.toSet());
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
            if (alreadyTransferredIds.contains(player.getId())) continue;
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
            playerInfo.put("transferValue",
                    calculateTransferValue(player.getAge(), player.getPosition(), player.getRating()));
            content.add(playerInfo);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("page", playerPage.getNumber());
        result.put("size", playerPage.getSize());
        result.put("totalElements", Math.max(0,
                playerPage.getTotalElements() - alreadyTransferredIds.size()));
        result.put("totalPages", playerPage.getTotalPages());
        return result;
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

        long humanTeamId = userContext.getTeamId(request);
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
        double baseValue = Math.pow(rating, 3) * 20;
        double ageMultiplier;
        if (age <= 21) ageMultiplier = 1.3;
        else if (age <= 23) ageMultiplier = 1.1;
        else if (age <= 25) ageMultiplier = 1.0;
        else if (age <= 27) ageMultiplier = 0.95;
        else if (age <= 29) ageMultiplier = 0.75;
        else if (age <= 31) ageMultiplier = 0.45;
        else if (age <= 33) ageMultiplier = 0.2;
        else ageMultiplier = 0.08;
        return Math.max(50_000L, (long) (baseValue * ageMultiplier));
    }

}
