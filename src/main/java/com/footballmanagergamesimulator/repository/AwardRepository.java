package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.Award;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AwardRepository extends JpaRepository<Award, Long> {

    List<Award> findAllBySeasonNumber(int seasonNumber);

    List<Award> findAllByWinnerId(long winnerId);
}
