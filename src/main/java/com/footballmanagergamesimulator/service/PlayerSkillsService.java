package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.PlayerSkills;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

@Service
public class PlayerSkillsService {

    // Getter and setter maps for all attributes (returns int, not long)
    public static final LinkedHashMap<String, Function<PlayerSkills, Integer>> GETTER_MAP = new LinkedHashMap<>();
    public static final LinkedHashMap<String, BiConsumer<PlayerSkills, Integer>> SETTER_MAP = new LinkedHashMap<>();

    // Category lists for grouping in UI
    public static final List<String> TECHNICAL = List.of(
            "Corners", "Crossing", "Dribbling", "Finishing", "First Touch", "Free Kick",
            "Heading", "Long Shots", "Long Throws", "Marking", "Passing", "Penalty Taking",
            "Tackling", "Technique");

    public static final List<String> MENTAL = List.of(
            "Aggression", "Anticipation", "Bravery", "Composure", "Concentration",
            "Decisions", "Determination", "Flair", "Leadership", "Off The Ball",
            "Positioning", "Teamwork", "Vision", "Work Rate");

    public static final List<String> PHYSICAL = List.of(
            "Acceleration", "Agility", "Balance", "Jumping Reach", "Natural Fitness",
            "Pace", "Stamina", "Strength");

    public static final List<String> GOALKEEPER = List.of(
            "Handling", "Reflexes", "One On Ones", "Command Of Area", "Kicking", "Throwing");

    static {
        // Technical
        GETTER_MAP.put("Corners", PlayerSkills::getCorners);
        GETTER_MAP.put("Crossing", PlayerSkills::getCrossing);
        GETTER_MAP.put("Dribbling", PlayerSkills::getDribbling);
        GETTER_MAP.put("Finishing", PlayerSkills::getFinishing);
        GETTER_MAP.put("First Touch", PlayerSkills::getFirstTouch);
        GETTER_MAP.put("Free Kick", PlayerSkills::getFreeKick);
        GETTER_MAP.put("Heading", PlayerSkills::getHeading);
        GETTER_MAP.put("Long Shots", PlayerSkills::getLongShots);
        GETTER_MAP.put("Long Throws", PlayerSkills::getLongThrows);
        GETTER_MAP.put("Marking", PlayerSkills::getMarking);
        GETTER_MAP.put("Passing", PlayerSkills::getPassing);
        GETTER_MAP.put("Penalty Taking", PlayerSkills::getPenaltyTaking);
        GETTER_MAP.put("Tackling", PlayerSkills::getTackling);
        GETTER_MAP.put("Technique", PlayerSkills::getTechnique);

        // Mental
        GETTER_MAP.put("Aggression", PlayerSkills::getAggression);
        GETTER_MAP.put("Anticipation", PlayerSkills::getAnticipation);
        GETTER_MAP.put("Bravery", PlayerSkills::getBravery);
        GETTER_MAP.put("Composure", PlayerSkills::getComposure);
        GETTER_MAP.put("Concentration", PlayerSkills::getConcentration);
        GETTER_MAP.put("Decisions", PlayerSkills::getDecisions);
        GETTER_MAP.put("Determination", PlayerSkills::getDetermination);
        GETTER_MAP.put("Flair", PlayerSkills::getFlair);
        GETTER_MAP.put("Leadership", PlayerSkills::getLeadership);
        GETTER_MAP.put("Off The Ball", PlayerSkills::getOffTheBall);
        GETTER_MAP.put("Positioning", PlayerSkills::getPositioning);
        GETTER_MAP.put("Teamwork", PlayerSkills::getTeamwork);
        GETTER_MAP.put("Vision", PlayerSkills::getVision);
        GETTER_MAP.put("Work Rate", PlayerSkills::getWorkRate);

        // Physical
        GETTER_MAP.put("Acceleration", PlayerSkills::getAcceleration);
        GETTER_MAP.put("Agility", PlayerSkills::getAgility);
        GETTER_MAP.put("Balance", PlayerSkills::getBalance);
        GETTER_MAP.put("Jumping Reach", PlayerSkills::getJumpingReach);
        GETTER_MAP.put("Natural Fitness", PlayerSkills::getNaturalFitness);
        GETTER_MAP.put("Pace", PlayerSkills::getPace);
        GETTER_MAP.put("Stamina", PlayerSkills::getStamina);
        GETTER_MAP.put("Strength", PlayerSkills::getStrength);

        // Goalkeeper
        GETTER_MAP.put("Handling", PlayerSkills::getHandling);
        GETTER_MAP.put("Reflexes", PlayerSkills::getReflexes);
        GETTER_MAP.put("One On Ones", PlayerSkills::getOneOnOnes);
        GETTER_MAP.put("Command Of Area", PlayerSkills::getCommandOfArea);
        GETTER_MAP.put("Kicking", PlayerSkills::getKicking);
        GETTER_MAP.put("Throwing", PlayerSkills::getThrowing);

        // Setters - Technical
        SETTER_MAP.put("Corners", PlayerSkills::setCorners);
        SETTER_MAP.put("Crossing", PlayerSkills::setCrossing);
        SETTER_MAP.put("Dribbling", PlayerSkills::setDribbling);
        SETTER_MAP.put("Finishing", PlayerSkills::setFinishing);
        SETTER_MAP.put("First Touch", PlayerSkills::setFirstTouch);
        SETTER_MAP.put("Free Kick", PlayerSkills::setFreeKick);
        SETTER_MAP.put("Heading", PlayerSkills::setHeading);
        SETTER_MAP.put("Long Shots", PlayerSkills::setLongShots);
        SETTER_MAP.put("Long Throws", PlayerSkills::setLongThrows);
        SETTER_MAP.put("Marking", PlayerSkills::setMarking);
        SETTER_MAP.put("Passing", PlayerSkills::setPassing);
        SETTER_MAP.put("Penalty Taking", PlayerSkills::setPenaltyTaking);
        SETTER_MAP.put("Tackling", PlayerSkills::setTackling);
        SETTER_MAP.put("Technique", PlayerSkills::setTechnique);

        // Setters - Mental
        SETTER_MAP.put("Aggression", PlayerSkills::setAggression);
        SETTER_MAP.put("Anticipation", PlayerSkills::setAnticipation);
        SETTER_MAP.put("Bravery", PlayerSkills::setBravery);
        SETTER_MAP.put("Composure", PlayerSkills::setComposure);
        SETTER_MAP.put("Concentration", PlayerSkills::setConcentration);
        SETTER_MAP.put("Decisions", PlayerSkills::setDecisions);
        SETTER_MAP.put("Determination", PlayerSkills::setDetermination);
        SETTER_MAP.put("Flair", PlayerSkills::setFlair);
        SETTER_MAP.put("Leadership", PlayerSkills::setLeadership);
        SETTER_MAP.put("Off The Ball", PlayerSkills::setOffTheBall);
        SETTER_MAP.put("Positioning", PlayerSkills::setPositioning);
        SETTER_MAP.put("Teamwork", PlayerSkills::setTeamwork);
        SETTER_MAP.put("Vision", PlayerSkills::setVision);
        SETTER_MAP.put("Work Rate", PlayerSkills::setWorkRate);

        // Setters - Physical
        SETTER_MAP.put("Acceleration", PlayerSkills::setAcceleration);
        SETTER_MAP.put("Agility", PlayerSkills::setAgility);
        SETTER_MAP.put("Balance", PlayerSkills::setBalance);
        SETTER_MAP.put("Jumping Reach", PlayerSkills::setJumpingReach);
        SETTER_MAP.put("Natural Fitness", PlayerSkills::setNaturalFitness);
        SETTER_MAP.put("Pace", PlayerSkills::setPace);
        SETTER_MAP.put("Stamina", PlayerSkills::setStamina);
        SETTER_MAP.put("Strength", PlayerSkills::setStrength);

        // Setters - Goalkeeper
        SETTER_MAP.put("Handling", PlayerSkills::setHandling);
        SETTER_MAP.put("Reflexes", PlayerSkills::setReflexes);
        SETTER_MAP.put("One On Ones", PlayerSkills::setOneOnOnes);
        SETTER_MAP.put("Command Of Area", PlayerSkills::setCommandOfArea);
        SETTER_MAP.put("Kicking", PlayerSkills::setKicking);
        SETTER_MAP.put("Throwing", PlayerSkills::setThrowing);
    }

    /**
     * Compute overall rating (1-100 scale) from individual attributes based on position.
     * Each position weights different attributes to produce a meaningful overall score.
     */
    public static double computeOverallRating(PlayerSkills skills) {
        String pos = skills.getPosition();
        if (pos == null) pos = "MC";

        double weighted;

        switch (pos) {
            case "GK" -> weighted = gkRating(skills);
            case "DC" -> weighted = dcRating(skills);
            case "DL", "DR" -> weighted = fbRating(skills);
            case "MC" -> weighted = mcRating(skills);
            case "ML", "MR" -> weighted = wideRating(skills);
            case "ST" -> weighted = stRating(skills);
            default -> weighted = mcRating(skills);
        }

        // Map from 1-20 attribute scale to roughly 30-100 rating scale
        // weighted is already a 1-20 scale weighted average
        return Math.max(1, Math.min(100, weighted * 5));
    }

    private static double gkRating(PlayerSkills s) {
        return s.getReflexes() * 0.18
                + s.getHandling() * 0.16
                + s.getPositioning() * 0.12
                + s.getOneOnOnes() * 0.10
                + s.getCommandOfArea() * 0.10
                + s.getKicking() * 0.06
                + s.getThrowing() * 0.04
                + s.getConcentration() * 0.06
                + s.getAnticipation() * 0.06
                + s.getComposure() * 0.04
                + s.getAgility() * 0.04
                + s.getDecisions() * 0.04;
    }

    private static double dcRating(PlayerSkills s) {
        return s.getTackling() * 0.14
                + s.getMarking() * 0.12
                + s.getPositioning() * 0.12
                + s.getHeading() * 0.08
                + s.getStrength() * 0.08
                + s.getAnticipation() * 0.08
                + s.getConcentration() * 0.08
                + s.getBravery() * 0.06
                + s.getComposure() * 0.05
                + s.getPace() * 0.05
                + s.getJumpingReach() * 0.05
                + s.getPassing() * 0.04
                + s.getDecisions() * 0.05;
    }

    private static double fbRating(PlayerSkills s) {
        return s.getTackling() * 0.10
                + s.getCrossing() * 0.10
                + s.getPace() * 0.10
                + s.getStamina() * 0.08
                + s.getPositioning() * 0.08
                + s.getMarking() * 0.07
                + s.getAnticipation() * 0.06
                + s.getWorkRate() * 0.06
                + s.getDribbling() * 0.05
                + s.getPassing() * 0.06
                + s.getAcceleration() * 0.06
                + s.getConcentration() * 0.05
                + s.getTeamwork() * 0.05
                + s.getDecisions() * 0.04
                + s.getFirstTouch() * 0.04;
    }

    private static double mcRating(PlayerSkills s) {
        return s.getPassing() * 0.14
                + s.getVision() * 0.10
                + s.getDecisions() * 0.08
                + s.getTeamwork() * 0.07
                + s.getFirstTouch() * 0.07
                + s.getTechnique() * 0.07
                + s.getStamina() * 0.06
                + s.getWorkRate() * 0.06
                + s.getPositioning() * 0.06
                + s.getTackling() * 0.05
                + s.getConcentration() * 0.05
                + s.getComposure() * 0.05
                + s.getAnticipation() * 0.05
                + s.getDribbling() * 0.04
                + s.getLongShots() * 0.05;
    }

    private static double wideRating(PlayerSkills s) {
        return s.getCrossing() * 0.12
                + s.getDribbling() * 0.12
                + s.getPace() * 0.10
                + s.getAcceleration() * 0.08
                + s.getTechnique() * 0.07
                + s.getFirstTouch() * 0.06
                + s.getOffTheBall() * 0.06
                + s.getFlair() * 0.05
                + s.getPassing() * 0.06
                + s.getStamina() * 0.05
                + s.getWorkRate() * 0.05
                + s.getAgility() * 0.05
                + s.getFinishing() * 0.05
                + s.getDecisions() * 0.04
                + s.getAnticipation() * 0.04;
    }

    private static double stRating(PlayerSkills s) {
        return s.getFinishing() * 0.16
                + s.getOffTheBall() * 0.10
                + s.getComposure() * 0.09
                + s.getFirstTouch() * 0.07
                + s.getDribbling() * 0.07
                + s.getHeading() * 0.06
                + s.getPace() * 0.06
                + s.getAcceleration() * 0.05
                + s.getAnticipation() * 0.06
                + s.getTechnique() * 0.06
                + s.getStrength() * 0.05
                + s.getDecisions() * 0.05
                + s.getFlair() * 0.04
                + s.getBalance() * 0.04
                + s.getAgility() * 0.04;
    }
}
