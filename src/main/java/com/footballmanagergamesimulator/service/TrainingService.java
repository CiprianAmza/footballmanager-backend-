package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Injury;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.TrainingSchedule;
import com.footballmanagergamesimulator.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TrainingService {

    @Autowired
    HumanRepository humanRepository;
    @Autowired
    PlayerSkillsRepository playerSkillsRepository;
    @Autowired
    InjuryRepository injuryRepository;
    @Autowired
    TrainingScheduleRepository trainingScheduleRepository;
    @Autowired
    TeamRepository teamRepository;

    private static final String[] TRAINING_INJURY_TYPES = {
            "Muscle Strain", "Twisted Ankle", "Minor Knock", "Hamstring Tweak", "Bruised Shin"
    };

    // Attributes that decline with age (physical)
    private static final Set<String> PHYSICAL_ATTRS = Set.of(
            "Acceleration", "Agility", "Balance", "Jumping Reach", "Natural Fitness",
            "Pace", "Stamina", "Strength");

    // Attributes that can grow even late career (mental)
    private static final Set<String> MENTAL_ATTRS = Set.of(
            "Aggression", "Anticipation", "Bravery", "Composure", "Concentration",
            "Decisions", "Determination", "Flair", "Leadership", "Off The Ball",
            "Positioning", "Teamwork", "Vision", "Work Rate");

    // Training focus -> which attributes improve faster
    private static final Map<String, Set<String>> TRAINING_FOCUS_ATTRS = Map.of(
            "Attacking", Set.of("Finishing", "Off The Ball", "Composure", "Dribbling",
                    "First Touch", "Long Shots", "Technique", "Anticipation", "Flair"),
            "Defensive", Set.of("Tackling", "Marking", "Positioning", "Concentration",
                    "Heading", "Bravery", "Strength", "Anticipation", "Aggression"),
            "Tactical", Set.of("Decisions", "Teamwork", "Vision", "Passing", "Positioning",
                    "Anticipation", "Composure", "Off The Ball", "Work Rate"),
            "Physical", Set.of("Acceleration", "Pace", "Stamina", "Strength", "Agility",
                    "Balance", "Jumping Reach", "Natural Fitness"),
            "General", Set.of("First Touch", "Passing", "Technique", "Decisions",
                    "Stamina", "Work Rate", "Teamwork", "Composure")
    );

    public void processTrainingSession(long teamId, int season) {

        Random random = new Random();

        // Get all players for team (typeId=1, not retired)
        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, 1L)
                .stream()
                .filter(h -> !h.isRetired())
                .collect(Collectors.toList());

        // Get currently injured player IDs
        Set<Long> injuredPlayerIds = injuryRepository.findAllByTeamIdAndDaysRemainingGreaterThan(teamId, 0)
                .stream()
                .map(Injury::getPlayerId)
                .collect(Collectors.toSet());

        // Get training focus
        String trainingFocus = getTrainingFocus(teamId);
        Set<String> focusAttrs = TRAINING_FOCUS_ATTRS.getOrDefault(trainingFocus, TRAINING_FOCUS_ATTRS.get("General"));

        List<Human> modifiedPlayers = new ArrayList<>();
        List<PlayerSkills> modifiedSkills = new ArrayList<>();

        for (Human player : players) {
            // Skip injured players
            if (injuredPlayerIds.contains(player.getId())) {
                continue;
            }

            // Increase fitness by random(0.5, 1.5), cap at 100
            double fitnessGain = random.nextDouble(0.5, 1.5);
            player.setFitness(Math.min(100.0, player.getFitness() + fitnessGain));

            // Determine effective training focus for this player (individual overrides team)
            Set<String> effectiveFocusAttrs = getEffectiveFocusAttrs(player, focusAttrs);

            // Train individual attributes
            Optional<PlayerSkills> skillsOpt = playerSkillsRepository.findPlayerSkillsByPlayerId(player.getId());
            if (skillsOpt.isPresent()) {
                PlayerSkills skills = skillsOpt.get();
                boolean attributeChanged = trainAttributes(player, skills, effectiveFocusAttrs, random);

                if (attributeChanged) {
                    // Recompute overall rating from updated attributes
                    double newRating = PlayerSkillsService.computeOverallRating(skills);
                    // Cap at potential ability
                    if (player.getPotentialAbility() > 0) {
                        newRating = Math.min(newRating, player.getPotentialAbility());
                    }
                    newRating = Math.max(1.0, newRating);
                    player.setRating(newRating);

                    if (newRating > player.getBestEverRating()) {
                        player.setBestEverRating(newRating);
                        player.setSeasonOfBestEverRating(season);
                    }
                    modifiedSkills.add(skills);
                }
            } else {
                // Fallback: old-style rating-only training
                double ratingChange = calculateDevelopmentChange(player, random);
                if (ratingChange != 0) {
                    double newRating = player.getRating() + ratingChange;
                    if (ratingChange > 0 && player.getPotentialAbility() > 0) {
                        newRating = Math.min(newRating, player.getPotentialAbility());
                    }
                    newRating = Math.max(newRating, 1.0);
                    player.setRating(newRating);

                    if (newRating > player.getBestEverRating()) {
                        player.setBestEverRating(newRating);
                        player.setSeasonOfBestEverRating(season);
                    }
                }
            }

            // Very small chance (1%) of training injury
            if (random.nextDouble() < 0.01) {
                Injury injury = new Injury();
                injury.setPlayerId(player.getId());
                injury.setTeamId(teamId);
                injury.setInjuryType(TRAINING_INJURY_TYPES[random.nextInt(TRAINING_INJURY_TYPES.length)]);
                injury.setSeverity("Minor");
                injury.setDaysRemaining(random.nextInt(3, 15)); // 3-14 days
                injury.setSeasonNumber(season);
                injuryRepository.save(injury);

                player.setCurrentStatus("Injured");
            }

            modifiedPlayers.add(player);
        }

        humanRepository.saveAll(modifiedPlayers);
        if (!modifiedSkills.isEmpty()) {
            playerSkillsRepository.saveAll(modifiedSkills);
        }
    }

    /**
     * Train individual attributes based on age, training focus, and position.
     * Returns true if any attribute was changed.
     *
     * Young players: all attributes can grow, physical + technical have higher chance
     * Peak players: mental attributes still develop, physical/technical maintain
     * Old players: physical attributes decline, mental can still grow slightly
     */
    private boolean trainAttributes(Human player, PlayerSkills skills, Set<String> focusAttrs, Random random) {
        int age = player.getAge();
        int matchesPlayed = player.getSeasonMatchesPlayed();
        double matchBonus = Math.min(matchesPlayed * 0.002, 0.05);
        boolean anyChanged = false;

        for (Map.Entry<String, Function<PlayerSkills, Integer>> entry : PlayerSkillsService.GETTER_MAP.entrySet()) {
            String attrName = entry.getKey();
            int currentVal = entry.getValue().apply(skills);

            // Skip GK attrs for outfield and vice versa
            if (!isRelevantAttribute(attrName, skills.getPosition())) continue;

            boolean isPhysical = PHYSICAL_ATTRS.contains(attrName);
            boolean isMental = MENTAL_ATTRS.contains(attrName);
            boolean isFocused = focusAttrs.contains(attrName);

            double changeChance;
            double changeAmount;

            if (age <= 20) {
                // Youth: high growth everywhere, focused attrs even more
                changeChance = (isFocused ? 0.12 : 0.06) + matchBonus;
                changeAmount = random.nextDouble(0.3, 1.0);
            } else if (age <= 23) {
                changeChance = (isFocused ? 0.08 : 0.04) + matchBonus;
                changeAmount = random.nextDouble(0.2, 0.8);
            } else if (age <= 26) {
                changeChance = (isFocused ? 0.05 : 0.02) + matchBonus;
                changeAmount = random.nextDouble(0.1, 0.5);
            } else if (age <= 29) {
                // Peak: mental still grows, physical starts to stagnate
                if (isPhysical) {
                    changeChance = 0.02;
                    changeAmount = random.nextDouble(-0.2, 0.2);
                } else if (isMental) {
                    changeChance = (isFocused ? 0.04 : 0.02) + matchBonus * 0.5;
                    changeAmount = random.nextDouble(0.1, 0.4);
                } else {
                    changeChance = 0.02;
                    changeAmount = random.nextDouble(0.0, 0.3);
                }
            } else if (age <= 31) {
                // Early decline
                if (isPhysical) {
                    changeChance = 0.06;
                    changeAmount = -random.nextDouble(0.1, 0.4);
                } else if (isMental) {
                    changeChance = 0.03;
                    changeAmount = random.nextDouble(-0.1, 0.3);
                } else {
                    changeChance = 0.03;
                    changeAmount = random.nextDouble(-0.2, 0.1);
                }
            } else if (age <= 33) {
                // Noticeable decline
                if (isPhysical) {
                    changeChance = 0.10;
                    changeAmount = -random.nextDouble(0.2, 0.6);
                } else if (isMental) {
                    changeChance = 0.02;
                    changeAmount = random.nextDouble(-0.1, 0.15);
                } else {
                    changeChance = 0.06;
                    changeAmount = -random.nextDouble(0.1, 0.3);
                }
            } else {
                // 34+: significant decline, especially physical
                if (isPhysical) {
                    changeChance = 0.15;
                    changeAmount = -random.nextDouble(0.3, 0.8);
                } else if (isMental) {
                    changeChance = 0.01;
                    changeAmount = random.nextDouble(-0.05, 0.1);
                } else {
                    changeChance = 0.08;
                    changeAmount = -random.nextDouble(0.1, 0.4);
                }
            }

            if (random.nextDouble() < changeChance) {
                int newVal = (int) Math.round(currentVal + changeAmount);
                newVal = Math.max(1, Math.min(20, newVal));
                if (newVal != currentVal) {
                    PlayerSkillsService.SETTER_MAP.get(attrName).accept(skills, newVal);
                    anyChanged = true;
                }
            }
        }

        return anyChanged;
    }

    private boolean isRelevantAttribute(String attrName, String position) {
        boolean isGK = "GK".equals(position);
        boolean isGKAttr = PlayerSkillsService.GOALKEEPER.contains(attrName);
        // GK trains GK attrs + mental + physical; outfield trains non-GK attrs
        if (isGK) return true;
        return !isGKAttr;
    }

    private String getTrainingFocus(long teamId) {
        List<TrainingSchedule> schedules = trainingScheduleRepository.findAllByTeamId(teamId);
        if (schedules.isEmpty()) return "General";
        return schedules.get(0).getSessionName();
    }

    /**
     * Determines the effective training focus attributes for a specific player.
     * Individual settings override team focus.
     * Priority: specific attribute > individual focus > role-based > team focus
     */
    private Set<String> getEffectiveFocusAttrs(Human player, Set<String> teamFocusAttrs) {
        // 1. If player has a specific attribute focus, boost that attribute heavily
        if (player.getIndividualTrainingAttribute() != null && !player.getIndividualTrainingAttribute().isEmpty()) {
            Set<String> attrs = new HashSet<>(teamFocusAttrs); // start with team focus
            attrs.add(player.getIndividualTrainingAttribute()); // ensure the target attribute is in the set
            return attrs;
        }

        // 2. If player has an individual training focus category
        if (player.getIndividualTrainingFocus() != null && !player.getIndividualTrainingFocus().isEmpty()) {
            return TRAINING_FOCUS_ATTRS.getOrDefault(player.getIndividualTrainingFocus(), teamFocusAttrs);
        }

        // 3. If player has a role training focus, derive key attributes from that role
        if (player.getIndividualTrainingRole() != null && !player.getIndividualTrainingRole().isEmpty()) {
            Set<String> roleAttrs = getRoleTrainingAttrs(player.getIndividualTrainingRole());
            if (!roleAttrs.isEmpty()) return roleAttrs;
        }

        // 4. Default: use team focus
        return teamFocusAttrs;
    }

    // Role → key attributes mapping for individual role training
    private static final Map<String, Set<String>> ROLE_TRAINING_ATTRS = Map.ofEntries(
            // GK
            Map.entry("Goalkeeper", Set.of("Handling", "Reflexes", "One On Ones", "Command Of Area", "Kicking", "Concentration")),
            Map.entry("Sweeper Keeper", Set.of("Handling", "Reflexes", "Kicking", "First Touch", "Passing", "Anticipation")),
            // DC
            Map.entry("Central Defender", Set.of("Tackling", "Marking", "Heading", "Positioning", "Strength", "Concentration")),
            Map.entry("Ball-Playing Defender", Set.of("Passing", "First Touch", "Tackling", "Composure", "Vision", "Positioning")),
            Map.entry("Stopper", Set.of("Tackling", "Marking", "Heading", "Aggression", "Bravery", "Strength")),
            // DL/DR
            Map.entry("Full-Back", Set.of("Tackling", "Marking", "Crossing", "Stamina", "Pace", "Positioning")),
            Map.entry("Wing-Back", Set.of("Crossing", "Dribbling", "Stamina", "Pace", "Off The Ball", "Technique")),
            Map.entry("Inverted Wing-Back", Set.of("Passing", "First Touch", "Decisions", "Technique", "Composure", "Vision")),
            // MC
            Map.entry("Box-to-Box Midfielder", Set.of("Stamina", "Tackling", "Passing", "Work Rate", "Off The Ball", "Technique")),
            Map.entry("Deep-Lying Playmaker", Set.of("Passing", "Vision", "First Touch", "Composure", "Decisions", "Technique")),
            Map.entry("Advanced Playmaker", Set.of("Passing", "Vision", "Dribbling", "Technique", "Flair", "First Touch")),
            Map.entry("Ball-Winning Midfielder", Set.of("Tackling", "Marking", "Stamina", "Work Rate", "Aggression", "Anticipation")),
            Map.entry("Mezzala", Set.of("Dribbling", "Passing", "Off The Ball", "Technique", "Stamina", "Vision")),
            Map.entry("Defensive Midfielder", Set.of("Tackling", "Marking", "Positioning", "Concentration", "Strength", "Anticipation")),
            // ML/MR
            Map.entry("Winger", Set.of("Crossing", "Dribbling", "Pace", "Acceleration", "Technique", "Flair")),
            Map.entry("Inside Forward", Set.of("Finishing", "Dribbling", "Off The Ball", "Acceleration", "Composure", "Technique")),
            Map.entry("Wide Midfielder", Set.of("Crossing", "Stamina", "Work Rate", "Passing", "Teamwork", "Technique")),
            Map.entry("Inverted Winger", Set.of("Dribbling", "Finishing", "Long Shots", "Technique", "Flair", "Acceleration")),
            // ST
            Map.entry("Advanced Forward", Set.of("Finishing", "Off The Ball", "Composure", "First Touch", "Acceleration", "Technique")),
            Map.entry("Complete Forward", Set.of("Finishing", "Heading", "First Touch", "Strength", "Off The Ball", "Composure")),
            Map.entry("Poacher", Set.of("Finishing", "Off The Ball", "Composure", "Anticipation", "Acceleration", "Concentration")),
            Map.entry("Target Man", Set.of("Heading", "Strength", "First Touch", "Finishing", "Off The Ball", "Bravery")),
            Map.entry("Deep-Lying Forward", Set.of("First Touch", "Passing", "Vision", "Technique", "Off The Ball", "Composure")),
            Map.entry("Pressing Forward", Set.of("Work Rate", "Stamina", "Off The Ball", "Aggression", "Finishing", "Anticipation"))
    );

    private Set<String> getRoleTrainingAttrs(String roleName) {
        return ROLE_TRAINING_ATTRS.getOrDefault(roleName, Set.of());
    }

    /**
     * Set individual training for a player.
     */
    public void setIndividualTraining(long playerId, String focus, String attribute, String role) {
        Optional<Human> playerOpt = humanRepository.findById(playerId);
        if (playerOpt.isEmpty()) return;

        Human player = playerOpt.get();
        player.setIndividualTrainingFocus(focus);
        player.setIndividualTrainingAttribute(attribute);
        player.setIndividualTrainingRole(role);
        humanRepository.save(player);
    }

    /**
     * Get individual training info for a player.
     */
    public Map<String, Object> getIndividualTraining(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Optional<Human> playerOpt = humanRepository.findById(playerId);
        if (playerOpt.isEmpty()) {
            result.put("error", "Player not found");
            return result;
        }

        Human player = playerOpt.get();
        result.put("playerId", player.getId());
        result.put("playerName", player.getName());
        result.put("individualFocus", player.getIndividualTrainingFocus());
        result.put("individualAttribute", player.getIndividualTrainingAttribute());
        result.put("individualRole", player.getIndividualTrainingRole());
        result.put("position", player.getPosition());
        return result;
    }

    /**
     * Get available individual training options for a player's position.
     */
    public Map<String, Object> getAvailableIndividualTraining(String position) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Focus categories
        result.put("focusCategories", List.of("Attacking", "Defensive", "Tactical", "Physical", "General"));

        // Available attributes to focus on (all 36)
        List<String> attrs = new ArrayList<>(PlayerSkillsService.GETTER_MAP.keySet());
        result.put("attributes", attrs);

        // Available roles for this position
        List<String> roles = ROLE_TRAINING_ATTRS.keySet().stream()
                .sorted()
                .collect(Collectors.toList());
        result.put("roles", roles);

        return result;
    }

    /**
     * Fallback: Age-based development curves for players without PlayerSkills.
     */
    private double calculateDevelopmentChange(Human player, Random random) {
        int age = player.getAge();
        int matchesPlayed = player.getSeasonMatchesPlayed();
        double matchBonus = Math.min(matchesPlayed * 0.002, 0.05);

        if (age <= 20) {
            if (random.nextDouble() < 0.12 + matchBonus) {
                return random.nextDouble(0.1, 0.4);
            }
        } else if (age <= 23) {
            if (random.nextDouble() < 0.08 + matchBonus) {
                return random.nextDouble(0.1, 0.3);
            }
        } else if (age <= 26) {
            if (random.nextDouble() < 0.05 + matchBonus) {
                return random.nextDouble(0.05, 0.15);
            }
        } else if (age <= 29) {
            double roll = random.nextDouble();
            if (roll < 0.03 + matchBonus * 0.5) {
                return random.nextDouble(0.02, 0.1);
            } else if (roll > 0.97) {
                return -random.nextDouble(0.02, 0.08);
            }
        } else if (age <= 31) {
            double roll = random.nextDouble();
            if (roll < 0.03) {
                return random.nextDouble(0.01, 0.05);
            } else if (roll > 0.94) {
                return -random.nextDouble(0.05, 0.15);
            }
        } else if (age <= 33) {
            if (random.nextDouble() > 0.92) {
                return -random.nextDouble(0.1, 0.25);
            }
        } else {
            if (random.nextDouble() > 0.88) {
                return -random.nextDouble(0.15, 0.4);
            }
        }
        return 0;
    }

    public void setTrainingFocus(long teamId, String focus) {

        // Map focus to session types
        String sessionType;
        switch (focus) {
            case "Attacking" -> sessionType = "Tactical";
            case "Defensive" -> sessionType = "Tactical";
            case "Fitness" -> sessionType = "Physical";
            case "Tactical" -> sessionType = "Tactical";
            default -> sessionType = "General";
        }

        List<TrainingSchedule> existing = trainingScheduleRepository.findAllByTeamId(teamId);

        if (existing.isEmpty()) {
            // Create a default weekly schedule with the given focus
            for (int day = 0; day < 5; day++) { // Monday to Friday
                TrainingSchedule schedule = new TrainingSchedule();
                schedule.setTeamId(teamId);
                schedule.setDayOfWeek(day);
                schedule.setSessionSlot(0);
                schedule.setSessionType(sessionType);
                schedule.setSessionName(focus);
                schedule.setIntensity(70);
                trainingScheduleRepository.save(schedule);
            }
        } else {
            // Update existing schedule to reflect the new focus
            for (TrainingSchedule schedule : existing) {
                schedule.setSessionType(sessionType);
                schedule.setSessionName(focus);
            }
            trainingScheduleRepository.saveAll(existing);
        }
    }

    public List<TrainingSchedule> getTrainingSchedule(long teamId) {
        return trainingScheduleRepository.findAllByTeamId(teamId);
    }

    public void processMatchDayFitness(long teamId) {

        Random random = new Random();

        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, 1L)
                .stream()
                .filter(h -> !h.isRetired())
                .collect(Collectors.toList());

        for (Human player : players) {
            // Reduce fitness by 10-20 for players who played
            double fitnessLoss = random.nextDouble(10.0, 20.0);
            player.setFitness(Math.max(0.0, player.getFitness() - fitnessLoss));
        }

        humanRepository.saveAll(players);
    }
}
