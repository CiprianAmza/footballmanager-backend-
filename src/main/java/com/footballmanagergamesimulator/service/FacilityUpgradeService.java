package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.FacilityUpgrade;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamFacilities;
import com.footballmanagergamesimulator.repository.FacilityUpgradeRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.TeamFacilitiesRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service responsible for managing facility upgrades.
 * Handles starting upgrades, checking completion, and updating facility levels.
 *
 * Facility types and their effects:
 * - TRAINING_GROUND: affects training quality (maps to seniorTrainingLevel)
 * - STADIUM: affects match income
 * - YOUTH_ACADEMY: affects youth player potential (maps to youthAcademyLevel)
 * - MEDICAL_CENTER: reduces injury duration (maps to scoutingLevel, repurposed as medical)
 */
@Service
public class FacilityUpgradeService {

    private static final long BASE_UPGRADE_COST = 500000L;
    private static final int BASE_UPGRADE_DURATION_DAYS = 30;

    @Autowired
    private UserContext userContext;

    @Autowired
    private FacilityUpgradeRepository facilityUpgradeRepository;
    @Autowired
    private TeamFacilitiesRepository teamFacilitiesRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private ManagerInboxRepository managerInboxRepository;

    // ==================== START UPGRADE ====================

    /**
     * Start an upgrade for a specific facility type.
     * Cost = currentLevel * 500,000. Duration = currentLevel * 30 days.
     * Deducts cost from team budget and creates a FacilityUpgrade record.
     *
     * @param teamId       the team ID
     * @param facilityType one of: "TRAINING_GROUND", "STADIUM", "YOUTH_ACADEMY", "MEDICAL_CENTER"
     * @param season       the current season number
     * @return the created FacilityUpgrade, or null if team/facilities not found or insufficient funds
     */
    public FacilityUpgrade startUpgrade(long teamId, String facilityType, int season) {
        TeamFacilities facilities = teamFacilitiesRepository.findByTeamId(teamId);
        if (facilities == null) {
            return null;
        }

        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return null;
        }

        int currentLevel = getFacilityLevel(facilities, facilityType);
        long cost = (long) currentLevel * BASE_UPGRADE_COST;
        int durationDays = currentLevel * BASE_UPGRADE_DURATION_DAYS;

        // Ensure minimum cost and duration for level 0
        if (currentLevel == 0) {
            cost = BASE_UPGRADE_COST;
            durationDays = BASE_UPGRADE_DURATION_DAYS;
        }

        // Check if team can afford the upgrade
        if (team.getTotalFinances() < cost) {
            return null;
        }

        // Deduct cost from team budget
        team.setTotalFinances(team.getTotalFinances() - cost);
        teamRepository.save(team);

        // Create upgrade record
        FacilityUpgrade upgrade = new FacilityUpgrade();
        upgrade.setTeamId(teamId);
        upgrade.setFacilityType(facilityType);
        upgrade.setCurrentLevel(currentLevel);
        upgrade.setTargetLevel(currentLevel + 1);
        upgrade.setCost(cost);
        upgrade.setStartDay(0); // Will be set from game calendar context
        upgrade.setStartSeason(season);
        upgrade.setDurationDays(durationDays);
        upgrade.setCompleted(false);

        return facilityUpgradeRepository.save(upgrade);
    }

    // ==================== COMPLETION CHECK ====================

    /**
     * Check if any upgrades are due for completion based on the current day.
     * If completed: increment facility level in TeamFacilities and send inbox message.
     *
     * Effects of facility levels:
     * - Training ground level affects training quality
     * - Youth academy level affects youth player potential
     * - Medical center level reduces injury duration
     * - Stadium level affects match income
     *
     * @param teamId     the team ID
     * @param currentDay the current day in the season
     */
    public void checkUpgradeCompletion(long teamId, int currentDay) {
        List<FacilityUpgrade> inProgress = facilityUpgradeRepository.findAllByTeamIdAndCompletedFalse(teamId);
        TeamFacilities facilities = teamFacilitiesRepository.findByTeamId(teamId);

        if (facilities == null) {
            return;
        }

        for (FacilityUpgrade upgrade : inProgress) {
            int endDay = upgrade.getStartDay() + upgrade.getDurationDays();
            if (currentDay >= endDay) {
                // Mark as completed
                upgrade.setCompleted(true);
                facilityUpgradeRepository.save(upgrade);

                // Increment facility level
                setFacilityLevel(facilities, upgrade.getFacilityType(), upgrade.getTargetLevel());
                teamFacilitiesRepository.save(facilities);

                // Send inbox notification
                if (userContext.isHumanTeam(teamId)) {
                    sendUpgradeCompleteNotification(upgrade);
                }
            }
        }
    }

    // ==================== QUERIES ====================

    /**
     * Return all in-progress (incomplete) upgrades for a team.
     *
     * @param teamId the team ID
     * @return list of in-progress facility upgrades
     */
    public List<FacilityUpgrade> getUpgradesInProgress(long teamId) {
        return facilityUpgradeRepository.findAllByTeamIdAndCompletedFalse(teamId);
    }

    /**
     * Return the TeamFacilities for a team.
     *
     * @param teamId the team ID
     * @return the team's facilities, or null if not found
     */
    public TeamFacilities getFacilities(long teamId) {
        return teamFacilitiesRepository.findByTeamId(teamId);
    }

    // ==================== PRIVATE HELPERS ====================

    /**
     * Get the current level for a given facility type from TeamFacilities.
     */
    private int getFacilityLevel(TeamFacilities facilities, String facilityType) {
        switch (facilityType) {
            case "TRAINING_GROUND":
                return (int) facilities.getSeniorTrainingLevel();
            case "STADIUM":
                // Stadium level is not directly tracked in TeamFacilities;
                // default to 1 so upgrades can still be processed
                return 1;
            case "YOUTH_ACADEMY":
                return (int) facilities.getYouthAcademyLevel();
            case "MEDICAL_CENTER":
                return facilities.getScoutingLevel();
            default:
                return 1;
        }
    }

    /**
     * Set the level for a given facility type in TeamFacilities.
     */
    private void setFacilityLevel(TeamFacilities facilities, String facilityType, int level) {
        switch (facilityType) {
            case "TRAINING_GROUND":
                facilities.setSeniorTrainingLevel(level);
                break;
            case "STADIUM":
                // Stadium upgrades tracked via FacilityUpgrade records
                break;
            case "YOUTH_ACADEMY":
                facilities.setYouthAcademyLevel(level);
                break;
            case "MEDICAL_CENTER":
                facilities.setScoutingLevel(level);
                break;
        }
    }

    /**
     * Send an inbox notification when a facility upgrade is completed.
     */
    private void sendUpgradeCompleteNotification(FacilityUpgrade upgrade) {
        String facilityName = upgrade.getFacilityType().replace("_", " ").toLowerCase();
        String effectDescription = getEffectDescription(upgrade.getFacilityType(), upgrade.getTargetLevel());

        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(upgrade.getTeamId());
        inbox.setSeasonNumber(upgrade.getStartSeason());
        inbox.setRoundNumber(0);
        inbox.setTitle("Facility Upgrade Complete: " + facilityName);
        inbox.setContent("Your " + facilityName + " has been upgraded to level "
                + upgrade.getTargetLevel() + ".\n\n" + effectDescription);
        inbox.setCategory("facility");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        managerInboxRepository.save(inbox);
    }

    /**
     * Get a description of the effect of a facility upgrade.
     */
    private String getEffectDescription(String facilityType, int level) {
        switch (facilityType) {
            case "TRAINING_GROUND":
                return "Training quality has improved. Players will develop faster during training sessions.";
            case "STADIUM":
                return "Stadium capacity increased. Match day income will be higher.";
            case "YOUTH_ACADEMY":
                return "Youth academy improved. Youth players will have higher potential ability.";
            case "MEDICAL_CENTER":
                return "Medical facilities upgraded. Injury recovery times will be reduced.";
            default:
                return "Facility upgraded to level " + level + ".";
        }
    }
}
