package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PlayerSkills;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlayerSkillsRepository extends JpaRepository<PlayerSkills, Long> {

    Optional<PlayerSkills> findPlayerSkillsByPlayerId(long playerId);

    java.util.List<PlayerSkills> findAllByPlayerIdIn(java.util.Collection<Long> playerIds);

    /** The natural-fitness values in use, one bulk recovery update per value. */
    @org.springframework.data.jpa.repository.Query("select distinct s.naturalFitness from PlayerSkills s")
    java.util.List<Integer> findDistinctNaturalFitness();
}
