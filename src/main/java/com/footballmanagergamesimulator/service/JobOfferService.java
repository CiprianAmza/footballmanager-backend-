package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Generates and resolves job offers for human-managed users:
 *  - Offers come from teams the user does NOT currently coach.
 *  - The system favours teams with reputation slightly above the user's current
 *    team — the typical "natural step up" scenario.
 *  - Each generated offer also writes an inbox message so it appears in the
 *    user's normal feed; the JOB_OFFER category lets the frontend render
 *    accept / decline buttons inline.
 */
@Service
public class JobOfferService {

    @Autowired private JobOfferRepository jobOfferRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private ManagerInboxRepository inboxRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private HumanService humanService;

    private static final int OFFER_VALIDITY_DAYS = 7;

    /**
     * Generate a job offer for the user from a specific team. Returns the created
     * offer or null if invariants fail (user not found, team already managed,
     * already coaches that team).
     */
    @Transactional
    public JobOffer generateOffer(int userId, long offeringTeamId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return null;
        Long currentTeamId = user.getTeamId();
        if (currentTeamId != null && currentTeamId == offeringTeamId) return null;

        Team team = teamRepository.findById(offeringTeamId).orElse(null);
        if (team == null) return null;

        // Refuse if there's already a pending offer from THIS team
        boolean existsPending = jobOfferRepository.findAllByUserIdAndStatus(userId, "PENDING").stream()
                .anyMatch(o -> o.getTeamId() == offeringTeamId);
        if (existsPending) return null;

        int currentSeason = currentSeason();
        int currentDay = 1; // we don't depend on a calendar here; expiresOnDay is informational
        long wage = computeOfferedWage(team);

        JobOffer offer = new JobOffer();
        offer.setUserId(userId);
        offer.setTeamId(team.getId());
        offer.setTeamName(team.getName());
        offer.setTeamReputation(team.getReputation());
        offer.setOfferedWage(wage);
        offer.setSigningBonus(wage * 3);
        offer.setContractLengthSeasons(3);
        offer.setPitch(buildPitch(team));
        offer.setSeasonOffered(currentSeason);
        offer.setDayOffered(currentDay);
        offer.setExpiresOnDay(currentDay + OFFER_VALIDITY_DAYS);
        offer.setStatus("PENDING");
        offer.setCurrentTeamId(currentTeamId != null ? currentTeamId : 0L);
        jobOfferRepository.save(offer);

        // Drop an inbox entry so the offer appears in the normal message feed.
        // Content holds the offer id so the frontend can wire accept/decline buttons.
        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(currentTeamId != null ? currentTeamId : 0L);
        inbox.setSeasonNumber(currentSeason);
        inbox.setRoundNumber(0);
        inbox.setTitle("Job offer from " + team.getName());
        inbox.setCategory("JOB_OFFER");
        inbox.setContent("OFFER_ID:" + offer.getId()
                + " | " + team.getName() + " want you as their new manager. "
                + offer.getPitch()
                + " Offered wage: " + wage + "€/month. Contract: " + offer.getContractLengthSeasons() + " seasons.");
        inbox.setCreatedAt(System.currentTimeMillis());
        inbox.setRead(false);
        inboxRepository.save(inbox);

        return offer;
    }

    /**
     * Pick a sensible team and generate an offer. Used when the admin forces
     * an offer or when the periodic generator decides a user should get one.
     * Strategy: prefer teams reputed slightly higher than the user's current team
     * (a step up); otherwise any team they don't currently manage.
     */
    @Transactional
    public JobOffer generateOpportunisticOffer(int userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return null;
        Long currentTeamId = user.getTeamId();

        int currentRep = 0;
        if (currentTeamId != null) {
            Team cur = teamRepository.findById(currentTeamId).orElse(null);
            if (cur != null) currentRep = cur.getReputation();
        }

        // Candidates: teams that aren't currently managed by ANY human user
        Set<Long> humanManagedTeamIds = new HashSet<>();
        for (User u : userRepository.findAll()) {
            if (u.getTeamId() != null) humanManagedTeamIds.add(u.getTeamId());
        }

        List<Team> candidates = new ArrayList<>();
        for (Team t : teamRepository.findAll()) {
            if (humanManagedTeamIds.contains(t.getId())) continue;
            candidates.add(t);
        }
        if (candidates.isEmpty()) return null;

        // Score candidates: prefer those reputed slightly above current (a "step up")
        // but accept any team if user has none.
        int finalCurrentRep = currentRep;
        candidates.sort(Comparator.comparingInt(t -> {
            int delta = t.getReputation() - finalCurrentRep;
            // Best score for delta in [200, 1500]; punish extremes.
            if (delta < 0) return -delta + 2000;        // worse team = lower priority
            if (delta > 2000) return delta;             // way out of reach = lower priority
            return Math.abs(800 - delta);               // sweet spot around +800 rep
        }));

        Team chosen = candidates.get(0);
        return generateOffer(userId, chosen.getId());
    }

    /**
     * Accept the offer: move the user's manager Human to the new team, demote
     * the old AI manager from the new team to free-agent status, and mark the
     * old team needing a fresh AI manager (existing replacement logic handles
     * that on the next end-of-day cycle).
     */
    @Transactional
    public Map<String, Object> acceptOffer(long offerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        JobOffer offer = jobOfferRepository.findById(offerId).orElse(null);
        if (offer == null || !"PENDING".equals(offer.getStatus())) {
            result.put("success", false);
            result.put("message", "Offer not available.");
            return result;
        }

        User user = userRepository.findById(offer.getUserId()).orElse(null);
        if (user == null) {
            result.put("success", false);
            result.put("message", "User not found.");
            return result;
        }

        long oldTeamId = offer.getCurrentTeamId();
        long newTeamId = offer.getTeamId();

        // 1. Demote the AI manager currently at the offering team (free agent / retired)
        List<Human> aiManagers = humanRepository.findAllByTeamIdAndTypeId(newTeamId, TypeNames.MANAGER_TYPE);
        for (Human ai : aiManagers) {
            // Only displace non-user managers — defensive guard
            if (user.getManagerId() != null && ai.getId() == user.getManagerId()) continue;
            ai.setTeamId(0L);
            humanRepository.save(ai);
        }

        // 2. Move the user's manager Human entity to the new team
        if (user.getManagerId() != null) {
            humanRepository.findById(user.getManagerId()).ifPresent(mgr -> {
                mgr.setTeamId(newTeamId);
                humanRepository.save(mgr);
            });
        }

        // 3. Update the user's owned team. The old team becomes managerless,
        //    so we spawn a fresh AI manager for it immediately. Without this
        //    the match simulator can hit findAllByTeamIdAndTypeId(...).get(0)
        //    on an empty list and crash the whole round — the previous comment
        //    claimed an "end-of-day AI replacement loop" handled it, but that
        //    loop never existed.
        user.setTeamId(newTeamId);
        user.setLastTeamId(oldTeamId);
        user.setFired(false);
        userRepository.save(user);
        if (oldTeamId > 0) humanService.ensureTeamHasManager(oldTeamId);

        // 4. Auto-decline every other pending offer for this user (we accepted one)
        for (JobOffer other : jobOfferRepository.findAllByUserIdAndStatus(offer.getUserId(), "PENDING")) {
            if (other.getId() != offerId) {
                other.setStatus("DECLINED");
                jobOfferRepository.save(other);
            }
        }

        offer.setStatus("ACCEPTED");
        jobOfferRepository.save(offer);

        // 5. Inbox confirmation
        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(newTeamId);
        inbox.setSeasonNumber(offer.getSeasonOffered());
        inbox.setTitle("Welcome to " + offer.getTeamName());
        inbox.setCategory("CAREER");
        inbox.setContent("You accepted the offer from " + offer.getTeamName()
                + ". Your career continues at your new club.");
        inbox.setCreatedAt(System.currentTimeMillis());
        inbox.setRead(false);
        inboxRepository.save(inbox);

        result.put("success", true);
        result.put("newTeamId", newTeamId);
        result.put("newTeamName", offer.getTeamName());
        return result;
    }

    @Transactional
    public Map<String, Object> declineOffer(long offerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        JobOffer offer = jobOfferRepository.findById(offerId).orElse(null);
        if (offer == null || !"PENDING".equals(offer.getStatus())) {
            result.put("success", false);
            result.put("message", "Offer not available.");
            return result;
        }
        offer.setStatus("DECLINED");
        jobOfferRepository.save(offer);
        result.put("success", true);
        return result;
    }

    /** Convenience: true if the user has at least one PENDING offer (pause condition). */
    public boolean userHasPendingOffer(int userId) {
        return !jobOfferRepository.findAllByUserIdAndStatus(userId, "PENDING").isEmpty();
    }

    public List<JobOffer> pendingOffersFor(int userId) {
        return jobOfferRepository.findAllByUserIdAndStatus(userId, "PENDING");
    }

    // ============================================================
    //                       helpers
    // ============================================================

    private long computeOfferedWage(Team team) {
        // Higher reputation = better contract. Scaled so a 2000-rep team offers ~€10k/month.
        return Math.max(2000L, team.getReputation() * 5L);
    }

    private String buildPitch(Team team) {
        int rep = team.getReputation();
        if (rep >= 8000) return "We are one of the elite sides in football and want a top coach for the next era.";
        if (rep >= 5000) return "We're an ambitious club ready to break into the top tier with the right manager.";
        if (rep >= 3000) return "A mid-table side looking for a coach who can guide us forward.";
        return "We are rebuilding from the bottom and need someone with vision and patience.";
    }

    private int currentSeason() {
        return roundRepository.findById(1L).map(r -> (int) r.getSeason()).orElse(1);
    }
}
