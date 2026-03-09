package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.user.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/loans")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class LoanController {

    @Autowired
    UserContext userContext;

    @Autowired
    HumanRepository humanRepository;

    @Autowired
    TeamRepository teamRepository;

    @Autowired
    LoanRepository loanRepository;

    @Autowired
    RoundRepository roundRepository;

    @Autowired
    ManagerInboxRepository managerInboxRepository;

    @Autowired
    CompetitionController competitionController;

    /**
     * Get active loans where team is either parent or loan team
     */
    @GetMapping("/active/{teamId}")
    public Map<String, List<Loan>> getActiveLoans(@PathVariable long teamId) {
        List<Loan> loansIn = loanRepository.findAllByLoanTeamIdAndStatus(teamId, "active");
        List<Loan> loansOut = loanRepository.findAllByParentTeamIdAndStatus(teamId, "active");

        Map<String, List<Loan>> result = new HashMap<>();
        result.put("loansIn", loansIn);
        result.put("loansOut", loansOut);
        return result;
    }

    /**
     * Get loan history for a team in a given season
     */
    @GetMapping("/history/{teamId}/{season}")
    public Map<String, List<Loan>> getLoanHistory(@PathVariable long teamId, @PathVariable int season) {
        List<Loan> loansIn = loanRepository.findAllByLoanTeamIdAndSeasonNumber(teamId, season);
        List<Loan> loansOut = loanRepository.findAllByParentTeamIdAndSeasonNumber(teamId, season);

        Map<String, List<Loan>> result = new HashMap<>();
        result.put("loansIn", loansIn);
        result.put("loansOut", loansOut);
        return result;
    }

    /**
     * Human makes a loan offer for a player
     */
    @PostMapping("/offer")
    public ResponseEntity<?> makeLoanOffer(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        long playerId = ((Number) body.get("playerId")).longValue();
        long loanFee = ((Number) body.get("loanFee")).longValue();

        // Check transfer window is open
        if (!competitionController.isTransferWindowOpen()) {
            return ResponseEntity.badRequest().body("Loan offers can only be made during the transfer window.");
        }

        // Get the player
        Human player = humanRepository.findById(playerId).orElse(null);
        if (player == null) {
            return ResponseEntity.badRequest().body("Player not found.");
        }

        // Cannot loan your own player
        if (player.getTeamId() == userContext.getTeamId(request)) {
            return ResponseEntity.badRequest().body("You cannot loan your own player.");
        }

        // Check player is not already on loan
        List<Loan> existingLoans = loanRepository.findAllByPlayerIdAndStatus(playerId, "active");
        if (!existingLoans.isEmpty()) {
            return ResponseEntity.badRequest().body("Player is already on loan.");
        }

        // Get teams
        Team parentTeam = teamRepository.findById(player.getTeamId()).orElse(null);
        Team humanTeam = teamRepository.findById(userContext.getTeamId(request)).orElse(null);
        if (parentTeam == null || humanTeam == null) {
            return ResponseEntity.badRequest().body("Team not found.");
        }

        // AI decision: accept if loanFee >= 5% of transfer value AND player NOT in top 15 by rating
        long minFee = Math.max(1, (long) (player.getTransferValue() * 0.05));
        long maxFee = (long) (player.getTransferValue() * 0.10);
        boolean feeAcceptable = loanFee >= minFee && loanFee <= maxFee;

        // Check if player is NOT in the team's top 15 by rating
        List<Human> teamPlayers = humanRepository.findAllByTeamIdAndTypeId(player.getTeamId(), 1L);
        List<Human> top15 = teamPlayers.stream()
                .sorted((a, b) -> Double.compare(b.getRating(), a.getRating()))
                .limit(15)
                .toList();
        boolean notInTop15 = top15.stream().noneMatch(p -> p.getId() == player.getId());

        Round round = roundRepository.findById(1L).orElse(new Round());

        if (feeAcceptable && notInTop15) {
            // Accept the loan
            player.setTeamId(userContext.getTeamId(request));
            humanRepository.save(player);

            // Update finances
            humanTeam.setTransferBudget(humanTeam.getTransferBudget() - loanFee);
            parentTeam.setTransferBudget(parentTeam.getTransferBudget() + loanFee);
            teamRepository.save(humanTeam);
            teamRepository.save(parentTeam);

            Loan loan = new Loan();
            loan.setPlayerId(playerId);
            loan.setPlayerName(player.getName());
            loan.setParentTeamId(parentTeam.getId());
            loan.setParentTeamName(parentTeam.getName());
            loan.setLoanTeamId(userContext.getTeamId(request));
            loan.setLoanTeamName(humanTeam.getName());
            loan.setSeasonNumber((int) round.getSeason());
            loan.setStatus("active");
            loan.setLoanFee(loanFee);
            loanRepository.save(loan);

            // Send inbox message
            ManagerInbox inbox = new ManagerInbox();
            inbox.setTeamId(userContext.getTeamId(request));
            inbox.setSeasonNumber((int) round.getSeason());
            inbox.setRoundNumber((int) round.getRound());
            inbox.setTitle("Loan Deal Completed");
            inbox.setContent(player.getName() + " has joined on loan from " + parentTeam.getName() +
                    " for a fee of " + loanFee + ".");
            inbox.setCategory("transfer");
            inbox.setRead(false);
            inbox.setCreatedAt(System.currentTimeMillis());
            managerInboxRepository.save(inbox);

            return ResponseEntity.ok(loan);
        } else {
            // Reject
            String reason;
            if (!notInTop15) {
                reason = "The club considers this player too important to loan out (top 15 in squad).";
            } else if (loanFee < minFee) {
                reason = "Loan fee too low. Minimum required: " + minFee + " (5% of value).";
            } else {
                reason = "Loan fee too high. Maximum acceptable: " + maxFee + " (10% of value).";
            }
            return ResponseEntity.badRequest().body("Loan offer rejected. " + reason);
        }
    }

    /**
     * Recall a player from loan early (not implemented)
     */
    @PostMapping("/recall/{loanId}")
    public ResponseEntity<?> recallPlayer(@PathVariable long loanId) {
        return ResponseEntity.badRequest().body("Cannot recall during season");
    }

}
