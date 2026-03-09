package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Scout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScoutRepository extends JpaRepository<Scout, Long> {

    List<Scout> findAllByTeamId(long teamId);

    List<Scout> findAllByTeamIdIsNull();

    List<Scout> findAllByTeamIdAndContractEndSeasonLessThanEqual(long teamId, int season);
}
