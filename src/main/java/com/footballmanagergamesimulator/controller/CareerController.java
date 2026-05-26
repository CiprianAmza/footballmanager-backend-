package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.JobOffer;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.JobOfferRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.service.JobOfferService;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Endpoints powering the manager career flow (resign + accept/decline job offers).
 * Admin-side triggering lives in AdminController; this is the user-facing surface.
 */
@RestController
@RequestMapping("/career")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class CareerController {

    @Autowired private JobOfferService jobOfferService;
    @Autowired private JobOfferRepository jobOfferRepository;
    @Autowired private UserContext userContext;
    @Autowired private UserRepository userRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private ManagerInboxRepository inboxRepository;

    /** Identity payload for the logged-in user — managerId + teamId, plus pending-offer flag. */
    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        Integer userId = userIdFromHeader(request);
        if (userId == null) return Map.of();
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", user.getId());
        out.put("managerId", user.getManagerId());
        out.put("teamId", user.getTeamId());
        out.put("lastTeamId", user.getLastTeamId());
        out.put("fired", user.isFired());
        out.put("hasPendingOffer", jobOfferService.userHasPendingOffer(userId));
        return out;
    }

    /** All pending offers for the current user — drives the inbox modal and pause banner. */
    @GetMapping("/pendingOffers")
    public List<JobOffer> getPending(HttpServletRequest request) {
        Long userIdLong = (long) userIdFromHeader(request);
        if (userIdLong == null) return List.of();
        return jobOfferService.pendingOffersFor(userIdLong.intValue());
    }

    @PostMapping("/offers/{offerId}/accept")
    public Map<String, Object> accept(@PathVariable long offerId, HttpServletRequest request) {
        Integer userId = userIdFromHeader(request);
        if (userId == null) return Map.of("success", false, "message", "Not logged in");
        JobOffer offer = jobOfferRepository.findById(offerId).orElse(null);
        if (offer == null || offer.getUserId() != userId) {
            return Map.of("success", false, "message", "Offer not yours");
        }
        return jobOfferService.acceptOffer(offerId);
    }

    @PostMapping("/offers/{offerId}/decline")
    public Map<String, Object> decline(@PathVariable long offerId, HttpServletRequest request) {
        Integer userId = userIdFromHeader(request);
        if (userId == null) return Map.of("success", false, "message", "Not logged in");
        JobOffer offer = jobOfferRepository.findById(offerId).orElse(null);
        if (offer == null || offer.getUserId() != userId) {
            return Map.of("success", false, "message", "Offer not yours");
        }
        return jobOfferService.declineOffer(offerId);
    }

    /**
     * User-initiated resign: detaches their manager Human from the current team,
     * clears the user's teamId, and leaves them in "looking for a job" state.
     * The empty team is picked up by the existing AI-manager-replacement loop.
     */
    @PostMapping("/resign")
    public Map<String, Object> resign(HttpServletRequest request) {
        Integer userId = userIdFromHeader(request);
        if (userId == null) return Map.of("success", false, "message", "Not logged in");
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getTeamId() == null || user.getTeamId() <= 0) {
            return Map.of("success", false, "message", "You don't currently manage a team.");
        }
        long oldTeamId = user.getTeamId();

        // Detach the user's manager Human from the team so the end-of-day AI
        // replacement loop fills the vacancy with a new AI manager.
        if (user.getManagerId() != null) {
            humanRepository.findById(user.getManagerId()).ifPresent(mgr -> {
                mgr.setTeamId(0L);
                humanRepository.save(mgr);
            });
        }

        user.setLastTeamId(oldTeamId);
        user.setTeamId(null);
        userRepository.save(user);

        // Inbox confirmation on the old team's feed (so they can still see it).
        ManagerInbox msg = new ManagerInbox();
        msg.setTeamId(oldTeamId);
        msg.setSeasonNumber(0);
        msg.setTitle("Resignation submitted");
        msg.setCategory("CAREER");
        msg.setContent("You resigned from your post. You're now a free agent — look for a new club via the Job Search page.");
        msg.setCreatedAt(System.currentTimeMillis());
        msg.setRead(false);
        inboxRepository.save(msg);

        return Map.of("success", true, "leftTeamId", oldTeamId);
    }

    private Integer userIdFromHeader(HttpServletRequest request) {
        String header = request.getHeader("X-User-Id");
        if (header == null || header.isBlank()) return null;
        try {
            return Integer.parseInt(header);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
