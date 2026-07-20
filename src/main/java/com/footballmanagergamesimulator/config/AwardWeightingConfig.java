package com.footballmanagergamesimulator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;

/**
 * Designer-editable weights shared by the live award leaderboards and the
 * end-of-season award ceremony. Rank-multiplier keys are inclusive upper
 * bounds: {@code 3: 4.0} means positions 1-3 receive a x4 multiplier.
 */
@Data
@Component
@ConfigurationProperties(prefix = "awards.weighting")
public class AwardWeightingConfig {

    private LeagueStrength leagueStrength = new LeagueStrength();
    private GoldenBoot goldenBoot = new GoldenBoot();

    @Data
    public static class LeagueStrength {
        private int topPlayersPerTeam = 11;
        private Map<Integer, Double> rankMultipliers = new TreeMap<>(Map.of(
                3, 4.0,
                5, 3.0,
                7, 2.0));
        private double defaultMultiplier = 1.0;

        public double multiplierForRank(int rank) {
            return rankMultipliers.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .filter(entry -> rank <= entry.getKey())
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(defaultMultiplier);
        }
    }

    @Data
    public static class GoldenBoot {
        private double goalWeight = 2.5;
        private double assistWeight = 1.0;
    }
}
