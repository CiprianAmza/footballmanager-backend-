package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.TeamFacilities;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface TeamFacilitiesRepository extends JpaRepository<TeamFacilities, Long> {

  TeamFacilities findByTeamId(long teamId);
  List<TeamFacilities> findAllByTeamIdIn(Collection<Long> teamIds);
}
