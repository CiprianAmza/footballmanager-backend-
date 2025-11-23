package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompetitionTeamInfoRepository extends JpaRepository<CompetitionTeamInfo, Long> {

  List<CompetitionTeamInfo> findAllByRound(long round);

  List<CompetitionTeamInfo> findAllByRoundAndCompetitionIdAndSeasonNumber(long round, long competitionId, long seasonNumber);
  List<CompetitionTeamInfo> findAllBySeasonNumber(long seasonNumber);
  List<CompetitionTeamInfo> findAllByTeamIdAndSeasonNumber(long teamId, long seasonNumber);

}
