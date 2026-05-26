package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@RestController
@RequestMapping("/shortlist")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class ShortlistController {

    @Autowired
    ShortlistRepository shortlistRepository;

    @Autowired
    UserContext userContext;

    @Autowired
    HumanRepository humanRepository;

    @Autowired
    TeamRepository teamRepository;

    @Autowired
    RoundRepository roundRepository;

    @GetMapping("/all")
    public List<Map<String, Object>> getShortlist(HttpServletRequest request) {
        User user = userContext.getUserOrNull(request);
        if (user == null) return Collections.emptyList();

        List<Shortlist> entries = shortlistRepository.findAllByUserId(user.getId());
        List<Map<String, Object>> result = new ArrayList<>();

        for (Shortlist entry : entries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entry.getId());
            item.put("playerId", entry.getPlayerId());
            item.put("notes", entry.getNotes());
            item.put("addedAtSeason", entry.getAddedAtSeason());

            // Fetch live player data
            Human player = humanRepository.findById(entry.getPlayerId()).orElse(null);
            if (player != null) {
                item.put("playerName", player.getName());
                item.put("position", player.getPosition());
                item.put("age", player.getAge());
                item.put("rating", player.getRating());
                item.put("transferValue", player.getTransferValue());
                item.put("wage", player.getWage());
                item.put("fitness", player.getFitness());
                item.put("morale", player.getMorale());
                item.put("contractEndSeason", player.getContractEndSeason());

                Team team = player.getTeamId() != null ? teamRepository.findById(player.getTeamId()).orElse(null) : null;
                item.put("teamName", team != null ? team.getName() : "Free Agent");
            } else {
                item.put("playerName", entry.getPlayerName());
                item.put("position", entry.getPosition());
                item.put("teamName", entry.getTeamName());
                item.put("retired", true);
            }
            result.add(item);
        }
        return result;
    }

    @PostMapping("/add/{playerId}")
    public ResponseEntity<?> addToShortlist(@PathVariable long playerId, HttpServletRequest request) {
        User user = userContext.getUserOrNull(request);
        if (user == null) return ResponseEntity.status(401).body("Not logged in");

        if (shortlistRepository.existsByUserIdAndPlayerId(user.getId(), playerId)) {
            return ResponseEntity.badRequest().body("Player already in shortlist");
        }

        Human player = humanRepository.findById(playerId).orElse(null);
        if (player == null) return ResponseEntity.badRequest().body("Player not found");

        Round round = roundRepository.findById(1L).orElse(new Round());
        Team team = player.getTeamId() != null ? teamRepository.findById(player.getTeamId()).orElse(null) : null;

        Shortlist entry = new Shortlist();
        entry.setUserId(user.getId());
        entry.setPlayerId(playerId);
        entry.setPlayerName(player.getName());
        entry.setPosition(player.getPosition());
        entry.setTeamName(team != null ? team.getName() : "Free Agent");
        entry.setAddedAtDay(round.getRound());
        entry.setAddedAtSeason((int) round.getSeason());
        entry.setNotes("");
        shortlistRepository.save(entry);

        return ResponseEntity.ok(Map.of("success", true, "message", "Player added to shortlist"));
    }

    @DeleteMapping("/remove/{playerId}")
    @Transactional
    public ResponseEntity<?> removeFromShortlist(@PathVariable long playerId, HttpServletRequest request) {
        User user = userContext.getUserOrNull(request);
        if (user == null) return ResponseEntity.status(401).body("Not logged in");

        shortlistRepository.deleteByUserIdAndPlayerId(user.getId(), playerId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Player removed from shortlist"));
    }

    @GetMapping("/check/{playerId}")
    public Map<String, Boolean> isInShortlist(@PathVariable long playerId, HttpServletRequest request) {
        User user = userContext.getUserOrNull(request);
        if (user == null) return Map.of("inShortlist", false);
        return Map.of("inShortlist", shortlistRepository.existsByUserIdAndPlayerId(user.getId(), playerId));
    }

    @PostMapping("/updateNotes/{shortlistId}")
    public ResponseEntity<?> updateNotes(@PathVariable long shortlistId, @RequestBody Map<String, String> body, HttpServletRequest request) {
        User user = userContext.getUserOrNull(request);
        if (user == null) return ResponseEntity.status(401).body("Not logged in");

        Shortlist entry = shortlistRepository.findById(shortlistId).orElse(null);
        if (entry == null || entry.getUserId() != user.getId()) {
            return ResponseEntity.badRequest().body("Entry not found");
        }

        entry.setNotes(body.getOrDefault("notes", ""));
        shortlistRepository.save(entry);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
