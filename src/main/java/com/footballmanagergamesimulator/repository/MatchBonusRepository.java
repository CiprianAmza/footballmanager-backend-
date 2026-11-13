package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.MatchBonus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MatchBonusRepository extends JpaRepository<MatchBonus, Long> {

    List<MatchBonus> findAllByTeamIdAndSettledFalse(long teamId);

    List<MatchBonus> findAllByTeamIdAndSeason(long teamId, int season);
}
