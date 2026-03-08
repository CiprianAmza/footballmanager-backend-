package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PlayerInteraction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlayerInteractionRepository extends JpaRepository<PlayerInteraction, Long> {

    List<PlayerInteraction> findAllByTeamIdAndSeasonNumberAndResolvedFalse(long teamId, int seasonNumber);

    List<PlayerInteraction> findAllByTeamIdAndSeasonNumber(long teamId, int seasonNumber);
}
