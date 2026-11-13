package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Agent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRepository extends JpaRepository<Agent, Long> {

    List<Agent> findAllByNationId(long nationId);
}
