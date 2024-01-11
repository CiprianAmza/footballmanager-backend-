package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.TeamFacilities;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamFacilitiesRepository extends JpaRepository<TeamFacilities, Long> {

  TeamFacilities findByTeamId(long teamId);
}
