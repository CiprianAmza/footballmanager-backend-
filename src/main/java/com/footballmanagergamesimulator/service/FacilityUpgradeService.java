package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.user.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class FacilityUpgradeService {

    private static final int MAX_LEVEL = 10;

    @Autowired
    private UserContext userContext;
    @Autowired
    private FacilityUpgradeRepository facilityUpgradeRepository;
    @Autowired
    private TeamFacilitiesRepository teamFacilitiesRepository;
    @Autowired
    private StadiumRepository stadiumRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private ManagerInboxRepository managerInboxRepository;
    @Autowired
    @Lazy
    private FinanceService financeService;

    /**
     * All upgradeable facility types with their base cost and base duration (days).
     */
    private static final Map<String, long[]> FACILITY_CONFIG = new LinkedHashMap<>();
    static {
        // facilityType -> [baseCost, baseDurationDays]
        FACILITY_CONFIG.put("TRAINING_GROUND",    new long[]{500_000, 30});
        FACILITY_CONFIG.put("YOUTH_ACADEMY",      new long[]{400_000, 25});
        FACILITY_CONFIG.put("MEDICAL_CENTER",     new long[]{350_000, 20});
        FACILITY_CONFIG.put("STADIUM_EXPANSION",  new long[]{5_000_000, 60});
        FACILITY_CONFIG.put("VIP_BOXES",          new long[]{2_000_000, 40});
        FACILITY_CONFIG.put("CATERING",           new long[]{800_000, 25});
        FACILITY_CONFIG.put("FAN_SHOP",           new long[]{600_000, 20});
        FACILITY_CONFIG.put("FAST_FOOD",          new long[]{500_000, 20});
        FACILITY_CONFIG.put("HEADQUARTERS",       new long[]{1_500_000, 35});
        FACILITY_CONFIG.put("TRAINING_PITCH",     new long[]{1_000_000, 30});
        FACILITY_CONFIG.put("PARKING",            new long[]{400_000, 15});
    }

    public FacilityUpgrade startUpgrade(long teamId, String facilityType, int season) {
        return startUpgrade(teamId, facilityType, season, 0);
    }

    public FacilityUpgrade startUpgrade(long teamId, String facilityType, int season, int currentDay) {
        long[] config = FACILITY_CONFIG.get(facilityType);
        if (config == null) return null;

        int currentLevel = getFacilityLevel(teamId, facilityType);
        if (currentLevel >= MAX_LEVEL) return null;

        // Check if there's already an upgrade in progress for this facility
        List<FacilityUpgrade> inProgress = facilityUpgradeRepository.findAllByTeamIdAndCompletedFalse(teamId);
        for (FacilityUpgrade u : inProgress) {
            if (u.getFacilityType().equals(facilityType)) return null;
        }

        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) return null;

        long baseCost = config[0];
        int baseDuration = (int) config[1];

        // Cost and duration scale with target level
        int targetLevel = currentLevel + 1;
        long cost = baseCost * targetLevel;
        int durationDays = baseDuration + (targetLevel * 5);

        if (team.getTotalFinances() < cost) return null;

        // Record expense
        financeService.recordExpense(teamId, season, currentDay, "OTHER",
                "Facility upgrade: " + formatFacilityName(facilityType) + " (Level " + targetLevel + ")", cost);
        team = teamRepository.findById(teamId).orElse(team);
        team.setTransferBudget(Math.max(0, team.getTransferBudget() - cost));
        teamRepository.save(team);

        FacilityUpgrade upgrade = new FacilityUpgrade();
        upgrade.setTeamId(teamId);
        upgrade.setFacilityType(facilityType);
        upgrade.setCurrentLevel(currentLevel);
        upgrade.setTargetLevel(targetLevel);
        upgrade.setCost(cost);
        upgrade.setStartDay(currentDay);
        upgrade.setStartSeason(season);
        upgrade.setDurationDays(durationDays);
        upgrade.setCompleted(false);

        return facilityUpgradeRepository.save(upgrade);
    }

    public void checkUpgradeCompletion(long teamId, int currentDay) {
        checkUpgradeCompletion(teamId, currentDay, 0);
    }

    public void checkUpgradeCompletion(long teamId, int currentDay, int currentSeason) {
        List<FacilityUpgrade> inProgress = facilityUpgradeRepository.findAllByTeamIdAndCompletedFalse(teamId);

        for (FacilityUpgrade upgrade : inProgress) {
            boolean shouldComplete = false;

            if (currentSeason > 0 && upgrade.getStartSeason() < currentSeason) {
                // Upgrade started in a previous season - it should be done by now
                // Calculate remaining days that spill into the new season
                int daysLeftInOldSeason = 365 - upgrade.getStartDay();
                int daysNeededInNewSeason = upgrade.getDurationDays() - daysLeftInOldSeason;
                if (daysNeededInNewSeason <= 0 || currentDay >= daysNeededInNewSeason) {
                    shouldComplete = true;
                }
            } else {
                int endDay = upgrade.getStartDay() + upgrade.getDurationDays();
                if (currentDay >= endDay) {
                    shouldComplete = true;
                }
            }

            if (shouldComplete) {
                upgrade.setCompleted(true);
                facilityUpgradeRepository.save(upgrade);

                setFacilityLevel(teamId, upgrade.getFacilityType(), upgrade.getTargetLevel());

                if (userContext.isHumanTeam(teamId)) {
                    sendUpgradeCompleteNotification(upgrade);
                }
            }
        }
    }

    /**
     * Get the current level for any facility type.
     */
    public int getFacilityLevel(long teamId, String facilityType) {
        switch (facilityType) {
            case "TRAINING_GROUND": {
                TeamFacilities f = teamFacilitiesRepository.findByTeamId(teamId);
                return f != null ? (int) f.getSeniorTrainingLevel() : 1;
            }
            case "YOUTH_ACADEMY": {
                TeamFacilities f = teamFacilitiesRepository.findByTeamId(teamId);
                return f != null ? (int) f.getYouthAcademyLevel() : 1;
            }
            case "MEDICAL_CENTER": {
                TeamFacilities f = teamFacilitiesRepository.findByTeamId(teamId);
                return f != null ? f.getScoutingLevel() : 1;
            }
            case "STADIUM_EXPANSION": {
                Stadium s = stadiumRepository.findByTeamId(teamId).orElse(null);
                return s != null ? s.getExpansionLevel() : 0;
            }
            case "VIP_BOXES": {
                Stadium s = stadiumRepository.findByTeamId(teamId).orElse(null);
                return s != null ? s.getVipBoxesLevel() : 0;
            }
            case "CATERING": {
                Stadium s = stadiumRepository.findByTeamId(teamId).orElse(null);
                return s != null ? s.getCateringLevel() : 0;
            }
            case "FAN_SHOP": {
                Stadium s = stadiumRepository.findByTeamId(teamId).orElse(null);
                return s != null ? s.getFanShopLevel() : 0;
            }
            case "FAST_FOOD": {
                Stadium s = stadiumRepository.findByTeamId(teamId).orElse(null);
                return s != null ? s.getFastFoodLevel() : 0;
            }
            case "HEADQUARTERS": {
                Stadium s = stadiumRepository.findByTeamId(teamId).orElse(null);
                return s != null ? s.getHeadquartersLevel() : 1;
            }
            case "TRAINING_PITCH": {
                Stadium s = stadiumRepository.findByTeamId(teamId).orElse(null);
                return s != null ? s.getTrainingPitchLevel() : 1;
            }
            case "PARKING": {
                Stadium s = stadiumRepository.findByTeamId(teamId).orElse(null);
                return s != null ? s.getParkingLevel() : 0;
            }
            default:
                return 0;
        }
    }

    /**
     * Set the level for a facility type after upgrade completes.
     */
    private void setFacilityLevel(long teamId, String facilityType, int level) {
        switch (facilityType) {
            case "TRAINING_GROUND": {
                TeamFacilities f = teamFacilitiesRepository.findByTeamId(teamId);
                if (f != null) { f.setSeniorTrainingLevel(level); teamFacilitiesRepository.save(f); }
                break;
            }
            case "YOUTH_ACADEMY": {
                TeamFacilities f = teamFacilitiesRepository.findByTeamId(teamId);
                if (f != null) { f.setYouthAcademyLevel(level); teamFacilitiesRepository.save(f); }
                break;
            }
            case "MEDICAL_CENTER": {
                TeamFacilities f = teamFacilitiesRepository.findByTeamId(teamId);
                if (f != null) { f.setScoutingLevel(level); teamFacilitiesRepository.save(f); }
                break;
            }
            case "STADIUM_EXPANSION": {
                Stadium s = getOrCreateStadium(teamId);
                s.setExpansionLevel(level);
                stadiumRepository.save(s);
                break;
            }
            case "VIP_BOXES": {
                Stadium s = getOrCreateStadium(teamId);
                s.setVipBoxesLevel(level);
                stadiumRepository.save(s);
                break;
            }
            case "CATERING": {
                Stadium s = getOrCreateStadium(teamId);
                s.setCateringLevel(level);
                stadiumRepository.save(s);
                break;
            }
            case "FAN_SHOP": {
                Stadium s = getOrCreateStadium(teamId);
                s.setFanShopLevel(level);
                stadiumRepository.save(s);
                break;
            }
            case "FAST_FOOD": {
                Stadium s = getOrCreateStadium(teamId);
                s.setFastFoodLevel(level);
                stadiumRepository.save(s);
                break;
            }
            case "HEADQUARTERS": {
                Stadium s = getOrCreateStadium(teamId);
                s.setHeadquartersLevel(level);
                stadiumRepository.save(s);
                break;
            }
            case "TRAINING_PITCH": {
                Stadium s = getOrCreateStadium(teamId);
                s.setTrainingPitchLevel(level);
                stadiumRepository.save(s);
                break;
            }
            case "PARKING": {
                Stadium s = getOrCreateStadium(teamId);
                s.setParkingLevel(level);
                stadiumRepository.save(s);
                break;
            }
        }
    }

    private Stadium getOrCreateStadium(long teamId) {
        return stadiumRepository.findByTeamId(teamId).orElseGet(() -> {
            Team team = teamRepository.findById(teamId).orElse(null);
            Stadium s = new Stadium();
            s.setTeamId(teamId);
            s.setCapacity(team != null ? team.getStadiumCapacity() : 30000);
            s.setStadiumName(team != null ? team.getStadiumName() : "Stadium");
            return stadiumRepository.save(s);
        });
    }

    // ==================== QUERIES ====================

    public List<FacilityUpgrade> getUpgradesInProgress(long teamId) {
        return facilityUpgradeRepository.findAllByTeamIdAndCompletedFalse(teamId);
    }

    public TeamFacilities getFacilities(long teamId) {
        return teamFacilitiesRepository.findByTeamId(teamId);
    }

    public Stadium getStadium(long teamId) {
        return getOrCreateStadium(teamId);
    }

    /**
     * Get full overview of all facilities and available upgrades for a team.
     */
    public Map<String, Object> getFullFacilityOverview(long teamId) {
        Map<String, Object> overview = new LinkedHashMap<>();

        TeamFacilities facilities = teamFacilitiesRepository.findByTeamId(teamId);
        Stadium stadium = getOrCreateStadium(teamId);
        List<FacilityUpgrade> inProgress = getUpgradesInProgress(teamId);

        overview.put("facilities", facilities);
        overview.put("stadium", stadium);
        overview.put("effectiveCapacity", stadium.getEffectiveCapacity());
        overview.put("revenueMultiplier", stadium.getRevenueMultiplier());
        overview.put("upgradesInProgress", inProgress);

        // Build available upgrades list
        Set<String> upgrading = new HashSet<>();
        for (FacilityUpgrade u : inProgress) {
            upgrading.add(u.getFacilityType());
        }

        List<Map<String, Object>> available = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : FACILITY_CONFIG.entrySet()) {
            String type = entry.getKey();
            long[] config = entry.getValue();
            int currentLevel = getFacilityLevel(teamId, type);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", type);
            item.put("name", formatFacilityName(type));
            item.put("description", getFacilityDescription(type));
            item.put("currentLevel", currentLevel);
            item.put("maxLevel", MAX_LEVEL);
            item.put("upgrading", upgrading.contains(type));

            if (currentLevel < MAX_LEVEL && !upgrading.contains(type)) {
                int targetLevel = currentLevel + 1;
                long cost = config[0] * targetLevel;
                int duration = (int) config[1] + (targetLevel * 5);
                item.put("upgradeCost", cost);
                item.put("upgradeDuration", duration);
                item.put("canUpgrade", true);
            } else {
                item.put("canUpgrade", false);
            }

            available.add(item);
        }
        overview.put("availableUpgrades", available);

        return overview;
    }

    private String formatFacilityName(String facilityType) {
        switch (facilityType) {
            case "TRAINING_GROUND": return "Training Ground";
            case "YOUTH_ACADEMY": return "Youth Academy";
            case "MEDICAL_CENTER": return "Medical Center";
            case "STADIUM_EXPANSION": return "Stadium Expansion";
            case "VIP_BOXES": return "VIP Boxes";
            case "CATERING": return "Catering Facilities";
            case "FAN_SHOP": return "Fan Shop";
            case "FAST_FOOD": return "Fast Food Area";
            case "HEADQUARTERS": return "Club Headquarters";
            case "TRAINING_PITCH": return "Training Pitch";
            case "PARKING": return "Parking Area";
            default: return facilityType;
        }
    }

    private String getFacilityDescription(String facilityType) {
        switch (facilityType) {
            case "TRAINING_GROUND": return "Improves player development during training sessions.";
            case "YOUTH_ACADEMY": return "Higher potential for youth academy prospects.";
            case "MEDICAL_CENTER": return "Reduces injury recovery time.";
            case "STADIUM_EXPANSION": return "Adds 5,000 seats per level, increasing match day attendance.";
            case "VIP_BOXES": return "Premium seating areas. +8% match day revenue per level.";
            case "CATERING": return "In-stadium food and drink service. +4% match day revenue per level.";
            case "FAN_SHOP": return "Official merchandise store. +3% match day revenue per level.";
            case "FAST_FOOD": return "Fast food outlets in the stadium. +3% match day revenue per level.";
            case "HEADQUARTERS": return "Club offices. Improves administrative efficiency and board confidence growth.";
            case "TRAINING_PITCH": return "Better training surfaces. Boosts training effectiveness.";
            case "PARKING": return "Stadium parking facilities. +2% match day revenue per level.";
            default: return "";
        }
    }

    private void sendUpgradeCompleteNotification(FacilityUpgrade upgrade) {
        String facilityName = formatFacilityName(upgrade.getFacilityType());
        String effectDesc = getFacilityDescription(upgrade.getFacilityType());

        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(upgrade.getTeamId());
        inbox.setSeasonNumber(upgrade.getStartSeason());
        inbox.setRoundNumber(0);
        inbox.setTitle("Facility Upgrade Complete: " + facilityName);
        inbox.setContent(facilityName + " has been upgraded to level "
                + upgrade.getTargetLevel() + ".\n\n" + effectDesc);
        inbox.setCategory("facility");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        managerInboxRepository.save(inbox);
    }
}
