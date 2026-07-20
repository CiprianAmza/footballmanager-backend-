package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.Sponsorship;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.SponsorshipRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.user.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;



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
    @Autowired
    private FinanceService financeService;
    @Autowired
    private RoundRepository roundRepository;

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
        expireContractsBeforeSeason(season);
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return new ArrayList<>();
        }

        // Find which sponsor types already have active contracts
        List<Sponsorship> activeSponsors = sponsorshipRepository.findAllByTeamIdAndStatus(teamId, "ACTIVE");
        Set<String> occupiedTypes = new HashSet<>();
        for (Sponsorship active : activeSponsors) {
            occupiedTypes.add(active.getType());
        }

        // Filter available types (exclude types with active contracts)
        List<String> availableTypes = new ArrayList<>();
        for (String type : SPONSOR_TYPES) {
            if (!occupiedTypes.contains(type)) {
                availableTypes.add(type);
            }
        }

        // No available sponsor slots
        if (availableTypes.isEmpty()) {
            return new ArrayList<>();
        }

        int offerCount = Math.min(1 + random.nextInt(2), availableTypes.size()); // 1 or 2 offers, max available
        List<Sponsorship> offers = new ArrayList<>();
        List<String> usedTypes = new ArrayList<>();

        for (int i = 0; i < offerCount; i++) {
            String sponsorName = SPONSOR_NAMES[random.nextInt(SPONSOR_NAMES.length)];
            // Pick from available types, avoiding duplicates within the same batch
            List<String> remainingTypes = new ArrayList<>(availableTypes);
            remainingTypes.removeAll(usedTypes);
            if (remainingTypes.isEmpty()) break;
            String sponsorType = remainingTypes.get(random.nextInt(remainingTypes.size()));
            usedTypes.add(sponsorType);
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

        int currentSeason = currentSeason();
        if (!"OFFERED".equals(sponsorship.getStatus())
                || sponsorship.getEndSeason() < currentSeason) {
            if (sponsorship.getEndSeason() < currentSeason
                    && !"EXPIRED".equals(sponsorship.getStatus())) {
                sponsorship.setStatus("EXPIRED");
                sponsorshipRepository.save(sponsorship);
            }
            return null;
        }

        sponsorship.setStatus("ACTIVE");
        sponsorship = sponsorshipRepository.save(sponsorship);

        // Record sponsorship income via finance service
        financeService.recordTransaction(sponsorship.getTeamId(), sponsorship.getStartSeason(), 0,
                "SPONSORSHIP", sponsorship.getSponsorName() + " (" + sponsorship.getType() + ") sponsorship deal",
                sponsorship.getAnnualValue());

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
        expireContractsBeforeSeason(season);
        List<Sponsorship> activeSponsors = sponsorshipRepository.findAllByTeamIdAndStatus(teamId, "ACTIVE");
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return;
        }

        for (Sponsorship sponsorship : activeSponsors) {
            if (season >= sponsorship.getStartSeason() && season <= sponsorship.getEndSeason()) {
                financeService.recordTransaction(teamId, season, 0,
                        "SPONSORSHIP", sponsorship.getSponsorName() + " (" + sponsorship.getType() + ") annual revenue",
                        sponsorship.getAnnualValue());
            }
        }
    }

    /**
     * Close every active contract and unanswered offer whose final season is
     * older than the season being entered. This is the authoritative season
     * boundary operation; it is also safe to call repeatedly.
     */
    public int expireContractsBeforeSeason(int season) {
        List<Sponsorship> expired = sponsorshipRepository
                .findAllByStatusInAndEndSeasonLessThan(List.of("ACTIVE", "OFFERED"), season);
        if (expired.isEmpty()) return 0;
        expired.forEach(sponsorship -> sponsorship.setStatus("EXPIRED"));
        sponsorshipRepository.saveAll(expired);
        return expired.size();
    }

    // ==================== QUERIES ====================

    /**
     * Return all ACTIVE sponsorships for a team.
     *
     * @param teamId the team ID
     * @return list of active sponsorships
     */
    public List<Sponsorship> getActiveSponsors(long teamId) {
        expireContractsBeforeSeason(currentSeason());
        return sponsorshipRepository.findAllByTeamIdAndStatus(teamId, "ACTIVE");
    }

    /**
     * Return all OFFERED sponsorships for a team.
     *
     * @param teamId the team ID
     * @return list of offered sponsorships
     */
    public List<Sponsorship> getOfferedSponsors(long teamId) {
        expireContractsBeforeSeason(currentSeason());
        return sponsorshipRepository.findAllByTeamIdAndStatus(teamId, "OFFERED");
    }

    private int currentSeason() {
        return roundRepository.findById(1L).map(Round::getSeason).map(Math::toIntExact).orElse(1);
    }
}
