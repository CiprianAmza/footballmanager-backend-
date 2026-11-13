package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.RelationParty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@Import(RelationService.class)
class RelationServiceTest {

    @Autowired private RelationService relations;

    @Test
    @DisplayName("""
            GIVEN player 7 and manager 3 who never met
            WHEN the player comes to like the manager (+30)
            THEN the player's view of the manager is 80 while the manager's view of the player stays neutral (50)
            """)
    void relationsAreDirectional() {
        relations.change(RelationParty.PLAYER, 7, RelationParty.MANAGER, 3, 30);

        assertEquals(80, relations.relation(RelationParty.PLAYER, 7, RelationParty.MANAGER, 3));
        assertEquals(50, relations.relation(RelationParty.MANAGER, 3, RelationParty.PLAYER, 7));
    }

    @Test
    @DisplayName("""
            GIVEN player 7 who likes club 3 (+40)
            WHEN relations with the same ids but other kinds of entities are read
            THEN only PLAYER 7 → CLUB 3 moved; PLAYER 7 → MANAGER 3 and AGENT 7 → CLUB 3 are still neutral
            """)
    void kindsOfEntitiesAreKeptApart() {
        relations.change(RelationParty.PLAYER, 7, RelationParty.CLUB, 3, 40);

        assertEquals(90, relations.relation(RelationParty.PLAYER, 7, RelationParty.CLUB, 3));
        assertEquals(50, relations.relation(RelationParty.PLAYER, 7, RelationParty.MANAGER, 3));
        assertEquals(50, relations.relation(RelationParty.AGENT, 7, RelationParty.CLUB, 3));
    }

    @Test
    @DisplayName("""
            GIVEN a neutral relation
            WHEN it is pushed far up and then far down
            THEN it stays within 0–100
            """)
    void relationStaysWithinBounds() {
        assertEquals(100, relations.change(RelationParty.MANAGER, 1, RelationParty.DIRECTOR, 2, 80));
        assertEquals(0, relations.change(RelationParty.MANAGER, 1, RelationParty.DIRECTOR, 2, -150));
    }
}
