package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/contract")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class ContractController {

    private static final long HUMAN_TEAM_ID = 1L;

    @Autowired
    private HumanRepository humanRepository;

    @Autowired
    private RoundRepository roundRepository;

    @PostMapping("/renew")
    public ResponseEntity<?> renewContract(@RequestBody Map<String, Object> body) {
        long playerId = ((Number) body.get("playerId")).longValue();
        int newEndSeason = ((Number) body.get("newEndSeason")).intValue();
        long newWage = ((Number) body.get("newWage")).longValue();

        Optional<Human> playerOpt = humanRepository.findById(playerId);
        if (playerOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Player not found"));
        }

        Human player = playerOpt.get();
        if (player.getTeamId() == null || player.getTeamId() != HUMAN_TEAM_ID) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Can only renew contracts for your own players"));
        }

        Round round = roundRepository.findById(1L).orElse(new Round());
        int currentSeason = (int) round.getSeason();

        // Player accepts if newWage >= currentWage * 0.9 AND newEndSeason >= currentSeason + 1
        boolean wageAcceptable = newWage >= (long) (player.getWage() * 0.9);
        boolean durationAcceptable = newEndSeason >= currentSeason + 1;

        if (wageAcceptable && durationAcceptable) {
            player.setContractEndSeason(newEndSeason);
            player.setWage(newWage);
            humanRepository.save(player);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", player.getName() + " has accepted the new contract until Season " + newEndSeason + "."
            ));
        } else {
            String reason = "";
            if (!wageAcceptable) {
                reason = "The wage offer is too low. Minimum acceptable: " + (long) (player.getWage() * 0.9) + ".";
            }
            if (!durationAcceptable) {
                reason += " Contract must extend to at least Season " + (currentSeason + 1) + ".";
            }
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", player.getName() + " has rejected the contract offer. " + reason.trim()
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
                    info.put("releaseClause", player.getReleaseClause());
                    return info;
                })
                .collect(Collectors.toList());
    }
}
