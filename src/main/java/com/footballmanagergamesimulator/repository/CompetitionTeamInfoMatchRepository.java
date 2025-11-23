package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CompetitionTeamInfoMatchRepository extends JpaRepository<CompetitionTeamInfoMatch, Long> {

    @Query("SELECT c FROM CompetitionTeamInfoMatch c WHERE c.seasonNumber = :seasonNumber AND ((c.team1Id = :teamId) OR (c.team2Id = :teamId))")
    List<CompetitionTeamInfoMatch> findAllBySeasonNumberAndTeamId(@Param("seasonNumber") String seasonNumber, @Param("teamId") long teamId);
}
