package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ClubLegend;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClubLegendRepository extends JpaRepository<ClubLegend, Long> {

    List<ClubLegend> findAllByTeamIdOrderByInductedSeasonDescInductedAtDesc(long teamId);

    Optional<ClubLegend> findByTeamIdAndPlayerId(long teamId, long playerId);

    void deleteByTeamIdAndPlayerId(long teamId, long playerId);
}
