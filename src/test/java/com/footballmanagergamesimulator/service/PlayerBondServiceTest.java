package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.RelationParty;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@Import({PlayerBondService.class, RelationService.class})
class PlayerBondServiceTest {

    @Autowired private PlayerBondService bonds;
    @Autowired private RelationService relations;
    @Autowired private HumanRepository humans;

    @Test
    @DisplayName("""
            GIVEN a player at club 5 whose manager is human 900, a retired player and a free agent
            WHEN two seasons end
            THEN the active club player likes club 5 and manager 900 at 70 each (+10 a season, from 50);
             the manager does not gain anything towards the player, and the others bond with nobody
            """)
    void eachSeasonPlayedBondsWithClubAndManager() {
        long player = humans.save(human(TypeNames.PLAYER_TYPE, 5L, false)).getId();
        long retired = humans.save(human(TypeNames.PLAYER_TYPE, 5L, true)).getId();
        humans.save(human(TypeNames.PLAYER_TYPE, 0L, false));
        long manager = humans.save(human(TypeNames.MANAGER_TYPE, 5L, false)).getId();

        bonds.recordSeasonPlayed();
        bonds.recordSeasonPlayed();

        assertEquals(70, relations.relation(RelationParty.PLAYER, player, RelationParty.CLUB, 5));
        assertEquals(70, relations.relation(RelationParty.PLAYER, player, RelationParty.MANAGER, manager));
        assertEquals(50, relations.relation(RelationParty.MANAGER, manager, RelationParty.PLAYER, player));
        assertEquals(50, relations.relation(RelationParty.PLAYER, retired, RelationParty.CLUB, 5));
    }

    @Test
    @DisplayName("""
            GIVEN a youth player promoted to club 5
            WHEN his first season there ends
            THEN he likes the club at 80 (+20 youth product, +10 season)
            """)
    void youthProductStartsAttached() {
        long player = humans.save(human(TypeNames.PLAYER_TYPE, 5L, false)).getId();

        bonds.recordYouthProduct(player, 5);
        bonds.recordSeasonPlayed();

        assertEquals(80, relations.relation(RelationParty.PLAYER, player, RelationParty.CLUB, 5));
    }

    private Human human(long type, long teamId, boolean retired) {
        Human h = new Human();
        h.setName("H");
        h.setTypeId(type);
        h.setTeamId(teamId);
        h.setRetired(retired);
        return h;
    }
}
