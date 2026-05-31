package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.CoachPermissions;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CoachPermissionsRepository extends JpaRepository<CoachPermissions, Long> {
    Optional<CoachPermissions> findByTeamId(long teamId);
}
