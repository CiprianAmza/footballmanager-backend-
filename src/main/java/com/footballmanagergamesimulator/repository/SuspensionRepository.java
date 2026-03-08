package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Suspension;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SuspensionRepository extends JpaRepository<Suspension, Long> {

    List<Suspension> findAllByPlayerIdAndActive(long playerId, boolean active);

    List<Suspension> findAllByTeamIdAndCompetitionIdAndActive(long teamId, long competitionId, boolean active);
}
