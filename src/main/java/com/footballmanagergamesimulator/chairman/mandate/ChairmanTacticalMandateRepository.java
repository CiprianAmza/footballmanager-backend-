package com.footballmanagergamesimulator.chairman.mandate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChairmanTacticalMandateRepository extends JpaRepository<ChairmanTacticalMandate, Long> {
    Optional<ChairmanTacticalMandate> findByTeamId(long teamId);
}
