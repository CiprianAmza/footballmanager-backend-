package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.SquadObjective;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SquadObjectiveRepository extends JpaRepository<SquadObjective, Long> {

    List<SquadObjective> findAllByTeamIdAndSeason(long teamId, int season);

    List<SquadObjective> findAllBySeason(int season);
}
