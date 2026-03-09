package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Injury;
import com.footballmanagergamesimulator.model.TrainingSchedule;
import com.footballmanagergamesimulator.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
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

        List<Human> modifiedPlayers = new ArrayList<>();

        for (Human player : players) {
            // Skip injured players
            if (injuredPlayerIds.contains(player.getId())) {
                continue;
            }

            // Increase fitness by random(0.5, 1.5), cap at 100
            double fitnessGain = random.nextDouble(0.5, 1.5);
            player.setFitness(Math.min(100.0, player.getFitness() + fitnessGain));

            // Age-based development curves
            double ratingChange = calculateDevelopmentChange(player, random);
            if (ratingChange != 0) {
                // Cap growth at potentialAbility (if set)
                double newRating = player.getRating() + ratingChange;
                if (ratingChange > 0 && player.getPotentialAbility() > 0) {
                    newRating = Math.min(newRating, player.getPotentialAbility());
                }
                newRating = Math.max(newRating, 1.0); // never go below 1
                player.setRating(newRating);

                if (newRating > player.getBestEverRating()) {
                    player.setBestEverRating(newRating);
                    player.setSeasonOfBestEverRating(season);
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
    }

    /**
     * Age-based development curves:
     * - U21: High growth potential, training is very effective
     * - 21-24: Good growth, approaching peak
     * - 25-29: Peak years, minimal change (slight growth if playing regularly)
     * - 30-31: Start of decline, small losses
     * - 32-33: Noticeable decline
     * - 34+: Significant decline
     *
     * Match minutes boost: players who play regularly develop faster
     */
    private double calculateDevelopmentChange(Human player, Random random) {
        int age = player.getAge();
        int matchesPlayed = player.getSeasonMatchesPlayed();

        // Match activity bonus: players who play regularly improve faster
        double matchBonus = Math.min(matchesPlayed * 0.002, 0.05); // up to +5% per training

        if (age <= 20) {
            // Young talent: 12% chance of +0.1 to +0.4 growth per session
            if (random.nextDouble() < 0.12 + matchBonus) {
                return random.nextDouble(0.1, 0.4);
            }
        } else if (age <= 23) {
            // Developing: 8% chance of +0.1 to +0.3
            if (random.nextDouble() < 0.08 + matchBonus) {
                return random.nextDouble(0.1, 0.3);
            }
        } else if (age <= 26) {
            // Approaching peak: 5% chance of +0.05 to +0.15
            if (random.nextDouble() < 0.05 + matchBonus) {
                return random.nextDouble(0.05, 0.15);
            }
        } else if (age <= 29) {
            // Peak years: 3% chance of small gain, 2% small loss
            double roll = random.nextDouble();
            if (roll < 0.03 + matchBonus * 0.5) {
                return random.nextDouble(0.02, 0.1);
            } else if (roll > 0.97) {
                return -random.nextDouble(0.02, 0.08);
            }
        } else if (age <= 31) {
            // Early decline: 3% gain, 6% loss
            double roll = random.nextDouble();
            if (roll < 0.03) {
                return random.nextDouble(0.01, 0.05);
            } else if (roll > 0.94) {
                return -random.nextDouble(0.05, 0.15);
            }
        } else if (age <= 33) {
            // Decline: 8% chance of -0.1 to -0.25
            if (random.nextDouble() > 0.92) {
                return -random.nextDouble(0.1, 0.25);
            }
        } else {
            // Significant decline: 12% chance of -0.15 to -0.4
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
