package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.ClubCoefficient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClubCoefficientRepository extends JpaRepository<ClubCoefficient, Long> {

    List<ClubCoefficient> findAllByTeamId(long teamId);
    Optional<ClubCoefficient> findByTeamIdAndSeasonNumber(long teamId, int seasonNumber);
    List<ClubCoefficient> findAllBySeasonNumber(int seasonNumber);
}
