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
    private com.footballmanagergamesimulator.service.CoachPermissionService coachPermissionService;

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

    @PostMapping("/renew")
    public ResponseEntity<?> renewContract(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long playerId = Long.parseLong(String.valueOf(body.get("playerId")));
        long newWage = Long.parseLong(String.valueOf(body.get("newWage")));

        Optional<Human> playerOpt = humanRepository.findById(playerId);
        if (playerOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Player not found"));
        }

        Human player = playerOpt.get();
        if (player.getTeamId() == null || player.getTeamId() != userContext.getTeamId(request)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Can only renew contracts for your own players"));
        }
        if (contractsLocked(player.getTeamId())) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Restricționat de patron: nu poți renegocia contracte."));
        }

        Round round = roundRepository.findById(1L).orElse(new Round());
        int currentSeason = (int) round.getSeason();

        // Accept both contractYears (relative) and newEndSeason (absolute) for backwards compatibility
        int newEndSeason;
        if (body.containsKey("contractYears")) {
            int contractYears = Integer.parseInt(String.valueOf(body.get("contractYears")));
            newEndSeason = currentSeason + contractYears;
        } else {
            newEndSeason = Integer.parseInt(String.valueOf(body.get("newEndSeason")));
        }

        long wageDemand = calculateWageDemand(player);
        int minDuration = currentSeason + 1;

        // Player accepts if wage >= demand AND duration >= min
        boolean wageAcceptable = newWage >= wageDemand;
        boolean durationAcceptable = newEndSeason >= minDuration;

        if (wageAcceptable && durationAcceptable) {
            long oldWage = player.getWage();
            player.setContractEndSeason(newEndSeason);
            player.setWage(newWage);
            // Renewing contract settles the player
            if (player.isWantsTransfer()) {
                player.setWantsTransfer(false);
                player.setMorale(Math.min(100, player.getMorale() + 10));
            }
            humanRepository.save(player);

            // Update team salary budget with the wage difference
            Team team = teamRepository.findById(player.getTeamId()).orElse(null);
            if (team != null) {
                team.setSalaryBudget(team.getSalaryBudget() + (newWage - oldWage));
                teamRepository.save(team);
            }

            String msg = player.getName() + " has accepted the new contract until Season " + newEndSeason + ".";

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", msg
            ));
        } else {
            StringBuilder reason = new StringBuilder();
            if (!wageAcceptable) {
                reason.append("The wage offer is too low. The player demands at least ").append(wageDemand).append(".");
                if (player.isWantsTransfer()) {
                    reason.append(" (The player wants to leave, making negotiations harder.)");
                }
            }
            if (!durationAcceptable) {
                reason.append(" Contract must extend to at least Season ").append(minDuration).append(".");
            }

            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", player.getName() + " has rejected the contract offer. " + reason.toString().trim(),
                    "wageDemand", wageDemand
            ));
        }
    }

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
                    "message", "This player will retire at the end of their current contract"));
        }

        long humanTeamId = userContext.getTeamId(request);
        if (contractsLocked(humanTeamId)) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Restricționat de patron: nu poți negocia contracte."));
        }
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
        long humanTeamId = userContext.getTeamId(request);
        if (player.getTeamId() == null || player.getTeamId() != humanTeamId) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Not your player"));
        }
        if (contractsLocked(humanTeamId)) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Restricționat de patron: nu poți negocia contracte."));
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
}
