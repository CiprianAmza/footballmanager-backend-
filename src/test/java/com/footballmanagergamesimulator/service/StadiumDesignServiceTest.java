package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Stadium;
import com.footballmanagergamesimulator.model.Team;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StadiumDesignServiceTest {

    private final StadiumDesignService service = new StadiumDesignService();

    @Test
    void everyClubGetsAStableIdentityAndDifferentSeedsProduceDifferentArchitecture() {
        Team first = team(10, "Sherlock FC", 7_000, "blue", "white");
        Team second = team(11, "Yu Gi Oh", 7_000, "red", "black");
        Stadium stadium = stadium(66_000);

        var firstDesign = service.design(first, stadium);
        var repeated = service.design(first, stadium);
        var secondDesign = service.design(second, stadium);

        assertThat(firstDesign).isEqualTo(repeated);
        assertThat(firstDesign.seed()).isNotEqualTo(secondDesign.seed());
        assertThat(firstDesign.primaryColour()).isEqualTo("#2867b2");
        assertThat(secondDesign.primaryColour()).isEqualTo("#d64045");
    }

    @Test
    void capacityAndVisualPrestigeGrowWithClubReputation() {
        assertThat(StadiumDesignService.initialCapacityForReputation(9_500))
                .isGreaterThan(StadiumDesignService.initialCapacityForReputation(5_000));

        Team elite = team(1, "Elite", 9_500, "gold", "black");
        Team local = team(2, "Local", 4_000, "green", "white");
        var eliteDesign = service.design(elite, stadium(86_000));
        var localDesign = service.design(local, stadium(18_000));

        assertThat(eliteDesign.tiers()).isEqualTo(3);
        assertThat(eliteDesign.roof()).isEqualTo("FULL");
        assertThat(eliteDesign.prestige()).isGreaterThan(localDesign.prestige());
        assertThat(localDesign.tiers()).isEqualTo(1);
    }

    @Test
    void anExpansionChangesTheVisibleTierCountWithoutChangingTheClubSeed() {
        Team club = team(7, "Growers", 6_000, "cyan", "navy");
        Stadium stadium = stadium(30_000);
        var before = service.design(club, stadium);
        stadium.setExpansionLevel(2);
        var after = service.design(club, stadium);

        assertThat(after.seed()).isEqualTo(before.seed());
        assertThat(before.tiers()).isEqualTo(1);
        assertThat(after.tiers()).isEqualTo(2);
    }

    private Team team(long id, String name, int reputation, String primary, String secondary) {
        Team team = new Team(); team.setId(id); team.setName(name); team.setReputation(reputation);
        team.setColor1(primary); team.setColor2(secondary); team.setBorder("gold"); return team;
    }

    private Stadium stadium(int capacity) {
        Stadium stadium = new Stadium(); stadium.setCapacity(capacity); stadium.setVipBoxesLevel(2); return stadium;
    }
}
