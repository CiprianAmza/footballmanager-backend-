package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.util.TypeNames;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/contract")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class ContractController {

    static final double MIN_WAGE_FACTOR = 0.60;
    static final double MAX_WAGE_FACTOR = 1.40;

    @Autowired
    private HumanRepository humanRepository;

    @Autowired
    private RoundRepository roundRepository;

    @Autowired
    private ManagerInboxRepository managerInboxRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private UserContext userContext;

    @Autowired
    private com.footballmanagergamesimulator.service.ContractSignature contractSignature;

    @Autowired
    private com.footballmanagergamesimulator.service.CoachPermissionService coachPermissionService;

    @Autowired
    private com.footballmanagergamesimulator.service.ClubActionAuthorizationService clubActionAuthorizationService;

    /** True when the owner has barred the coach from contract negotiation for this team. */
    private boolean contractsLocked(long teamId) {
        return !coachPermissionService.canNegotiateContracts(teamId);
    }

    /**
     * Renewal demands are anchored to the existing contract. Performance,
     * potential, age and morale can move the request, but never outside ±40%.
     * Package-private so the negotiation rule can be unit tested directly.
     */
    long calculateWageDemand(Human player) {
        long currentWage = player.getWage();
        if (currentWage <= 0) {
            return com.footballmanagergamesimulator.service.WageService.baseWage(player.getRating());
        }

        double multiplier = 1.0;
        int age = player.getAge();
        if (age <= 22 && player.getPotentialAbility() > player.getRating() + 10) multiplier += 0.08;
        else if (age >= 33) multiplier -= 0.12;
        else if (age >= 30) multiplier -= 0.05;

        if (player.getMorale() < 60) multiplier += 0.10;
        else if (player.getMorale() >= 90) multiplier -= 0.03;
        if (player.isWantsTransfer()) multiplier += 0.15;

        int matchesPlayed = player.getSeasonMatchesPlayed();
        if (matchesPlayed >= 30) multiplier += 0.15;
        else if (matchesPlayed >= 20) multiplier += 0.10;
        else if (matchesPlayed >= 10) multiplier += 0.05;
        else if (matchesPlayed <= 3) multiplier -= 0.05;

        long minimum = Math.round(currentWage * MIN_WAGE_FACTOR);
        long maximum = Math.round(currentWage * MAX_WAGE_FACTOR);
        long demanded = Math.round(currentWage * multiplier);
        return Math.max(minimum, Math.min(maximum, demanded));
    }

    @GetMapping("/demand/{playerId}")
    public ResponseEntity<?> getRenewalDemand(@PathVariable long playerId) {
        Human player = humanRepository.findById(playerId).orElse(null);
        if (player == null || player.isRetired()) {
            return ResponseEntity.notFound().build();
        }
        long currentWage = player.getWage();
        return ResponseEntity.ok(Map.of(
                "currentWage", currentWage,
                "minimumWage", Math.round(currentWage * MIN_WAGE_FACTOR),
                "wageDemand", calculateWageDemand(player),
                "maximumWage", Math.round(currentWage * MAX_WAGE_FACTOR)
        ));
    }

    // Renewals go through round-based talks with the agent: POST /contract/negotiation/start (design §3.3).

    @GetMapping("/expiring/{teamId}")
    public List<Map<String, Object>> getExpiringContracts(@PathVariable(name = "teamId") long teamId) {
        Round round = roundRepository.findById(1L).orElse(new Round());
        int currentSeason = (int) round.getSeason();
        int expiryThreshold = currentSeason + 1;

        List<Human> expiringPlayers = humanRepository
                .findAllByTeamIdAndTypeIdAndContractEndSeasonLessThanEqual(teamId, TypeNames.PLAYER_TYPE, expiryThreshold);

        return expiringPlayers.stream()
                .filter(p -> !p.isRetired())
                .map(player -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("id", player.getId());
                    info.put("name", player.getName());
                    info.put("position", player.getPosition());
                    info.put("age", player.getAge());
                    info.put("rating", player.getRating());
                    info.put("contractEndSeason", player.getContractEndSeason());
                    info.put("wage", player.getWage());
                    info.put("wageDemand", calculateWageDemand(player));
                    info.put("releaseClause", player.getReleaseClause());
                    info.put("morale", player.getMorale());
                    info.put("wantsTransfer", player.isWantsTransfer());
                    info.put("consecutiveBenched", player.getConsecutiveBenched());
                    return info;
                })
                .collect(Collectors.toList());
    }

    /**
     * Get all squad contracts (not just expiring) for contract management page.
     */
    @GetMapping("/squad/{teamId}")
    public List<Map<String, Object>> getSquadContracts(@PathVariable(name = "teamId") long teamId) {
        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);

        return players.stream()
                .filter(p -> !p.isRetired())
                .sorted((a, b) -> Double.compare(b.getRating(), a.getRating()))
                .map(player -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("id", player.getId());
                    info.put("name", player.getName());
                    info.put("position", player.getPosition());
                    info.put("age", player.getAge());
                    info.put("rating", Math.round(player.getRating() * 10) / 10.0);
                    info.put("contractEndSeason", player.getContractEndSeason());
                    info.put("wage", player.getWage());
                    info.put("wageDemand", calculateWageDemand(player));
                    info.put("morale", Math.round(player.getMorale()));
                    info.put("wantsTransfer", player.isWantsTransfer());
                    info.put("consecutiveBenched", player.getConsecutiveBenched());
                    info.put("seasonMatchesPlayed", player.getSeasonMatchesPlayed());
                    return info;
                })
                .collect(Collectors.toList());
    }

    /**
     * Get unhappy players who want transfers.
     */
    @GetMapping("/unhappy/{teamId}")
    public List<Map<String, Object>> getUnhappyPlayers(@PathVariable(name = "teamId") long teamId) {
        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);

        return players.stream()
                .filter(p -> !p.isRetired() && (p.isWantsTransfer() || p.getMorale() < 60))
                .sorted(Comparator.comparingDouble(Human::getMorale))
                .map(player -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("id", player.getId());
                    info.put("name", player.getName());
                    info.put("position", player.getPosition());
                    info.put("age", player.getAge());
                    info.put("rating", Math.round(player.getRating() * 10) / 10.0);
                    info.put("morale", Math.round(player.getMorale()));
                    info.put("wantsTransfer", player.isWantsTransfer());
                    info.put("consecutiveBenched", player.getConsecutiveBenched());
                    info.put("seasonMatchesPlayed", player.getSeasonMatchesPlayed());
                    info.put("contractEndSeason", player.getContractEndSeason());
                    info.put("wage", player.getWage());

                    String reason = "";
                    if (player.isWantsTransfer()) reason = "Wants to leave the club";
                    else if (player.getConsecutiveBenched() >= 5) reason = "Frustrated by lack of playing time";
                    else if (player.getMorale() < 50) reason = "Very low morale";
                    else reason = "Unhappy";
                    info.put("reason", reason);

                    return info;
                })
                .collect(Collectors.toList());
    }

    /**
     * Sign a pre-contract with a player whose contract expires at the end of the current season.
     * The player will join your team for free when their contract expires.
     */
    @PostMapping("/preContract")
    public ResponseEntity<?> signPreContract(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long playerId = ((Number) body.get("playerId")).longValue();
        long offeredWage = ((Number) body.get("offeredWage")).longValue();
        int contractYears = body.containsKey("contractYears") ? ((Number) body.get("contractYears")).intValue() : 3;

        Human player = humanRepository.findById(playerId).orElse(null);
        if (player == null || player.isRetired()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Player not found or retired"));
        }
        if (player.isWillNeverLeave()) {
            return ResponseEntity.status(409).body(Map.of("success", false,
                    "message", "This player has committed his whole career to one club and will never be available"));
        }

        long humanTeamId = clubActionAuthorizationService.authorize(request, body,
                com.footballmanagergamesimulator.service.ClubActionAuthorizationService.Action.ACQUISITION).teamId();
        if (player.getTeamId() != null && player.getTeamId() == humanTeamId) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Cannot sign a pre-contract with your own player"));
        }

        Round round = roundRepository.findById(1L).orElse(new Round());
        int currentSeason = (int) round.getSeason();

        // Can only sign pre-contract if player's contract expires at end of current season
        if (player.getContractEndSeason() > currentSeason) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "Player's contract doesn't expire this season (expires Season " + player.getContractEndSeason() + ")"));
        }

        // Check if player already has a pre-contract
        if (player.getPreContractTeamId() > 0) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "Player has already signed a pre-contract with another club"));
        }

        long wageDemand = calculateWageDemand(player);

        if (offeredWage < wageDemand * 0.8) {
            return ResponseEntity.ok(Map.of("success", false,
                    "message", player.getName() + " rejected the pre-contract. They demand at least " + wageDemand,
                    "wageDemand", wageDemand));
        }

        boolean accepted = offeredWage >= wageDemand
                || (offeredWage >= wageDemand * 0.8 && new Random().nextDouble() < 0.5);

        if (!accepted) {
            return ResponseEntity.ok(Map.of("success", false,
                    "message", player.getName() + " rejected your pre-contract offer.",
                    "wageDemand", wageDemand));
        }

        player.setPreContractTeamId(humanTeamId);
        contractSignature.mark(player);
        humanRepository.save(player);

        // Send inbox notification
        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(humanTeamId);
        inbox.setSeasonNumber(currentSeason);
        inbox.setRoundNumber((int) round.getRound());
        inbox.setTitle("Pre-Contract Signed");
        inbox.setContent(player.getName() + " has agreed to join on a pre-contract. " +
                "They will join at the end of the season when their current contract expires. " +
                "Wage: " + offeredWage + ", Duration: " + contractYears + " years.");
        inbox.setCategory("transfer");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        managerInboxRepository.save(inbox);

        return ResponseEntity.ok(Map.of("success", true,
                "message", player.getName() + " has agreed to a pre-contract! They will join at end of season."));
    }

    /**
     * Get players available for pre-contract signing (contract expires this season, from other teams).
     */
    @GetMapping("/preContractAvailable/{teamId}")
    public List<Map<String, Object>> getPreContractAvailable(@PathVariable(name = "teamId") long teamId) {
        Round round = roundRepository.findById(1L).orElse(new Round());
        int currentSeason = (int) round.getSeason();

        List<Human> allPlayers = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE);

        return allPlayers.stream()
                .filter(p -> !p.isRetired())
                .filter(p -> !p.isWillNeverLeave())
                .filter(p -> p.getTeamId() != null && p.getTeamId() != teamId && p.getTeamId() != 0L)
                .filter(p -> p.getContractEndSeason() <= currentSeason)
                .filter(p -> p.getPreContractTeamId() == 0)
                .map(player -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("id", player.getId());
                    info.put("name", player.getName());
                    info.put("position", player.getPosition());
                    info.put("age", player.getAge());
                    info.put("rating", Math.round(player.getRating() * 10) / 10.0);
                    info.put("currentTeamId", player.getTeamId());
                    String teamName = teamRepository.findById(player.getTeamId()).map(t -> t.getName()).orElse("Unknown");
                    info.put("currentTeamName", teamName);
                    info.put("contractEndSeason", player.getContractEndSeason());
                    info.put("wageDemand", calculateWageDemand(player));
                    return info;
                })
                .collect(Collectors.toList());
    }

    /**
     * Set contract clauses during renewal.
     */
    @PostMapping("/setClauses")
    public ResponseEntity<?> setContractClauses(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long playerId = ((Number) body.get("playerId")).longValue();
        Human player = humanRepository.findById(playerId).orElse(null);
        if (player == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Player not found"));
        }
        long humanTeamId = clubActionAuthorizationService.authorize(request, body,
                com.footballmanagergamesimulator.service.ClubActionAuthorizationService.Action.CONTRACT).teamId();
        if (player.getTeamId() == null || player.getTeamId() != humanTeamId) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Not your player"));
        }

        if (body.containsKey("releaseClause")) {
            player.setReleaseClause(((Number) body.get("releaseClause")).longValue());
        }
        if (body.containsKey("sellOnPercentage")) {
            int pct = ((Number) body.get("sellOnPercentage")).intValue();
            player.setSellOnPercentage(Math.max(0, Math.min(50, pct)));
            player.setSellOnClubId(humanTeamId);
        }
        if (body.containsKey("optionalExtensionYears")) {
            player.setOptionalExtensionYears(Math.max(0, Math.min(2,
                    ((Number) body.get("optionalExtensionYears")).intValue())));
        }
        if (body.containsKey("appearanceBonus")) {
            player.setAppearanceBonus(((Number) body.get("appearanceBonus")).longValue());
        }
        if (body.containsKey("goalBonus")) {
            player.setGoalBonus(((Number) body.get("goalBonus")).longValue());
        }
        if (body.containsKey("relegationWageDrop")) {
            player.setRelegationWageDrop(Math.max(0, Math.min(50,
                    ((Number) body.get("relegationWageDrop")).intValue())));
        }

        humanRepository.save(player);
        return ResponseEntity.ok(Map.of("success", true, "message", "Contract clauses updated for " + player.getName()));
    }

    /**
     * Promise playing time to an unhappy player to settle them.
     */
    @PostMapping("/promisePlayingTime")
    public ResponseEntity<?> promisePlayingTime(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long playerId = ((Number) body.get("playerId")).longValue();

        Optional<Human> playerOpt = humanRepository.findById(playerId);
        if (playerOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Player not found"));
        }

        Human player = playerOpt.get();
        if (player.getTeamId() == null || player.getTeamId() != userContext.getTeamId(request)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Not your player"));
        }
        if (contractsLocked(player.getTeamId())) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Restricționat de patron: nu poți negocia contracte."));
        }

        Random random = new Random();
        Round round = roundRepository.findById(1L).orElse(new Round());
        int currentSeason = (int) round.getSeason();

        // Success chance depends on how unhappy they are
        double successChance;
        if (player.getConsecutiveBenched() >= 7) successChance = 0.2;
        else if (player.getConsecutiveBenched() >= 5) successChance = 0.4;
        else if (player.isWantsTransfer()) successChance = 0.3;
        else successChance = 0.7;

        if (random.nextDouble() < successChance) {
            player.setWantsTransfer(false);
            player.setMorale(Math.min(100, player.getMorale() + 15));
            player.setConsecutiveBenched(0); // Reset the counter
            humanRepository.save(player);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", player.getName() + " has accepted your promise of more playing time and is willing to stay."
            ));
        } else {
            player.setMorale(Math.max(0, player.getMorale() - 5));
            humanRepository.save(player);

            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", player.getName() + " doesn't believe your promises. They still want to leave."
            ));
        }
    }

    // ==================== ROUND-BASED NEGOTIATION (design §3.3) ====================

    @Autowired
    private com.footballmanagergamesimulator.service.ContractNegotiationService negotiationService;

    @Autowired
    private AgentRepository agentRepository;

    /** Opens (or resumes) a renewal negotiation with the player's agent. Blocks the day until it ends. */
    @PostMapping("/negotiation/start")
    public ResponseEntity<?> startNegotiation(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long teamId = clubActionAuthorizationService.authorize(request, body,
                com.footballmanagergamesimulator.service.ClubActionAuthorizationService.Action.CONTRACT).teamId();
        long playerId = Long.parseLong(String.valueOf(body.get("playerId")));
        return negotiationResponse(() -> negotiationService.startRenewal(playerId, teamId));
    }

    /** The inbox button: opens personal terms for a fee-agreed transfer, or returns to the existing talks. */
    @PostMapping("/negotiation/startTransfer")
    public ResponseEntity<?> startTransferNegotiation(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long teamId = clubActionAuthorizationService.authorize(request, body,
                com.footballmanagergamesimulator.service.ClubActionAuthorizationService.Action.TRANSFER).teamId();
        long offerId = Long.parseLong(String.valueOf(body.get("transferOfferId")));
        return negotiationResponse(() -> negotiationService.startTransfer(offerId, teamId));
    }

    @GetMapping("/negotiation/{id}")
    public ResponseEntity<?> getNegotiation(HttpServletRequest request, @PathVariable long id) {
        long teamId = userContext.getTeamId(request);
        return negotiationResponse(() -> negotiationService.get(id, teamId));
    }

    @PostMapping("/negotiation/{id}/offer")
    public ResponseEntity<?> offerInNegotiation(HttpServletRequest request, @PathVariable long id,
                                                @RequestBody Map<String, Object> body) {
        long teamId = clubActionAuthorizationService.authorize(request, body,
                com.footballmanagergamesimulator.service.ClubActionAuthorizationService.Action.CONTRACT).teamId();
        var offer = new com.footballmanagergamesimulator.service.ContractPoints.Offer(
                longOf(body, "wage"), (int) longOf(body, "years"),
                longOf(body, "signingBonus"), longOf(body, "releaseClause"));
        return negotiationResponse(() -> negotiationService.offer(id, teamId, offer));
    }

    @PostMapping("/negotiation/{id}/final")
    public ResponseEntity<?> answerFinalOffer(HttpServletRequest request, @PathVariable long id,
                                              @RequestBody Map<String, Object> body) {
        long teamId = clubActionAuthorizationService.authorize(request, body,
                com.footballmanagergamesimulator.service.ClubActionAuthorizationService.Action.CONTRACT).teamId();
        boolean accept = Boolean.parseBoolean(String.valueOf(body.get("accept")));
        return negotiationResponse(() -> negotiationService.answerFinalOffer(id, teamId, accept));
    }

    @Autowired
    private com.footballmanagergamesimulator.service.PlayerInterestService playerInterestService;

    /** "Ask the agent": would this player talk to my club? */
    @GetMapping("/interest/{playerId}")
    public ResponseEntity<?> playerInterest(HttpServletRequest request, @PathVariable long playerId) {
        long teamId = userContext.getTeamId(request);
        Human player = humanRepository.findById(playerId).orElse(null);
        if (player == null || player.isRetired()) return ResponseEntity.notFound().build();
        List<Human> managers = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE);
        long managerId = managers.isEmpty() ? 0 : managers.get(0).getId();
        var reason = playerInterestService.interest(player, teamId, managerId);
        String agentName = player.getAgentId() > 0
                ? agentRepository.findById(player.getAgentId()).map(Agent::getName).orElse("") : "";
        return ResponseEntity.ok(Map.of(
                "interested", reason == com.footballmanagergamesimulator.service.PlayerInterest.Reason.INTERESTED,
                "reason", reason.name(),
                "agentName", agentName,
                "agentId", player.getAgentId(),
                "personality", PlayerPersonality.of(player).name(),
                "message", reason.agentMessage()));
    }

    /** Renewal with my own player: ask him to fire his agent. */
    @PostMapping("/negotiation/{id}/fireAgent")
    public ResponseEntity<?> askToFireAgent(HttpServletRequest request, @PathVariable long id,
                                            @RequestBody(required = false) Map<String, Object> body) {
        long teamId = clubActionAuthorizationService.authorize(request, body == null ? Map.of() : body,
                com.footballmanagergamesimulator.service.ClubActionAuthorizationService.Action.CONTRACT).teamId();
        return negotiationResponse(() -> negotiationService.askToFireAgent(id, teamId));
    }

    /** The negotiations still open for my club (the reason the day cannot advance). */
    @GetMapping("/negotiation/open")
    public List<Map<String, Object>> openNegotiations(HttpServletRequest request) {
        return negotiationService.openNegotiations(userContext.getTeamId(request)).stream()
                .map(this::negotiationView).toList();
    }

    private static long longOf(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null || String.valueOf(value).isBlank() ? 0 : Long.parseLong(String.valueOf(value));
    }

    private ResponseEntity<?> negotiationResponse(java.util.function.Supplier<ContractNegotiation> action) {
        try {
            return ResponseEntity.ok(negotiationView(action.get()));
        } catch (com.footballmanagergamesimulator.service.ContractNegotiationService.NegotiationException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "code", e.getMessage()));
        }
    }

    Map<String, Object> negotiationView(ContractNegotiation n) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", n.getId());
        view.put("kind", n.getKind());
        view.put("playerId", n.getPlayerId());
        view.put("playerName", humanRepository.findById(n.getPlayerId()).map(Human::getName).orElse(""));
        view.put("agentId", n.getAgentId());
        view.put("agentName", n.getAgentId() > 0
                ? agentRepository.findById(n.getAgentId()).map(Agent::getName).orElse("") : "");
        view.put("personality", n.getPersonality() == null ? null : n.getPersonality().name());
        view.put("relation", n.getRelation());
        view.put("status", n.getStatus().name());
        view.put("round", n.getRound());
        view.put("rounds", n.getRounds());
        view.put("season", n.getSeason());
        view.put("transferOfferId", n.getTransferOfferId());
        view.put("transferFee", n.getTransferFee());
        view.put("fireAgentOutcome", n.getFireAgentOutcome());
        if (n.getRefusalReason() != null) {
            view.put("refusalReason", n.getRefusalReason());
            view.put("refusalMessage", com.footballmanagergamesimulator.service.PlayerInterest.Reason
                    .valueOf(n.getRefusalReason()).agentMessage());
        }
        view.put("agentOffer", Map.of("wage", n.getAgentWage(), "years", n.getAgentYears(),
                "signingBonus", n.getAgentSigningBonus(), "releaseClause", n.getAgentReleaseClause()));
        if (n.getLastYears() > 0) {
            view.put("lastOffer", Map.of("wage", n.getLastWage(), "years", n.getLastYears(),
                    "signingBonus", n.getLastSigningBonus(), "releaseClause", n.getLastReleaseClause()));
        }
        return view;
    }
}
