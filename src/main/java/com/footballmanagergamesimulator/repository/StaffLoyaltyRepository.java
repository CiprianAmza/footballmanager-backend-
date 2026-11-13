package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.StaffLoyalty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StaffLoyaltyRepository extends JpaRepository<StaffLoyalty, Long> {

    Optional<StaffLoyalty> findByStaffId(long staffId);

    List<StaffLoyalty> findAllByTeamId(long teamId);

    void deleteAllByTeamId(long teamId);

    void deleteByStaffId(long staffId);
}
