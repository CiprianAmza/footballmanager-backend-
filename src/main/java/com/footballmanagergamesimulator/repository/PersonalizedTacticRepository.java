package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PersonalizedTactic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PersonalizedTacticRepository extends JpaRepository<PersonalizedTactic, Long> {

    Optional<PersonalizedTactic> findPersonalizedTacticByTeamId(long teamId);

    void deleteAllByTeamId(long teamId);
}
