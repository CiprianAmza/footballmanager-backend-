package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.matchplan.MatchAnimationRecipe;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MatchAnimationRecipeRepository extends JpaRepository<MatchAnimationRecipe, Long> {
    Optional<MatchAnimationRecipe> findByFixtureKeyAndSlotIndex(String fixtureKey, int slotIndex);

    List<MatchAnimationRecipe> findByFixtureKeyOrderByMinuteAscSlotIndexAsc(String fixtureKey);

    void deleteByFixtureKey(String fixtureKey);
}
