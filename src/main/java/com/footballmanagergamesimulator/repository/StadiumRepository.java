package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Stadium;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.Collection;
import java.util.List;

public interface StadiumRepository extends JpaRepository<Stadium, Long> {

    Optional<Stadium> findByTeamId(long teamId);

    List<Stadium> findAllByTeamIdIn(Collection<Long> teamIds);
}
