package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PlayerSkills;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlayerSkillsRepository extends JpaRepository<PlayerSkills, Long> {

    Optional<PlayerSkills> findPlayerSkillsByPlayerId(long playerId);

}
