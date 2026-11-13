package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;

/**
 * The only way a player's rating changes.
 *
 * <p>A player's rating is never stored as an independent number: it is always the value
 * read back from his attributes ({@link PlayerSkillsService#computeOverallRating}). Anything
 * that wants a player to get better or worse changes attributes, and the rating follows.
 * There is no potential cap: if the attributes grow, the rating grows with them.
 *
 * <p>Static on purpose, like {@code computeOverallRating}, because several callers (season
 * processing, bootstrap, admin tooling) run outside a single Spring service graph.
 */
public final class PlayerRatingSync {

    private PlayerRatingSync() {
    }

    /**
     * Sets the player's rating to the value derived from his attributes, and keeps the
     * mirrored fields ({@code currentAbility}, best-ever rating) consistent with it.
     *
     * @param season the season recorded when the best-ever rating improves
     * @return the derived rating
     */
    public static double refresh(Human player, PlayerSkills skills, int season) {
        double rating = Math.max(1.0, PlayerSkillsService.computeOverallRating(skills));
        player.setRating(rating);
        player.setCurrentAbility((int) rating);
        if (rating > player.getBestEverRating()) {
            player.setBestEverRating(rating);
            player.setSeasonOfBestEverRating(season);
        }
        return rating;
    }

    /**
     * Moves the player's attributes so that his derived rating changes by about
     * {@code delta}, then refreshes the rating from those attributes.
     *
     * <p>The result is as close to {@code current + delta} as the integer attribute scale
     * allows; the returned value is the rating actually reached.
     *
     * @return the derived rating after the attributes moved
     */
    public static double shift(Human player, PlayerSkills skills, double delta, int season) {
        double target = PlayerSkillsService.computeOverallRating(skills) + delta;
        PlayerSkillsService.calibrateOverallRating(skills, target);
        return refresh(player, skills, season);
    }

    /**
     * Δ for a statistics category: grows (or lowers) only the attributes linked to
     * {@code category} so that the derived rating moves by about {@code delta}. Each point
     * goes to one of the category's attributes drawn at random. Past 19 every level needs the
     * same rare roll as training ({@link AttributeCeilingOdds}); a missed roll spends the point.
     *
     * <p>If the category's attributes weigh little or nothing at the player's position, the
     * rating moves less than {@code delta}; the returned value is the rating actually reached.
     *
     * @return the derived rating after the attributes moved
     */
    public static double shift(Human player, PlayerSkills skills, double delta,
                               StatCategory category, java.util.Random rng, int season) {
        double target = PlayerSkillsService.computeOverallRating(skills) + delta;
        PlayerSkillsService.calibrateOverallRating(skills, target, category.attributes(), rng);
        return refresh(player, skills, season);
    }
}
