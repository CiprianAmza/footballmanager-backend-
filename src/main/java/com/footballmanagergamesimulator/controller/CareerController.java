package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.model.JobOffer;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.JobOfferRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.service.HumanService;
import com.footballmanagergamesimulator.service.JobOfferService;
import com.footballmanagergamesimulator.user.CurrentUserService;
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
    @Autowired private CurrentUserService currentUserService;
    @Autowired private UserRepository userRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private ManagerInboxRepository inboxRepository;
    @Autowired private HumanService humanService;

    /** Identity payload for the logged-in user — managerId + teamId, plus pending-offer flag. */
    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        User user = currentUserService.getUserOrNull(request);
        if (user == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", user.getId());
        out.put("managerId", user.getManagerId());
        out.put("teamId", user.getTeamId());
        out.put("lastTeamId", user.getLastTeamId());
        out.put("fired", user.isFired());
        out.put("hasPendingOffer", jobOfferService.userHasPendingOffer(user.getId()));
        return out;
    }

    /** All pending offers for the current user — drives the inbox modal and pause banner. */
    @GetMapping("/pendingOffers")
    public List<JobOffer> getPending(HttpServletRequest request) {
        User user = currentUserService.getUserOrNull(request);
        if (user == null) return List.of();
        return jobOfferService.pendingOffersFor(user.getId());
    }

    @PostMapping("/offers/{offerId}/accept")
    public Map<String, Object> accept(@PathVariable long offerId, HttpServletRequest request) {
        User user = currentUserService.getUserOrNull(request);
        if (user == null) return Map.of("success", false, "message", "Not logged in");
        JobOffer offer = jobOfferRepository.findById(offerId).orElse(null);
        if (offer == null || offer.getUserId() != user.getId()) {
            return Map.of("success", false, "message", "Offer not yours");
        }
        return jobOfferService.acceptOffer(offerId);
    }

    @PostMapping("/offers/{offerId}/decline")
    public Map<String, Object> decline(@PathVariable long offerId, HttpServletRequest request) {
        User user = currentUserService.getUserOrNull(request);
        if (user == null) return Map.of("success", false, "message", "Not logged in");
        JobOffer offer = jobOfferRepository.findById(offerId).orElse(null);
        if (offer == null || offer.getUserId() != user.getId()) {
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
        User user = currentUserService.getUserOrNull(request);
        if (user == null) return Map.of("success", false, "message", "Not logged in");
        if (user.getTeamId() == null || user.getTeamId() <= 0) {
            return Map.of("success", false, "message", "You don't currently manage a team.");
        }
        long oldTeamId = user.getTeamId();

        // Detach the user's manager Human from the team, then immediately spawn
        // a fresh AI manager for the team they left. Without that replacement
        // the next round can crash on findAllByTeamIdAndTypeId(...).get(0) for
        // the now-managerless team.
        if (user.getManagerId() != null) {
            humanRepository.findById(user.getManagerId()).ifPresent(mgr -> {
                mgr.setTeamId(0L);
                humanRepository.save(mgr);
            });
        }

        user.setLastTeamId(oldTeamId);
        user.setTeamId(null);
        userRepository.save(user);
        humanService.ensureTeamHasManager(oldTeamId);

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
}
