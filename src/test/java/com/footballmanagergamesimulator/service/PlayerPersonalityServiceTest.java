package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerPersonalityServiceTest {

    private final PlayerPersonalityService service = new PlayerPersonalityService();

    @Test
    void profileIsStableAndEveryHiddenAttributeUsesTheOneToTwentyScale() {
        Human player = player(3386L);

        PlayerPersonalityService.Profile first = service.profile(player);
        PlayerPersonalityService.Profile second = service.profile(player);

        assertThat(second).isEqualTo(first);
        assertThat(first.hobbies()).hasSize(2).doesNotHaveDuplicates();
        for (int value : new int[]{first.professionalism(), first.ambition(), first.loyalty(),
                first.adaptability(), first.pressureHandling(), first.consistency(), first.leadership()}) {
            assertThat(value).isBetween(1, 20);
        }
    }

    @Test
    void trainingModifierRemainsBoundedForOldAndNewPlayers() {
        assertThat(service.trainingDevelopmentFactor(player(1L), "Physical")).isBetween(.88, 1.14);
        assertThat(service.trainingDevelopmentFactor(player(999_999L), "Tactical")).isBetween(.88, 1.14);
    }

    @Test
    void protectedOneClubPlayerAlwaysGetsLegacyCareerGoal() {
        Human player = player(12L);
        player.setWillNeverLeave(true);

        assertThat(service.profile(player).careerGoal()).isEqualTo("Become a one-club icon");
    }

    private Human player(long id) {
        Human player = new Human();
        player.setId(id);
        player.setName("Test Player");
        player.setAge(24);
        return player;
    }
}
