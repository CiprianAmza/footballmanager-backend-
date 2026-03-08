package com.footballmanagergamesimulator.repository;

import com.footballmanagergamesimulator.model.PressConference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PressConferenceRepository extends JpaRepository<PressConference, Long> {

    List<PressConference> findAllByTeamIdAndSeasonNumber(long teamId, int seasonNumber);
}
