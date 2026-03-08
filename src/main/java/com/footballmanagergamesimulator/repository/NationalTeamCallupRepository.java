package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.NationalTeamCallup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NationalTeamCallupRepository extends JpaRepository<NationalTeamCallup, Long> {

    List<NationalTeamCallup> findAllBySeasonNumberAndReturnedFalse(int seasonNumber);

    List<NationalTeamCallup> findAllByTeamIdAndSeasonNumber(long teamId, int seasonNumber);
}
