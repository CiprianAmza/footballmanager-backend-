package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Stable, save-compatible off-pitch profile for a player.
 *
 * <p>The profile is derived from the immutable player id, so old careers gain
 * the new data without a destructive Human-table migration and the same player
 * keeps the same character after save/load. These values are hidden traits:
 * they are useful management signals, not medical diagnoses or private facts.
 */
@Service
public class PlayerPersonalityService {

    private static final List<String> LIFESTYLES = List.of(
            "Family-oriented", "Private and home-focused", "Social and outgoing",
            "Travel enthusiast", "Community-minded", "Fashion-conscious",
            "Quiet and studious", "Competitive in everything");
    private static final List<String> HOBBIES = List.of(
            "Family time", "Gaming", "Music", "Travel", "Fashion",
            "Cars", "Charity work", "Cooking", "Films", "Padel");
    private static final List<String> TRAINING_STYLES = List.of(
            "Intense", "Technical", "Tactical", "Individual", "Balanced", "Recovery-led");
    private static final List<String> COACHING_APPROACHES = List.of(
            "Direct feedback", "Collaborative coaching", "Clear private targets",
            "Frequent encouragement", "Detailed video feedback");
    private static final List<String> MEDIA_STYLES = List.of(
            "Reserved", "Calm", "Confident", "Diplomatic", "Outspoken");
    private static final List<String> CAREER_GOALS = List.of(
            "Become a club leader", "Win major trophies", "Play abroad",
            "Reach the national team", "Become a one-club icon", "Join a bigger club");

    public Profile profile(Human player) {
        int professionalism = score(player.getId(), 11);
        int ambition = score(player.getId(), 23);
        int loyalty = score(player.getId(), 37);
        int adaptability = score(player.getId(), 41);
        int pressure = score(player.getId(), 53);
        int consistency = score(player.getId(), 67);
        int leadership = score(player.getId(), 79);

        String lifestyle = pick(LIFESTYLES, player.getId(), 83);
        String trainingStyle = pick(TRAINING_STYLES, player.getId(), 97);
        String coachingApproach = pick(COACHING_APPROACHES, player.getId(), 101);
        String mediaStyle = pick(MEDIA_STYLES, player.getId(), 113);
        String careerGoal = careerGoal(player, ambition, loyalty);
        List<String> hobbies = twoDistinct(HOBBIES, player.getId(), 127, 139);

        return new Profile(
                personalityLabel(professionalism, ambition, loyalty, pressure, leadership),
                professionalism, ambition, loyalty, adaptability, pressure, consistency, leadership,
                lifestyle, hobbies, trainingStyle, coachingApproach, mediaStyle, careerGoal,
                personalSituation(loyalty, adaptability));
    }

    /** 0.88-1.14 modifier used by long-term attribute development. */
    public double trainingDevelopmentFactor(Human player, String teamFocus) {
        Profile profile = profile(player);
        double factor = 0.86 + profile.professionalism() * 0.014;
        if (focusMatches(profile.preferredTrainingStyle(), teamFocus)) factor += 0.04;
        return Math.max(0.88, Math.min(1.14, factor));
    }

    private boolean focusMatches(String preference, String focus) {
        if (focus == null) return false;
        return ("Technical".equals(preference) && "Attacking".equalsIgnoreCase(focus))
                || ("Tactical".equals(preference) && "Tactical".equalsIgnoreCase(focus))
                || ("Intense".equals(preference) && "Physical".equalsIgnoreCase(focus))
                || "Balanced".equals(preference);
    }

    private String careerGoal(Human player, int ambition, int loyalty) {
        if (player.isWillNeverLeave() || loyalty >= 18) return "Become a one-club icon";
        if (player.getAge() <= 22 && ambition >= 14) return "Reach the national team";
        if (ambition >= 17) return "Join a bigger club";
        return pick(CAREER_GOALS, player.getId(), 149);
    }

    private String personalityLabel(int professional, int ambition, int loyalty, int pressure, int leadership) {
        if (professional >= 17 && ambition >= 15) return "Driven professional";
        if (loyalty >= 17 && leadership >= 13) return "Loyal leader";
        if (pressure >= 17) return "Big-match character";
        if (ambition >= 17) return "Highly ambitious";
        if (professional <= 6) return "Needs structure";
        if (leadership >= 17) return "Influential presence";
        return "Balanced character";
    }

    private String personalSituation(int loyalty, int adaptability) {
        if (adaptability <= 6) return "Values routine and familiar surroundings";
        if (loyalty >= 16) return "Feels settled in the area";
        if (adaptability >= 16) return "Comfortable with relocation and new cultures";
        return "Settled, but open to a new environment";
    }

    private List<String> twoDistinct(List<String> values, long playerId, int saltA, int saltB) {
        List<String> result = new ArrayList<>();
        result.add(pick(values, playerId, saltA));
        String second = pick(values, playerId, saltB);
        if (result.get(0).equals(second)) second = values.get((values.indexOf(second) + 1) % values.size());
        result.add(second);
        return List.copyOf(result);
    }

    private int score(long id, int salt) {
        long mixed = mix(id + salt * 0x9E3779B97F4A7C15L);
        return (int) Math.floorMod(mixed, 20) + 1;
    }

    private String pick(List<String> values, long id, int salt) {
        return values.get((int) Math.floorMod(mix(id + salt * 31L), values.size()));
    }

    private long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ (value >>> 33);
    }

    public record Profile(
            String personalityLabel,
            int professionalism,
            int ambition,
            int loyalty,
            int adaptability,
            int pressureHandling,
            int consistency,
            int leadership,
            String lifestyle,
            List<String> hobbies,
            String preferredTrainingStyle,
            String preferredCoachingApproach,
            String mediaStyle,
            String careerGoal,
            String personalSituation) {
    }
}
