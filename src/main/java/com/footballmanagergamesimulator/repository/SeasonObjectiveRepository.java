package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.SeasonObjective;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SeasonObjectiveRepository extends JpaRepository<SeasonObjective, Long> {

    List<SeasonObjective> findAllByTeamIdAndSeasonNumber(long teamId, int seasonNumber);

    List<SeasonObjective> findAllByTeamId(long teamId);
}
