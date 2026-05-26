package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.NameGenerator;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class StaffService {

    @Autowired
    private HumanRepository humanRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private RoundRepository roundRepository;
    @Autowired
    private ManagerInboxRepository managerInboxRepository;

    private static final long[] COACH_TYPES = {
            TypeNames.ASSISTANT_MANAGER_TYPE,
            TypeNames.FIRST_TEAM_COACH_TYPE,
            TypeNames.FITNESS_COACH_TYPE,
            TypeNames.GK_COACH_TYPE,
            TypeNames.YOUTH_COACH_TYPE,
            TypeNames.HOYD_TYPE
    };

    // ==================== GENERATION ====================

    /**
     * Generate initial coaching staff for a team.
     * Each team gets: 1 assistant manager, 2 first-team coaches,
     * 1 fitness coach, 1 GK coach, 1 youth coach, 1 HOYD.
     */
    public void generateInitialStaff(long teamId, int season) {
        generateCoach(teamId, TypeNames.ASSISTANT_MANAGER_TYPE, season);
        generateCoach(teamId, TypeNames.FIRST_TEAM_COACH_TYPE, season);
        generateCoach(teamId, TypeNames.FIRST_TEAM_COACH_TYPE, season);
        generateCoach(teamId, TypeNames.FITNESS_COACH_TYPE, season);
        generateCoach(teamId, TypeNames.GK_COACH_TYPE, season);
        generateCoach(teamId, TypeNames.YOUTH_COACH_TYPE, season);
        generateCoach(teamId, TypeNames.HOYD_TYPE, season);
    }

    /**
     * Generate free agent coaches available on the market.
     */
    public void generateFreeAgentCoaches(int count, int season) {
        Random rng = new Random();
        for (int i = 0; i < count; i++) {
            long type = COACH_TYPES[rng.nextInt(COACH_TYPES.length)];
            generateCoach(0L, type, season);
        }
    }

    private Human generateCoach(long teamId, long typeId, int season) {
        Random rng = new Random();

        // Team reputation influences staff quality
        int baseQuality = 8;
        if (teamId > 0) {
            Team team = teamRepository.findById(teamId).orElse(null);
            if (team != null) {
                baseQuality = Math.max(5, Math.min(15, team.getReputation() / 7));
            }
        }

        Human coach = new Human();
        coach.setName(NameGenerator.generateName());
        coach.setAge(30 + rng.nextInt(25)); // 30-54
        coach.setTeamId(teamId == 0 ? 0L : teamId);
        coach.setTypeId(typeId);
        coach.setMorale(75);
        coach.setFitness(100);
        coach.setCurrentStatus("Active");
        coach.setSeasonCreated(season);

        // Contract
        coach.setContractEndSeason(season + 1 + rng.nextInt(3)); // 1-3 years
        int quality = Math.max(1, baseQuality + rng.nextInt(7) - 3); // ±3 from base
        coach.setWage((long) (quality * quality * 30)); // higher quality = much higher wage

        // Generate coaching attributes with specialization based on type
        int[] attrs = generateCoachingAttributes(typeId, quality, rng);
        coach.setCoachingAttacking(attrs[0]);
        coach.setCoachingDefending(attrs[1]);
        coach.setCoachingTactical(attrs[2]);
        coach.setCoachingTechnical(attrs[3]);
        coach.setCoachingMental(attrs[4]);
        coach.setCoachingFitness(attrs[5]);
        coach.setCoachingGK(attrs[6]);
        coach.setWorkingWithYoungsters(attrs[7]);
        coach.setMotivating(attrs[8]);

        // Set a useful "rating" as the average of all coaching attributes
        double avgAttr = (attrs[0] + attrs[1] + attrs[2] + attrs[3] + attrs[4]
                + attrs[5] + attrs[6] + attrs[7] + attrs[8]) / 9.0;
        coach.setRating(avgAttr);

        return humanRepository.save(coach);
    }

    private int[] generateCoachingAttributes(long typeId, int quality, Random rng) {
        // [atk, def, tac, tech, mental, fitness, gk, youngsters, motivating]
        int[] attrs = new int[9];

        // Base: quality ± random spread
        for (int i = 0; i < 9; i++) {
            attrs[i] = Math.max(1, Math.min(20, quality + rng.nextInt(5) - 2));
        }

        // Specialize based on type
        int boost = 2 + rng.nextInt(3); // 2-4 extra points in specialty
        switch ((int) typeId) {
            case 5: // Assistant Manager: well-rounded + motivating + tactical
                attrs[2] += boost; // tactical
                attrs[4] += boost; // mental
                attrs[8] += boost; // motivating
                break;
            case 6: // First Team Coach: attacking + technical + tactical
                attrs[0] += boost; // attacking
                attrs[3] += boost; // technical
                attrs[2] += boost - 1; // tactical
                break;
            case 7: // Fitness Coach: fitness dominant
                attrs[5] += boost + 2; // fitness
                attrs[4] += boost - 1; // mental
                break;
            case 8: // GK Coach: GK dominant
                attrs[6] += boost + 3; // GK
                break;
            case 9: // Youth Coach: youngsters + technical + mental
                attrs[7] += boost + 1; // youngsters
                attrs[3] += boost; // technical
                attrs[4] += boost - 1; // mental
                break;
            case 10: // HOYD: youngsters dominant + mental
                attrs[7] += boost + 2; // youngsters
                attrs[4] += boost; // mental
                break;
        }

        // Clamp all values to 1-20
        for (int i = 0; i < 9; i++) {
            attrs[i] = Math.max(1, Math.min(20, attrs[i]));
        }
        return attrs;
    }

    // ==================== QUERIES ====================

    public List<Human> getTeamStaff(long teamId) {
        List<Human> all = new ArrayList<>();
        for (long type : COACH_TYPES) {
            all.addAll(humanRepository.findAllByTeamIdAndTypeId(teamId, type));
        }
        return all;
    }

    public List<Human> getFreeAgentCoaches() {
        List<Human> all = new ArrayList<>();
        for (long type : COACH_TYPES) {
            all.addAll(humanRepository.findAllByTeamIdAndTypeId(0L, type));
        }
        return all;
    }

    // ==================== TRAINING IMPACT ====================

    /**
     * Calculate training star ratings (1-5) for each category based on coaching staff.
     * Used to replace the static facility-based multipliers.
     */
    public Map<String, Double> getTrainingStarRatings(long teamId) {
        List<Human> staff = getTeamStaff(teamId);
        Map<String, Double> stars = new LinkedHashMap<>();

        stars.put("attacking", avgAttr(staff, Human::getCoachingAttacking));
        stars.put("defending", avgAttr(staff, Human::getCoachingDefending));
        stars.put("tactical", avgAttr(staff, Human::getCoachingTactical));
        stars.put("technical", avgAttr(staff, Human::getCoachingTechnical));
        stars.put("mental", avgAttr(staff, Human::getCoachingMental));
        stars.put("fitness", avgAttr(staff, Human::getCoachingFitness));
        stars.put("goalkeeping", avgAttr(staff, Human::getCoachingGK));
        stars.put("youth", avgAttr(staff, Human::getWorkingWithYoungsters));

        return stars;
    }

    /**
     * Get the overall coaching quality multiplier for player training.
     * Replaces the old facilityMultiplier with a staff-derived value.
     * Returns 0.5 (no coaches) to 1.5 (elite coaches).
     */
    public double getCoachingMultiplier(long teamId) {
        List<Human> staff = getTeamStaff(teamId);
        if (staff.isEmpty()) return 0.5;

        double avg = staff.stream()
                .mapToDouble(c -> (c.getCoachingAttacking() + c.getCoachingDefending() +
                        c.getCoachingTactical() + c.getCoachingTechnical() +
                        c.getCoachingMental() + c.getCoachingFitness()) / 6.0)
                .average().orElse(5.0);

        // Map 1-20 scale to 0.5-1.5 multiplier
        return 0.5 + (avg / 20.0);
    }

    /**
     * Get youth coaching multiplier from HOYD + youth coaches.
     * Returns 0.5 to 1.5.
     */
    public double getYouthCoachingMultiplier(long teamId) {
        List<Human> staff = getTeamStaff(teamId);
        List<Human> youthStaff = staff.stream()
                .filter(s -> s.getTypeId() == TypeNames.YOUTH_COACH_TYPE || s.getTypeId() == TypeNames.HOYD_TYPE)
                .collect(Collectors.toList());

        if (youthStaff.isEmpty()) return 0.5;

        double avg = youthStaff.stream()
                .mapToDouble(Human::getWorkingWithYoungsters)
                .average().orElse(5.0);

        return 0.5 + (avg / 20.0);
    }

    /**
     * Get HOYD quality for youth player generation.
     * Returns 1-20 (the HOYD's workingWithYoungsters attribute), or 5 if no HOYD.
     */
    public int getHOYDQuality(long teamId) {
        return humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.HOYD_TYPE).stream()
                .findFirst()
                .map(Human::getWorkingWithYoungsters)
                .orElse(5);
    }

    /**
     * Get assistant manager's motivating attribute for press conference delegation.
     */
    public int getAssistantMotivating(long teamId) {
        return humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.ASSISTANT_MANAGER_TYPE).stream()
                .findFirst()
                .map(Human::getMotivating)
                .orElse(5);
    }

    // ==================== HIRING / FIRING ====================

    public Map<String, Object> hireCoach(long coachId, long teamId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Optional<Human> coachOpt = humanRepository.findById(coachId);
        if (coachOpt.isEmpty()) {
            result.put("success", false);
            result.put("message", "Coach not found");
            return result;
        }

        Human coach = coachOpt.get();
        if (coach.getTeamId() != null && coach.getTeamId() != 0L) {
            result.put("success", false);
            result.put("message", "Coach is already employed by another club");
            return result;
        }

        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            result.put("success", false);
            result.put("message", "Team not found");
            return result;
        }

        Round round = roundRepository.findById(1L).orElse(new Round());
        int season = (int) round.getSeason();

        coach.setTeamId(teamId);
        coach.setContractEndSeason(season + 2);
        humanRepository.save(coach);

        // Adjust salary budget
        team.setSalaryBudget(team.getSalaryBudget() + coach.getWage());
        teamRepository.save(team);

        result.put("success", true);
        result.put("message", coach.getName() + " has been hired as " + TypeNames.coachTypeName(coach.getTypeId()));
        return result;
    }

    public Map<String, Object> fireCoach(long coachId, long teamId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Optional<Human> coachOpt = humanRepository.findById(coachId);
        if (coachOpt.isEmpty()) {
            result.put("success", false);
            result.put("message", "Coach not found");
            return result;
        }

        Human coach = coachOpt.get();
        if (coach.getTeamId() == null || coach.getTeamId() != teamId) {
            result.put("success", false);
            result.put("message", "Coach is not employed by your club");
            return result;
        }

        Team team = teamRepository.findById(teamId).orElse(null);

        coach.setTeamId(0L);
        humanRepository.save(coach);

        if (team != null) {
            team.setSalaryBudget(team.getSalaryBudget() - coach.getWage());
            teamRepository.save(team);
        }

        result.put("success", true);
        result.put("message", coach.getName() + " has been released from the club");
        return result;
    }

    // ==================== STAFF OVERVIEW ====================

    public Map<String, Object> getStaffOverview(long teamId) {
        List<Human> staff = getTeamStaff(teamId);
        Map<String, Object> overview = new LinkedHashMap<>();

        List<Map<String, Object>> staffList = staff.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("age", c.getAge());
            m.put("role", TypeNames.coachTypeName(c.getTypeId()));
            m.put("typeId", c.getTypeId());
            m.put("wage", c.getWage());
            m.put("contractEndSeason", c.getContractEndSeason());
            m.put("coachingAttacking", c.getCoachingAttacking());
            m.put("coachingDefending", c.getCoachingDefending());
            m.put("coachingTactical", c.getCoachingTactical());
            m.put("coachingTechnical", c.getCoachingTechnical());
            m.put("coachingMental", c.getCoachingMental());
            m.put("coachingFitness", c.getCoachingFitness());
            m.put("coachingGK", c.getCoachingGK());
            m.put("workingWithYoungsters", c.getWorkingWithYoungsters());
            m.put("motivating", c.getMotivating());
            return m;
        }).collect(Collectors.toList());

        overview.put("staff", staffList);
        overview.put("trainingRatings", getTrainingStarRatings(teamId));
        overview.put("coachingMultiplier", getCoachingMultiplier(teamId));
        overview.put("youthMultiplier", getYouthCoachingMultiplier(teamId));
        overview.put("hoydQuality", getHOYDQuality(teamId));

        return overview;
    }

    // ==================== HELPERS ====================

    private double avgAttr(List<Human> staff, java.util.function.ToIntFunction<Human> getter) {
        if (staff.isEmpty()) return 1.0;
        double avg = staff.stream().mapToInt(getter).average().orElse(5.0);
        // Map 1-20 -> 1.0-5.0 stars
        return Math.round(avg / 4.0 * 10.0) / 10.0;
    }
}
