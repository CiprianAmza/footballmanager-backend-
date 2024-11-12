package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Round;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoundRepository extends JpaRepository<Round, Long> {
}
