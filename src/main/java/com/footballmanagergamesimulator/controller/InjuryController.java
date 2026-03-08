package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Injury;
import com.footballmanagergamesimulator.model.Scorer;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.InjuryRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/injuries")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class InjuryController {

    @Autowired
    InjuryRepository injuryRepository;
    @Autowired
    HumanRepository humanRepository;
    @Autowired
    ScorerRepository scorerRepository;

    @GetMapping("/active/{teamId}")
    public List<Map<String, Object>> getActiveInjuries(@PathVariable long teamId) {

        List<Injury> activeInjuries = injuryRepository.findAllByTeamIdAndDaysRemainingGreaterThan(teamId, 0);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Injury injury : activeInjuries) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", injury.getId());
            entry.put("playerId", injury.getPlayerId());

            Optional<Human> player = humanRepository.findById(injury.getPlayerId());
            entry.put("playerName", player.map(Human::getName).orElse("Unknown"));
            entry.put("position", player.map(Human::getPosition).orElse("N/A"));
            entry.put("rating", player.map(Human::getRating).orElse(0.0));

            entry.put("injuryType", injury.getInjuryType());
            entry.put("severity", injury.getSeverity());
            entry.put("daysRemaining", injury.getDaysRemaining());
            entry.put("seasonNumber", injury.getSeasonNumber());

            result.add(entry);
        }

        return result;
    }

    @GetMapping("/history/{teamId}")
    public List<Map<String, Object>> getInjuryHistory(@PathVariable long teamId) {

        List<Injury> allInjuries = injuryRepository.findAllByTeamId(teamId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Injury injury : allInjuries) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", injury.getId());
            entry.put("playerId", injury.getPlayerId());

            Optional<Human> player = humanRepository.findById(injury.getPlayerId());
            entry.put("playerName", player.map(Human::getName).orElse("Unknown"));
            entry.put("position", player.map(Human::getPosition).orElse("N/A"));

            entry.put("injuryType", injury.getInjuryType());
            entry.put("severity", injury.getSeverity());
            entry.put("daysRemaining", injury.getDaysRemaining());
            entry.put("seasonNumber", injury.getSeasonNumber());
            entry.put("recovered", injury.getDaysRemaining() <= 0);

            result.add(entry);
        }

        return result;
    }

    @GetMapping("/player/{playerId}")
    public List<Injury> getPlayerInjuries(@PathVariable long playerId) {
        return injuryRepository.findAllByPlayerId(playerId);
    }

    @GetMapping("/riskAssessment/{teamId}")
    public List<Map<String, Object>> getRiskAssessment(@PathVariable long teamId) {

        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Human player : players) {
            if (player.isRetired()) continue;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", player.getId());
            entry.put("name", player.getName());
            entry.put("position", player.getPosition());
            entry.put("rating", player.getRating());

            // Calculate match load from Scorer data
            List<Scorer> recentMatches = scorerRepository.findAllByPlayerId(player.getId());
            int totalMatches = recentMatches.size();

            // Take last 5 matches as "recent"
            int recentCount = Math.min(totalMatches, 5);
            String matchLoad;
            String matchLoadInfo;

            if (recentCount >= 4) {
                matchLoad = "Heavy";
                matchLoadInfo = recentCount + " matches recently";
            } else if (recentCount >= 2) {
                matchLoad = "Medium";
                matchLoadInfo = recentCount + " matches recently";
            } else {
                matchLoad = "Light";
                matchLoadInfo = recentCount + " match recently";
            }

            entry.put("matchLoad", matchLoad);
            entry.put("matchLoadInfo", matchLoadInfo);

            // Fatigue based on fitness
            String fatigue;
            if (player.getFitness() >= 80) {
                fatigue = "Fresh";
            } else if (player.getFitness() >= 50) {
                fatigue = "Low";
            } else {
                fatigue = "High";
            }
            entry.put("fatigue", fatigue);

            // Injury susceptibility based on injury history
            List<Injury> playerInjuries = injuryRepository.findAllByPlayerId(player.getId());
            int injuryCount = playerInjuries.size();
            String injurySusceptibility;
            String injuryInfo;

            if (injuryCount >= 3) {
                injurySusceptibility = "Very High";
                injuryInfo = "History of various injuries";
            } else if (injuryCount == 2) {
                injurySusceptibility = "High";
                injuryInfo = "Prone to injuries";
            } else if (injuryCount == 1) {
                injurySusceptibility = "Above Average";
                injuryInfo = "Previous injury recorded";
            } else {
                injurySusceptibility = "Low";
                injuryInfo = "No recurring injuries";
            }

            entry.put("injurySusceptibility", injurySusceptibility);
            entry.put("injuryInfo", injuryInfo);

            // Check if currently injured
            Optional<Injury> activeInjury = injuryRepository.findByPlayerIdAndDaysRemainingGreaterThan(player.getId(), 0);
            entry.put("currentlyInjured", activeInjury.isPresent());
            if (activeInjury.isPresent()) {
                entry.put("currentInjuryType", activeInjury.get().getInjuryType());
                entry.put("currentDaysRemaining", activeInjury.get().getDaysRemaining());
            }

            // Overall risk
            String overallRisk;
            String riskLabel;

            if (matchLoad.equals("Heavy") || injurySusceptibility.equals("Very High") || fatigue.equals("High")) {
                overallRisk = "High";
                riskLabel = "High injury risk";
            } else if (matchLoad.equals("Medium") || injurySusceptibility.equals("High") || injurySusceptibility.equals("Above Average")) {
                overallRisk = "Increased";
                riskLabel = "Increased injury risk";
            } else {
                overallRisk = "Low";
                riskLabel = "Low injury risk";
            }

            entry.put("overallRisk", overallRisk);
            entry.put("riskLabel", riskLabel);

            result.add(entry);
        }

        return result;
    }

}
