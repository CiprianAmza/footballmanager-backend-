package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Human;
import org.springframework.data.domain.Example;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

public interface HumanRepository extends JpaRepository<Human, Long> {

    List<Human> findAllByTeamId(long teamId);
    List<Human> findAllByTeamIdAndTypeId(long teamId, long typeId);
    List<Human> findAllByTypeId(long typeId);
    List<Human> findAllByTeamIdAndTypeIdAndContractEndSeasonLessThanEqual(long teamId, long typeId, int season);
    List<Human> findAllByPreContractTeamId(long teamId);

    // Batch IN-clause lookup — used to pre-load all teams' players in one query
    // at the start of simulateRound to avoid N+1 across the per-match helpers.
    List<Human> findAllByTeamIdInAndTypeId(Collection<Long> teamIds, long typeId);

}
