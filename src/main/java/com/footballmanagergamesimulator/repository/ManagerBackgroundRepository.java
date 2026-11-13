package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ManagerBackground;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ManagerBackgroundRepository extends JpaRepository<ManagerBackground, Long> {

    Optional<ManagerBackground> findByManagerId(long managerId);
}
