package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.Sponsorship;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.SponsorshipRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Service responsible for managing sponsorship deals.
 * Handles generating sponsor offers, accepting/rejecting them,
 * and processing seasonal sponsor revenue.
 */
@Service
public class SponsorshipService {

    @Autowired
    private UserContext userContext;

    private static final String[] SPONSOR_NAMES = {
            "TechCorp", "SportsDrink Plus", "GlobalBank", "AirTravel Co",
            "FastFood United", "ElectroGaming", "FitWear Sports"
    };

    private static final String[] SPONSOR_TYPES = {"KIT", "STADIUM", "TRAINING", "GENERAL"};

    @Autowired
    private SponsorshipRepository sponsorshipRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private ManagerInboxRepository managerInboxRepository;

    private final Random random = new Random();

    // ==================== OFFER GENERATION ====================

    /**
     * Generate 1-2 random sponsor offers for a team.
     * Called on SPONSOR_OFFER calendar events.
     *
     * Amount is based on team reputation * random multiplier (10-50) per season.
     * Duration is 1-3 seasons.
     *
     * @param teamId the team ID
     * @param season the current season number
     * @return list of generated sponsorship offers
     */
    public List<Sponsorship> generateSponsorOffer(long teamId, int season) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return new ArrayList<>();
        }

        int offerCount = 1 + random.nextInt(2); // 1 or 2 offers
        List<Sponsorship> offers = new ArrayList<>();

        for (int i = 0; i < offerCount; i++) {
            String sponsorName = SPONSOR_NAMES[random.nextInt(SPONSOR_NAMES.length)];
            String sponsorType = SPONSOR_TYPES[random.nextInt(SPONSOR_TYPES.length)];
            int multiplier = 10 + random.nextInt(41); // 10 to 50
            long annualValue = (long) team.getReputation() * multiplier;
            int duration = 1 + random.nextInt(3); // 1 to 3 seasons

            Sponsorship sponsorship = new Sponsorship();
            sponsorship.setTeamId(teamId);
            sponsorship.setSponsorName(sponsorName);
            sponsorship.setType(sponsorType);
            sponsorship.setAnnualValue(annualValue);
            sponsorship.setStartSeason(season);
            sponsorship.setEndSeason(season + duration - 1);
            sponsorship.setReputationRequirement(0);
            sponsorship.setStatus("OFFERED");

            sponsorship = sponsorshipRepository.save(sponsorship);
            offers.add(sponsorship);
        }

        // Send inbox message to the human manager
        if (userContext.isHumanTeam(teamId)) {
            StringBuilder content = new StringBuilder("New sponsorship offer(s) received:\n\n");
            for (Sponsorship offer : offers) {
                content.append("- ").append(offer.getSponsorName())
                        .append(" (").append(offer.getType()).append(")")
                        .append(": $").append(offer.getAnnualValue()).append("/season")
                        .append(" for ").append(offer.getEndSeason() - offer.getStartSeason() + 1)
                        .append(" season(s)\n");
            }
            content.append("\nReview and accept or reject these offers.");

            ManagerInbox inbox = new ManagerInbox();
            inbox.setTeamId(teamId);
            inbox.setSeasonNumber(season);
            inbox.setRoundNumber(0);
            inbox.setTitle("Sponsorship Offers Available");
            inbox.setContent(content.toString());
            inbox.setCategory("sponsorship");
            inbox.setRead(false);
            inbox.setCreatedAt(System.currentTimeMillis());
            managerInboxRepository.save(inbox);
        }

        return offers;
    }

    // ==================== ACCEPT / REJECT ====================

    /**
     * Accept a sponsorship offer. Sets status to ACTIVE and adds the annual value
     * to the team's total finances as an immediate budget boost.
     *
     * @param sponsorshipId the sponsorship ID to accept
     * @return the updated sponsorship, or null if not found
     */
    public Sponsorship acceptSponsorship(long sponsorshipId) {
        Sponsorship sponsorship = sponsorshipRepository.findById(sponsorshipId).orElse(null);
        if (sponsorship == null) {
            return null;
        }

        sponsorship.setStatus("ACTIVE");
        sponsorship = sponsorshipRepository.save(sponsorship);

        // Add money to team budget
        Team team = teamRepository.findById(sponsorship.getTeamId()).orElse(null);
        if (team != null) {
            team.setTotalFinances(team.getTotalFinances() + sponsorship.getAnnualValue());
            teamRepository.save(team);
        }

        return sponsorship;
    }

    /**
     * Reject a sponsorship offer. Sets status to REJECTED.
     *
     * @param sponsorshipId the sponsorship ID to reject
     * @return the updated sponsorship, or null if not found
     */
    public Sponsorship rejectSponsorship(long sponsorshipId) {
        Sponsorship sponsorship = sponsorshipRepository.findById(sponsorshipId).orElse(null);
        if (sponsorship == null) {
            return null;
        }

        sponsorship.setStatus("REJECTED");
        return sponsorshipRepository.save(sponsorship);
    }

    // ==================== SEASON REVENUE ====================

    /**
     * At end of season, add revenue from all ACTIVE sponsorships to team budget.
     * Also expires sponsorships whose end season has passed.
     *
     * @param teamId the team ID
     * @param season the current season number
     */
    public void processSeasonSponsorRevenue(long teamId, int season) {
        List<Sponsorship> activeSponsors = sponsorshipRepository.findAllByTeamIdAndStatus(teamId, "ACTIVE");
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return;
        }

        long totalRevenue = 0;
        for (Sponsorship sponsorship : activeSponsors) {
            if (season > sponsorship.getEndSeason()) {
                sponsorship.setStatus("EXPIRED");
                sponsorshipRepository.save(sponsorship);
            } else {
                totalRevenue += sponsorship.getAnnualValue();
            }
        }

        if (totalRevenue > 0) {
            team.setTotalFinances(team.getTotalFinances() + totalRevenue);
            teamRepository.save(team);
        }
    }

    // ==================== QUERIES ====================

    /**
     * Return all ACTIVE sponsorships for a team.
     *
     * @param teamId the team ID
     * @return list of active sponsorships
     */
    public List<Sponsorship> getActiveSponsors(long teamId) {
        return sponsorshipRepository.findAllByTeamIdAndStatus(teamId, "ACTIVE");
    }

    /**
     * Return all OFFERED sponsorships for a team.
     *
     * @param teamId the team ID
     * @return list of offered sponsorships
     */
    public List<Sponsorship> getOfferedSponsors(long teamId) {
        return sponsorshipRepository.findAllByTeamIdAndStatus(teamId, "OFFERED");
    }
}
