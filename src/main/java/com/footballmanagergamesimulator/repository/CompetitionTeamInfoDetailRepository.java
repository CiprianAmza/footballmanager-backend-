package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.CompetitionTeamInfoDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompetitionTeamInfoDetailRepository extends JpaRepository<CompetitionTeamInfoDetail, Long> {

  CompetitionTeamInfoDetail findCompetitionTeamInfoDetailByCompetitionIdAndRoundIdAndTeam1IdAndTeam2IdAndSeasonNumber(long competitionId, long roundId, long team1Id, long team2Id, long seasonNumber);

  List<CompetitionTeamInfoDetail> findAllByCompetitionIdAndRoundIdAndSeasonNumber(long competitionId, long roundId, long seasonNumber);
}
