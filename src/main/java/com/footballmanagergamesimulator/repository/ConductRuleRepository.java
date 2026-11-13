package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ConductRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConductRuleRepository extends JpaRepository<ConductRule, Long> {

    List<ConductRule> findAllByTeamId(long teamId);
}
