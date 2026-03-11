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

    /**
     * Calculate a player's wage demand based on rating, age, and market value.
     * Higher-rated players demand more. Young stars with high potential demand premium.
     * Unhappy players demand more. Players who want to leave demand much more.
     */
    private long calculateWageDemand(Human player) {
        double rating = player.getRating();
        int age = player.getAge();

        // Base wage from rating (exponential curve)
        long baseWage = (long) (Math.pow(rating / 10.0, 2.5) * 500);

        // Age modifier: young talents with high potential demand a premium
        double ageMultiplier;
        if (age <= 22 && player.getPotentialAbility() > rating + 10) {
            ageMultiplier = 1.2; // young stars know their worth
        } else if (age <= 25) {
            ageMultiplier = 1.1;
        } else if (age <= 29) {
            ageMultiplier = 1.0;
        } else if (age <= 32) {
            ageMultiplier = 0.85;
        } else {
            ageMultiplier = 0.7;
        }

        // Morale modifier: unhappy players demand more
        double moraleMultiplier = 1.0;
        if (player.getMorale() < 60) moraleMultiplier = 1.3;
        else if (player.getMorale() < 80) moraleMultiplier = 1.1;

        // Transfer request: players wanting to leave demand huge wages to stay
        if (player.isWantsTransfer()) moraleMultiplier *= 1.5;

        // Playing time multiplier: players who played many matches demand more (50%-250% premium)
        double playingTimeMultiplier = 1.0;
        int matchesPlayed = player.getSeasonMatchesPlayed();
        if (matchesPlayed >= 30) {
            playingTimeMultiplier = 2.5; // 250% more - undisputed starter
        } else if (matchesPlayed >= 20) {
            playingTimeMultiplier = 1.8; // 80% more - regular starter
        } else if (matchesPlayed >= 10) {
            playingTimeMultiplier = 1.5; // 50% more - rotation player
        }

        // Squad ranking multiplier: top 3 players by rating demand 2x
        double squadRankMultiplier = 1.0;
        if (player.getTeamId() != null) {
            List<Human> teamPlayers = humanRepository.findAllByTeamIdAndTypeId(player.getTeamId(), 1);
            teamPlayers.sort((a, b) -> Double.compare(b.getRating(), a.getRating()));
            for (int i = 0; i < Math.min(3, teamPlayers.size()); i++) {
                if (teamPlayers.get(i).getId() == player.getId()) {
                    squadRankMultiplier = 2.0;
                    break;
                }
            }
        }

        // Never below current wage * 0.9
        long demanded = (long) (baseWage * ageMultiplier * moraleMultiplier
                * Math.max(playingTimeMultiplier, squadRankMultiplier));
        return Math.max(demanded, (long) (player.getWage() * 0.9));
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

        // Negotiation: if within 80% of demand, 50% chance of acceptance
        boolean negotiated = false;
        if (!wageAcceptable && newWage >= (long) (wageDemand * 0.8) && durationAcceptable) {
            Random random = new Random();
            if (random.nextDouble() < 0.5) {
                wageAcceptable = true;
                negotiated = true;
            }
        }

        if (wageAcceptable && durationAcceptable) {
            long oldWage = player.getWage();
            player.setContractEndSeason(newEndSeason);
            player.setWage(newWage);
            // Renewing contract settles the player
            if (player.isWantsTransfer()) {
                player.setWantsTransfer(false);
                player.setMorale(Math.min(120, player.getMorale() + 10));
            }
            humanRepository.save(player);

            // Update team salary budget with the wage difference
            Team team = teamRepository.findById(player.getTeamId()).orElse(null);
            if (team != null) {
                team.setSalaryBudget(team.getSalaryBudget() + (newWage - oldWage));
                teamRepository.save(team);
            }

            String msg = player.getName() + " has accepted the new contract until Season " + newEndSeason + ".";
            if (negotiated) msg += " (After some negotiation, the player agreed to a slightly lower wage.)";

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

            // Rejected contract can lower morale
            player.setMorale(Math.max(30, player.getMorale() - 3));
            humanRepository.save(player);

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
            player.setMorale(Math.min(120, player.getMorale() + 15));
            player.setConsecutiveBenched(0); // Reset the counter
            humanRepository.save(player);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", player.getName() + " has accepted your promise of more playing time and is willing to stay."
            ));
        } else {
            player.setMorale(Math.max(30, player.getMorale() - 5));
            humanRepository.save(player);

            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", player.getName() + " doesn't believe your promises. They still want to leave."
            ));
        }
    }
}
