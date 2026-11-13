package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.FrontCoach;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FrontCoachRepository extends JpaRepository<FrontCoach, Long> {

    Optional<FrontCoach> findByTeamId(long teamId);
}
