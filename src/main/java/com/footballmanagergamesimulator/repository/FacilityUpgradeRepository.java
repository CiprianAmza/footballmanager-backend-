package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.FacilityUpgrade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FacilityUpgradeRepository extends JpaRepository<FacilityUpgrade, Long> {

    List<FacilityUpgrade> findAllByTeamIdAndCompletedFalse(long teamId);

    List<FacilityUpgrade> findAllByTeamId(long teamId);
}
