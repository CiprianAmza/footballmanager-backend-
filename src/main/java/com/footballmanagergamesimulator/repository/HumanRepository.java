package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Human;
import org.springframework.data.domain.Example;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

public interface HumanRepository extends JpaRepository<Human, Long> {

    List<Human> findAllByTeamId(long teamId);
    List<Human> findAllByTeamIdAndTypeId(long teamId, long typeId);
    List<Human> findAllByTypeId(long typeId);
    List<Human> findAllByTeamIdAndTypeIdAndContractEndSeasonLessThanEqual(long teamId, long typeId, int season);

}
