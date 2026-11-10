package com.footballmanagergamesimulator.chairman.mandate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.Optional;
import java.util.Collection;
import java.util.List;

public interface ChairmanTacticalMandateRepository extends JpaRepository<ChairmanTacticalMandate, Long> {
    @EntityGraph(attributePaths = "slots")
    Optional<ChairmanTacticalMandate> findByTeamId(long teamId);

    @EntityGraph(attributePaths = "slots")
    List<ChairmanTacticalMandate> findAllByTeamIdIn(Collection<Long> teamIds);
}
