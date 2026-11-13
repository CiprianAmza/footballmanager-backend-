package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PublicPromise;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PublicPromiseRepository extends JpaRepository<PublicPromise, Long> {

    List<PublicPromise> findAllBySeasonAndSettledFalse(int season);

    List<PublicPromise> findAllByTeamIdAndSeason(long teamId, int season);
}
