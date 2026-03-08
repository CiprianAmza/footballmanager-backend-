package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/scouting")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class ScoutingController {

    @Autowired
    private HumanRepository humanRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamFacilitiesRepository teamFacilitiesRepository;

    private final Random random = new Random();

    @GetMapping("/report/{playerId}/{teamId}")
    public ResponseEntity<?> getScoutReport(
            @PathVariable(name = "playerId") long playerId,
            @PathVariable(name = "teamId") long teamId) {

        Optional<Human> playerOpt = humanRepository.findById(playerId);
        if (playerOpt.isEmpty()) {
            return ResponseEntity.badRequest().body("Player not found");
        }

        Human player = playerOpt.get();
        Team playerTeam = (player.getTeamId() != null) ? teamRepository.findById(player.getTeamId()).orElse(null) : null;

        // Get scouting level
        TeamFacilities facilities = teamFacilitiesRepository.findByTeamId(teamId);
        int scoutingLevel = (facilities != null) ? facilities.getScoutingLevel() : 1;
        if (scoutingLevel < 1) scoutingLevel = 1;
        if (scoutingLevel > 20) scoutingLevel = 20;
        int errorMargin = (20 - scoutingLevel) * 3;
        int scoutingAccuracy = scoutingLevel * 5;

        // Fresh estimate each call
        double actualRating = player.getRating();
        double noise = (errorMargin > 0) ? (random.nextDouble() * 2 * errorMargin - errorMargin) : 0;
        double estimatedRating = Math.max(1, Math.round(actualRating + noise));

        // Estimated transfer value based on estimated rating
        long estimatedTransferValue = calculateTransferValue(player.getAge(), player.getPosition(), estimatedRating);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("playerId", player.getId());
        report.put("playerName", player.getName());
        report.put("position", player.getPosition());
        report.put("age", player.getAge());
        report.put("teamName", playerTeam != null ? playerTeam.getName() : "Free Agent");
        report.put("estimatedRating", estimatedRating);
        report.put("scoutingAccuracy", scoutingAccuracy);
        report.put("estimatedTransferValue", estimatedTransferValue);

        return ResponseEntity.ok(report);
    }

    private long calculateTransferValue(long age, String position, double rating) {
        double baseValue = rating * 10000;

        double ageMultiplier;
        if (age <= 22) ageMultiplier = 0.7;
        else if (age <= 24) ageMultiplier = 0.9;
        else if (age <= 27) ageMultiplier = 1.0;
        else if (age <= 29) ageMultiplier = 0.85;
        else if (age <= 31) ageMultiplier = 0.6;
        else if (age <= 33) ageMultiplier = 0.35;
        else ageMultiplier = 0.15;

        return (long) (baseValue * ageMultiplier);
    }
}
