package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.TeamCompetitionDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TeamCompetitionDetailRepository extends JpaRepository<TeamCompetitionDetail, Long> {

  List<TeamCompetitionDetail> findTeamCompetitionDetailByTeamIdAndCompetitionId(long teamId, long competitionId);

  default TeamCompetitionDetail findFirstByTeamIdAndCompetitionId(long teamId, long competitionId) {
    List<TeamCompetitionDetail> results = findTeamCompetitionDetailByTeamIdAndCompetitionId(teamId, competitionId);
    return results.isEmpty() ? null : results.get(0);
  }
}
