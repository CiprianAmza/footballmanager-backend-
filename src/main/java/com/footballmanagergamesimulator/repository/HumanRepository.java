package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Human;
import org.springframework.data.domain.Example;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HumanRepository extends JpaRepository<Human, Long> {

    List<Human> findAllByTeamId(long teamId);
    List<Human> findAllByTeamIdAndTypeId(long teamId, long typeId);

}
