package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Injury;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InjuryRepository extends JpaRepository<Injury, Long> {

    List<Injury> findAllByTeamId(long teamId);
    List<Injury> findAllByTeamIdAndDaysRemainingGreaterThan(long teamId, int days);
    List<Injury> findAllByPlayerId(long playerId);
    Optional<Injury> findByPlayerIdAndDaysRemainingGreaterThan(long playerId, int days);
    List<Injury> findAllByDaysRemainingGreaterThan(int days);

}
