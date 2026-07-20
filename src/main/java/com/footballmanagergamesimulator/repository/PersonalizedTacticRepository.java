package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PersonalizedTactic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.Collection;
import java.util.List;

public interface PersonalizedTacticRepository extends JpaRepository<PersonalizedTactic, Long> {

    Optional<PersonalizedTactic> findPersonalizedTacticByTeamId(long teamId);

    List<PersonalizedTactic> findAllByTeamIdIn(Collection<Long> teamIds);

    void deleteAllByTeamId(long teamId);
}
