package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.DirectorWish;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DirectorWishRepository extends JpaRepository<DirectorWish, Long> {

    List<DirectorWish> findAllByTeamIdAndSettledFalse(long teamId);

    List<DirectorWish> findAllByTeamIdAndSeason(long teamId, int season);

    List<DirectorWish> findAllByTeamIdOrderByIdDesc(long teamId);
}
